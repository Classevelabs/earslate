package com.classeve.earslate.session

import android.media.AudioManager
import android.util.Log
import com.classeve.earslate.audio.AudioCaptureEngine
import com.classeve.earslate.audio.AudioDeviceMonitor
import com.classeve.earslate.audio.AudioPlaybackEngine
import com.classeve.earslate.audio.AudioRoute
import com.classeve.earslate.bootstrap.AuthRequiredException
import com.classeve.earslate.bootstrap.DailyLimitReachedException
import com.classeve.earslate.bootstrap.SessionBootstrapRepository
import com.classeve.earslate.bootstrap.SubscriptionRequiredException
import com.classeve.earslate.live.LiveEvent
import com.classeve.earslate.live.LiveMessageParser
import com.classeve.earslate.live.LiveSessionConfigFactory
import com.classeve.earslate.live.LiveSocketClient
import com.classeve.earslate.live.LiveSocketState
import com.classeve.earslate.live.PromptConfigBuilder
import com.classeve.earslate.network.TranslateUsageReporter
import com.classeve.earslate.ui.captions.CaptionsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Orchestrates the live translator runtime end-to-end:
 *
 *   bootstrap → connect → setup → (capture audio ↔ play audio)* → close
 *
 * Structured concurrency: each live session runs inside a [coroutineScope] so
 * every child (frame pump, socket liveness watcher, the awaitCancellation hold)
 * is a descendant of [lifecycleJob]. Cancelling the lifecycle job — via [stop]
 * or a socket-death trip — runs the `finally` block, which deterministically
 * tears down capture, playback, and the socket.
 *
 * Reconnect: on an unexpected socket death, the lifecycle loop automatically
 * retries up to [MAX_RECONNECT_ATTEMPTS] times with [ReconnectManager]'s
 * bounded backoff. The counter resets after every successful setup, so long
 * sessions survive multiple transient network blips without forcing the user
 * to tap start again.
 */
class SessionCoordinator(
    private val bootstrapRepository: SessionBootstrapRepository,
    private val socketClient: LiveSocketClient,
    private val captureEngine: AudioCaptureEngine,
    private val playbackEngine: AudioPlaybackEngine,
    private val captionsStore: CaptionsStore,
    private val stateStore: RuntimeStateStore,
    private val audioManager: AudioManager,
    private val deviceMonitor: AudioDeviceMonitor,
    /**
     * Reports active session seconds to the Worker. Null in dev builds where
     * the local-properties bootstrap is in use — the worker would reject the
     * call anyway. Tests can leave it null too.
     */
    private val usageReporter: TranslateUsageReporter? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val reconnectManager = ReconnectManager()

    private val _events = MutableSharedFlow<LiveEvent>(
        replay = 0,
        extraBufferCapacity = 64,
    )
    val events: SharedFlow<LiveEvent> = _events.asSharedFlow()

    @Volatile private var lifecycleJob: Job? = null
    @Volatile private var resumptionHandle: String? = null
    @Volatile private var sessionStartElapsed: Long = 0L
    @Volatile private var firstAudioSeen: Boolean = false
    @Volatile private var wasSocketDeath: Boolean = false
    @Volatile private var playbackGateActive: Boolean = false
    @Volatile private var gateCooldownJob: Job? = null
    @Volatile private var currentPolicy: TranslatorPolicy? = null

    fun start(policy: TranslatorPolicy) {
        synchronized(this) {
            if (lifecycleJob != null) {
                Log.i(TAG, "start ignored; already active")
                return
            }
            captionsStore.clear()
            stateStore.clearError()
            reconnectManager.reset()
            resumptionHandle = null
            currentPolicy = policy
            playbackGateActive = false
            gateCooldownJob?.cancel()

            lifecycleJob = scope.launch {
                try {
                    reconnectLoop(policy)
                } catch (_: CancellationException) {
                    // expected on stop()
                } catch (t: Throwable) {
                    Log.e(TAG, "session crashed: ${t.message}", t)
                    stateStore.setError(
                        RuntimeError(
                            kind = RuntimeError.Kind.UNKNOWN,
                            message = t.message ?: "Session crashed",
                        ),
                    )
                    stateStore.set(RuntimeState.IDLE)
                } finally {
                    lifecycleJob = null
                }
            }
        }
    }

    fun stop() {
        lifecycleJob?.cancel()
    }

    private suspend fun reconnectLoop(policy: TranslatorPolicy) {
        while (true) {
            sessionStartElapsed = android.os.SystemClock.elapsedRealtime()
            firstAudioSeen = false
            wasSocketDeath = false

            try {
                runSession(policy)
            } catch (_: CancellationException) {
                // either user stop or socket-death trip — both catch here
            }

            if (!wasSocketDeath) {
                // normal termination (user stop or fatal failure)
                return
            }
            if (reconnectManager.attemptNumber >= MAX_RECONNECT_ATTEMPTS) {
                Log.w(TAG, "reconnect budget exhausted after ${reconnectManager.attemptNumber} attempts")
                stateStore.setError(
                    RuntimeError(
                        kind = RuntimeError.Kind.CONNECT_FAILED,
                        message = "Lost connection and could not reconnect. Tap start to try again.",
                    ),
                )
                return
            }

            stateStore.updateMetrics { it.copy(reconnectCount = it.reconnectCount + 1) }
            stateStore.set(RuntimeState.RECONNECTING)
            val delayMs = reconnectManager.nextDelayMs()
            Log.i(TAG, "reconnect attempt ${reconnectManager.attemptNumber} in ${delayMs}ms")
            delay(delayMs)
        }
    }

    private suspend fun runSession(policy: TranslatorPolicy): Unit = coroutineScope {
        val outer: CoroutineScope = this

        stateStore.set(RuntimeState.BOOTSTRAPPING)
        val bootstrap = try {
            bootstrapRepository.bootstrap()
        } catch (t: Throwable) {
            Log.e(TAG, "bootstrap failed: ${t.message}")
            val kind = when {
                t is AuthRequiredException -> RuntimeError.Kind.AUTH_REQUIRED
                t is SubscriptionRequiredException -> RuntimeError.Kind.SUBSCRIPTION_REQUIRED
                t is DailyLimitReachedException -> RuntimeError.Kind.DAILY_LIMIT_REACHED
                t.message?.contains("GEMINI_API_KEY", ignoreCase = true) == true ->
                    RuntimeError.Kind.MISSING_API_KEY
                else -> RuntimeError.Kind.BOOTSTRAP_FAILED
            }
            stateStore.setError(
                RuntimeError(
                    kind = kind,
                    message = t.message ?: "Bootstrap failed",
                ),
            )
            // Reconnect-loop must NOT bounce on a deterministic auth/billing
            // failure — wasSocketDeath stays false and reconnectLoop returns.
            wasSocketDeath = false
            stateStore.set(RuntimeState.IDLE)
            return@coroutineScope
        }

        stateStore.set(RuntimeState.CONNECTING)
        val url = buildWebSocketUrl(bootstrap)
        try {
            socketClient.connect(url)
        } catch (t: Throwable) {
            Log.e(TAG, "socket connect failed: ${t.message}")
            stateStore.setError(
                RuntimeError(
                    kind = RuntimeError.Kind.CONNECT_FAILED,
                    message = t.message ?: "Could not reach Gemini Live",
                ),
            )
            stateStore.set(RuntimeState.IDLE)
            return@coroutineScope
        }

        // Frame pump — parses JSON → LiveEvents → dispatch
        launch { pumpFrames() }

        // Socket-death watcher — flags the exit reason and cancels the scope
        launch {
            val death = socketClient.state.first {
                it == LiveSocketState.CLOSED || it == LiveSocketState.FAILED
            }
            Log.i(TAG, "socket $death — tripping reconnect loop")
            wasSocketDeath = true
            outer.cancel(CancellationException("socket $death"))
        }

        if (!waitForSocketOpen()) {
            Log.w(TAG, "socket did not reach OPEN in 5s")
            stateStore.set(RuntimeState.IDLE)
            return@coroutineScope
        }

        val systemInstruction = PromptConfigBuilder.build(policy)
        val setupFrame = LiveSessionConfigFactory.buildSetup(
            policy = policy,
            model = bootstrap.model,
            systemInstruction = systemInstruction,
            resumptionHandle = resumptionHandle,
        )
        val sent = socketClient.sendText(setupFrame)
        Log.i(TAG, "setup frame sent=$sent model=${bootstrap.model}")

        // Flip to VoIP mode BEFORE constructing AudioRecord — some HALs only wire
        // the AEC path at open time; changing mode mid-session is a no-op there.
        val priorAudioMode = audioManager.mode
        runCatching { audioManager.mode = AudioManager.MODE_IN_COMMUNICATION }
        try {
            // Capture first: its audioSessionId is the one playback binds to so
            // hardware AEC can correlate the reference signal with the mic stream.
            val sessionId = captureEngine.start { frame ->
                runCatching {
                    if (!playbackGateActive) {
                        val json = LiveSessionConfigFactory.buildAudioChunk(frame)
                        socketClient.sendText(json)
                    }
                }
            }
            if (sessionId == 0) {
                Log.w(TAG, "capture failed to start")
                stateStore.setError(
                    RuntimeError(
                        kind = RuntimeError.Kind.UNKNOWN,
                        message = "Could not open the microphone.",
                    ),
                )
                return@coroutineScope
            }
            playbackEngine.start(audioSessionId = sessionId)
            stateStore.set(RuntimeState.READY)
            // Successful setup — fresh retry budget for future transient blips.
            reconnectManager.reset()
            // Heartbeat pump — reports live-session seconds to the Worker so
            // the daily cap is enforced server-side. Wall-clock based: a long
            // GC pause shouldn't shorten the report, and a system sleep
            // shouldn't lengthen it either. Skipped for LOCAL_DEV bootstraps
            // because the worker would 401 the call anyway.
            val needHeartbeat = usageReporter != null &&
                bootstrap.source == com.classeve.earslate.bootstrap.BootstrapSource.REMOTE_WORKER
            if (needHeartbeat) {
                launch { runHeartbeat(outer) }
            }
            awaitCancellation()
        } finally {
            playbackGateActive = false
            gateCooldownJob?.cancel()
            runCatching { captureEngine.stop() }
            runCatching { playbackEngine.stop(graceful = true) }
            runCatching { socketClient.close() }
            runCatching { audioManager.mode = priorAudioMode }
            stateStore.set(RuntimeState.IDLE)
        }
    }

    private suspend fun pumpFrames() {
        socketClient.frames.collect { raw ->
            val parsed = LiveMessageParser.parse(raw)
            parsed.forEach { event ->
                runCatching { dispatch(event) }
                    .onFailure { Log.e(TAG, "dispatch failed for $event: ${it.message}", it) }
                _events.tryEmit(event)
            }
        }
    }

    private fun dispatch(event: LiveEvent) {
        when (event) {
            is LiveEvent.SetupComplete -> {
                Log.i(TAG, "setupComplete — entering LISTENING")
                stateStore.set(RuntimeState.LISTENING)
            }
            is LiveEvent.AudioChunk -> {
                if (!firstAudioSeen) {
                    firstAudioSeen = true
                    val elapsed = android.os.SystemClock.elapsedRealtime() - sessionStartElapsed
                    stateStore.updateMetrics { it.copy(timeToFirstAudioMs = elapsed) }
                }
                if (shouldGateMic()) {
                    playbackGateActive = true
                    gateCooldownJob?.cancel()
                }
                playbackEngine.enqueue(event.pcm24k)
                // Benign TOCTOU race: state may change between read and set, but this
                // is purely cosmetic UI state (LISTENING→PLAYING). A stale read only
                // causes a harmless no-op transition; no invariant is violated.
                if (stateStore.state.value == RuntimeState.LISTENING) {
                    stateStore.set(RuntimeState.PLAYING)
                }
            }
            is LiveEvent.CaptionDelta -> {
                captionsStore.appendDelta(event.text)
            }
            is LiveEvent.TurnComplete -> {
                captionsStore.commitLine()
                if (shouldGateMic()) {
                    val cooldownMs = if (deviceMonitor.route.value == AudioRoute.SPEAKER) 1000L else 500L
                    gateCooldownJob = scope.launch {
                        delay(cooldownMs)
                        playbackGateActive = false
                    }
                }
                if (stateStore.state.value == RuntimeState.PLAYING) {
                    stateStore.set(RuntimeState.LISTENING)
                }
            }
            is LiveEvent.ResumptionHandle -> {
                resumptionHandle = event.handle
                stateStore.updateMetrics { it.copy(resumeSuccessCount = it.resumeSuccessCount + 1) }
            }
            is LiveEvent.GoAway -> {
                Log.i(TAG, "server GoAway — will reconnect")
                stateStore.set(RuntimeState.RECONNECTING)
            }
            is LiveEvent.SocketClosed -> Unit
            is LiveEvent.Error -> {
                Log.w(TAG, "live error: ${event.message}")
                stateStore.set(RuntimeState.DEGRADED)
            }
        }
    }

    // Half-duplex gate: mute the mic while the translator is speaking.
    // Always active on SPEAKER (hardware AEC alone isn't always enough to
    // prevent the translator re-ingesting its own output); opt-in on earbud
    // routes via the externalOnly user setting.
    private fun shouldGateMic(): Boolean {
        if (currentPolicy?.externalOnly == true) return true
        return deviceMonitor.route.value == AudioRoute.SPEAKER
    }

    /**
     * Heartbeat pump that runs as a child of [outer] for the lifetime of the
     * Live session. Each tick reports the wall-clock seconds elapsed since the
     * last successful POST so the worker accumulates real-listening time, not
     * timer ticks (a long GC pause or system sleep shouldn't be billed).
     *
     * Three terminal cases:
     *   - 429 → set DAILY_LIMIT_REACHED, cancel outer (session ends).
     *   - 401 (and refresh failed) → set AUTH_REQUIRED, cancel outer.
     *   - everything else → log + accumulate the unsent seconds for the next tick.
     */
    private suspend fun runHeartbeat(outer: CoroutineScope) {
        val reporter = usageReporter ?: return
        var lastReportElapsed = android.os.SystemClock.elapsedRealtime()
        var carriedSeconds = 0
        while (true) {
            delay(HEARTBEAT_INTERVAL_MS)
            val now = android.os.SystemClock.elapsedRealtime()
            val deltaSeconds = ((now - lastReportElapsed) / 1000L).toInt()
            if (deltaSeconds <= 0 && carriedSeconds == 0) continue
            val toSend = (deltaSeconds + carriedSeconds).coerceAtLeast(1)

            when (val res = reporter.heartbeat(toSend)) {
                is TranslateUsageReporter.Result.Ok -> {
                    lastReportElapsed = now
                    carriedSeconds = 0
                    if (res.dailyRemainingSeconds <= 0) {
                        Log.i(TAG, "heartbeat: budget exhausted (remaining=0)")
                        stateStore.setError(
                            RuntimeError(
                                kind = RuntimeError.Kind.DAILY_LIMIT_REACHED,
                                message = "Daily translation budget reached. Resets at 00:00 UTC.",
                            ),
                        )
                        wasSocketDeath = false
                        outer.cancel(CancellationException("daily limit reached"))
                        return
                    }
                }
                is TranslateUsageReporter.Result.LimitReached -> {
                    Log.i(TAG, "heartbeat: limit reached")
                    stateStore.setError(
                        RuntimeError(
                            kind = RuntimeError.Kind.DAILY_LIMIT_REACHED,
                            message = "Daily translation budget reached. Resets at 00:00 UTC.",
                        ),
                    )
                    wasSocketDeath = false
                    outer.cancel(CancellationException("daily limit reached"))
                    return
                }
                is TranslateUsageReporter.Result.AuthRequired -> {
                    Log.w(TAG, "heartbeat: auth required — bouncing to sign-in")
                    stateStore.setError(
                        RuntimeError(
                            kind = RuntimeError.Kind.AUTH_REQUIRED,
                            message = "Sign-in required — please pair this device again.",
                        ),
                    )
                    wasSocketDeath = false
                    outer.cancel(CancellationException("auth required"))
                    return
                }
                is TranslateUsageReporter.Result.TransientError -> {
                    Log.w(TAG, "heartbeat: transient error: ${res.message}; carrying ${toSend}s")
                    // Carry the seconds forward so a network blip doesn't lose
                    // them. We still advance lastReportElapsed because the time
                    // is encoded into carriedSeconds for the next tick.
                    lastReportElapsed = now
                    carriedSeconds = toSend
                }
            }
        }
    }

    private suspend fun waitForSocketOpen(): Boolean {
        val result = withTimeoutOrNull(5_000) {
            socketClient.state.first { it == LiveSocketState.OPEN }
        }
        return result != null
    }

    // The "ephemeralToken" carried by SessionBootstrap is either:
    //   - a long-lived Gemini API key (dev mode, local.properties)
    //   - a single-use ephemeral token minted by the ClassEve Worker
    //     via AuthTokenService.CreateToken (production path)
    //
    // In production we use access_token=<ephemeral> which routes via the
    // v1alpha bidi endpoint. In dev the repository sets the source to
    // LOCAL_DEV and the token IS the raw API key — we switch URL shape
    // accordingly so the existing dev flow keeps working.
    private fun buildWebSocketUrl(bootstrap: com.classeve.earslate.bootstrap.SessionBootstrap): String =
        when (bootstrap.source) {
            com.classeve.earslate.bootstrap.BootstrapSource.REMOTE_WORKER ->
                "$GEMINI_LIVE_BASE?access_token=${bootstrap.ephemeralToken}"
            com.classeve.earslate.bootstrap.BootstrapSource.LOCAL_DEV ->
                "$GEMINI_LIVE_BASE_V1BETA?key=${bootstrap.ephemeralToken}"
        }

    companion object {
        private const val TAG = "SessionCoord"
        private const val MAX_RECONNECT_ATTEMPTS = 4
        /** ~60 s. The worker caps a single delta at 300 s; we report often enough that no tick exceeds the cap. */
        private const val HEARTBEAT_INTERVAL_MS = 60_000L
        private const val GEMINI_LIVE_BASE =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent"
        private const val GEMINI_LIVE_BASE_V1BETA =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
    }
}
