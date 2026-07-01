package com.classeve.earslate.session

import android.media.AudioAttributes
import android.media.AudioFocusRequest
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
 * Orchestrates the live conversation translator end-to-end:
 *
 *   bootstrap → connect leg(s) → setup → (capture audio ↔ play audio)* → close
 *
 * earslate is ALWAYS a bidirectional conversation translator. The
 * `gemini-3.5-live-translate-preview` model is single-target per session, so we
 * run one translate "leg" per direction:
 *   - a leg targeting the user's language    (the other person → me)
 *   - a leg targeting the other person's lang (me → the other person)
 * Both legs share the one mic; each leg's `echoTargetLanguage=false` makes it
 * stay SILENT when the input is already its target, so only one leg ever speaks
 * for a given utterance. When both languages match it collapses to a single leg.
 *
 * The model emits filler/anti-repeat silence as zero PCM; [isSilent] drops those
 * frames so the two legs' streams never interleave in the shared playback buffer.
 *
 * Structured concurrency: each session runs inside a [coroutineScope] so every
 * child (per-leg frame pump, per-leg liveness watcher, the awaitCancellation
 * hold) is a descendant of [lifecycleJob]. Cancelling it tears everything down.
 *
 * Reconnect: on an unexpected socket death of ANY leg, the lifecycle loop retries
 * the whole session up to [MAX_RECONNECT_ATTEMPTS] times with bounded backoff.
 */
class SessionCoordinator(
    private val bootstrapRepository: SessionBootstrapRepository,
    private val socketFactory: () -> LiveSocketClient,
    private val captureEngine: AudioCaptureEngine,
    private val playbackEngine: AudioPlaybackEngine,
    private val captionsStore: CaptionsStore,
    private val stateStore: RuntimeStateStore,
    private val audioManager: AudioManager,
    private val deviceMonitor: AudioDeviceMonitor,
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
    @Volatile private var sessionStartElapsed: Long = 0L
    @Volatile private var firstAudioSeen: Boolean = false
    @Volatile private var wasSocketDeath: Boolean = false
    @Volatile private var playbackGateActive: Boolean = false
    @Volatile private var gateCooldownJob: Job? = null
    @Volatile private var currentPolicy: TranslatorPolicy? = null

    private data class Leg(val targetCode: String, val socket: LiveSocketClient)

    fun start(policy: TranslatorPolicy) {
        synchronized(this) {
            if (lifecycleJob != null) {
                Log.i(TAG, "start ignored; already active")
                return
            }
            captionsStore.clear()
            stateStore.clearError()
            reconnectManager.reset()
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
            stateStore.setError(RuntimeError(kind = kind, message = t.message ?: "Bootstrap failed"))
            wasSocketDeath = false
            stateStore.set(RuntimeState.IDLE)
            return@coroutineScope
        }

        // Build the translate legs. One per distinct language direction.
        val myCode = LiveSessionConfigFactory.translateCodeFor(policy.myLanguage.bcp47)
        val theirCode = LiveSessionConfigFactory.translateCodeFor(policy.theirLanguage.bcp47)
        val targetCodes = if (theirCode.equals(myCode, ignoreCase = true)) {
            listOf(myCode)
        } else {
            listOf(myCode, theirCode)
        }
        val legs = targetCodes.map { Leg(targetCode = it, socket = socketFactory()) }
        Log.i(TAG, "session legs: ${targetCodes.joinToString()} model=${bootstrap.model}")

        stateStore.set(RuntimeState.CONNECTING)
        val url = buildWebSocketUrl(bootstrap)
        val headers = buildWebSocketHeaders(bootstrap)
        for (leg in legs) {
            try {
                leg.socket.connect(url, headers)
            } catch (t: Throwable) {
                Log.e(TAG, "socket connect failed: ${t.message}")
                // This early return sits BEFORE the try/finally below that owns
                // socket cleanup — without an explicit close here, any leg that
                // connected before this one failed is leaked (still open, no
                // owner, never closed).
                for (l in legs) runCatching { l.socket.close() }
                stateStore.setError(
                    RuntimeError(
                        kind = RuntimeError.Kind.CONNECT_FAILED,
                        message = t.message ?: "Could not reach Gemini Live",
                    ),
                )
                stateStore.set(RuntimeState.IDLE)
                return@coroutineScope
            }
        }

        // Per-leg frame pump + socket-death watcher.
        for (leg in legs) {
            launch { pumpFrames(leg.socket) }
            launch {
                val death = leg.socket.state.first {
                    it == LiveSocketState.CLOSED || it == LiveSocketState.FAILED
                }
                Log.i(TAG, "leg ${leg.targetCode} socket $death — tripping reconnect")
                wasSocketDeath = true
                outer.cancel(CancellationException("socket $death"))
            }
        }

        // All legs must reach OPEN.
        for (leg in legs) {
            if (!waitForSocketOpen(leg.socket)) {
                Log.w(TAG, "leg ${leg.targetCode} did not reach OPEN in 5s")
                // Same leak as the connect-failure path above: this return sits
                // before the try/finally that owns cleanup, so a leg that DID
                // reach OPEN while a sibling timed out would otherwise be left
                // connected with no owner.
                for (l in legs) runCatching { l.socket.close() }
                stateStore.set(RuntimeState.IDLE)
                return@coroutineScope
            }
        }

        // Send each leg its own translate setup (its target language).
        for (leg in legs) {
            val setupFrame = LiveSessionConfigFactory.buildSetup(
                model = bootstrap.model,
                targetLanguageCode = leg.targetCode,
                echoTargetLanguage = false,
                captionsEnabled = policy.captionsEnabled,
            )
            val sent = leg.socket.sendText(setupFrame)
            Log.i(TAG, "setup sent=$sent target=${leg.targetCode}")
        }

        // Clean, recognition-grade audio path: stay in MODE_NORMAL (NOT call
        // mode). Forcing MODE_IN_COMMUNICATION makes the platform apply telephony
        // voice-processing (AGC + call-grade AEC) to the mic, which mangled and
        // over-suppressed speech on-device. Playback routes to whatever output is
        // active (earbuds via A2DP, or the speaker); capture stays on the phone
        // mic. Speaker self-feedback is handled by the half-duplex gate below.
        val audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .build()
        runCatching { audioManager.requestAudioFocus(audioFocusRequest) }
        try {
            val sessionId = captureEngine.start(
                onBatch = { frame ->
                    runCatching {
                        if (!playbackGateActive) {
                            // Same audio to every leg; encode once.
                            val json = LiveSessionConfigFactory.buildAudioChunk(frame)
                            for (leg in legs) leg.socket.sendText(json)
                        }
                    }
                },
                onCaptureError = {
                    // Without this, a fatal AudioRecord.read() error silently ended
                    // capture: the Gemini socket stayed open, stateStore stayed at
                    // READY/LISTENING, and no audio was ever sent again — same
                    // reconnect trip as the socket-death watcher above, so the
                    // user actually sees a reconnect instead of a session that
                    // looks alive but has gone deaf.
                    Log.w(TAG, "capture engine reported a fatal error — tripping reconnect")
                    wasSocketDeath = true
                    outer.cancel(CancellationException("capture error"))
                },
            )
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
            // Independent playback session (no AEC coupling — we don't run AEC).
            playbackEngine.start()
            stateStore.set(RuntimeState.READY)
            reconnectManager.reset()
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
            for (leg in legs) runCatching { leg.socket.close() }
            runCatching { audioManager.abandonAudioFocusRequest(audioFocusRequest) }
            // Reserve-at-mint reconcile: report this mint's real wall-clock
            // length so the worker refunds the unused tail of the reserved
            // budget grant (it debited window*legs up front). Fire-and-forget on
            // the process scope so it still runs as this session's scope unwinds;
            // a missed close just leaves the (cost-bounded) reservation in place.
            val sid = bootstrap.sessionId
            if (sid != null && usageReporter != null &&
                bootstrap.source == com.classeve.earslate.bootstrap.BootstrapSource.REMOTE_WORKER
            ) {
                val elapsedSec = ((android.os.SystemClock.elapsedRealtime() - sessionStartElapsed) / 1000L)
                    .coerceAtLeast(0L).toInt()
                scope.launch { runCatching { usageReporter.close(sid, elapsedSec) } }
            }
            stateStore.set(RuntimeState.IDLE)
        }
    }

    private suspend fun pumpFrames(socket: LiveSocketClient) {
        socket.frames.collect { raw ->
            val parsed = LiveMessageParser.parse(raw)
            parsed.forEach { event ->
                runCatching { dispatch(event) }
                    .onFailure { Log.e(TAG, "dispatch failed for ${event.javaClass.simpleName}: ${it.message}", it) }
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
                // The translate model streams filler/anti-repeat silence as zero
                // PCM. Drop it so (a) the two legs' audio never interleaves in the
                // shared buffer and (b) we don't gate the mic or flip to PLAYING
                // for inaudible frames.
                if (isSilent(event.pcm24k)) return
                if (!firstAudioSeen) {
                    firstAudioSeen = true
                    val elapsed = android.os.SystemClock.elapsedRealtime() - sessionStartElapsed
                    stateStore.updateMetrics { it.copy(timeToFirstAudioMs = elapsed) }
                }
                if (shouldGateMic()) {
                    // Debounce the gate on the ACTUAL audio stream: each non-silent
                    // chunk holds the mic muted and pushes the re-open out. The mic
                    // re-opens only after audio has stopped flowing for the cooldown
                    // (which also covers the jitter-buffer drain), so the translated
                    // speech can't feed back into the mic on speaker.
                    playbackGateActive = true
                    gateCooldownJob?.cancel()
                    val cooldownMs = if (deviceMonitor.route.value == AudioRoute.SPEAKER) 500L else 200L
                    gateCooldownJob = scope.launch {
                        delay(cooldownMs)
                        playbackGateActive = false
                    }
                }
                playbackEngine.enqueue(event.pcm24k, event.sampleRateHz)
                if (stateStore.state.value == RuntimeState.LISTENING) {
                    stateStore.set(RuntimeState.PLAYING)
                }
            }
            is LiveEvent.CaptionDelta -> {
                captionsStore.appendDelta(event.text)
            }
            is LiveEvent.TurnComplete -> {
                captionsStore.commitLine()
                if (stateStore.state.value == RuntimeState.PLAYING) {
                    stateStore.set(RuntimeState.LISTENING)
                }
            }
            is LiveEvent.ResumptionHandle -> Unit // resumption disabled for the translate model
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

    // Half-duplex gate: mute the mic while the translator is speaking. Always on
    // for SPEAKER (the translated audio would otherwise be re-ingested and
    // re-translated by the other leg → feedback loop); opt-in on earbud routes
    // via externalOnly.
    private fun shouldGateMic(): Boolean {
        if (currentPolicy?.externalOnly == true) return true
        return deviceMonitor.route.value == AudioRoute.SPEAKER
    }

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
            // heartbeat() silently clamps to TranslateUsageReporter.MAX_DELTA_SECONDS
            // before it ever hits the wire (the worker itself caps a single call at
            // 300s) — after a long outage toSend can exceed that, and the excess
            // must be carried into the NEXT tick, not discarded. Resetting
            // carriedSeconds to 0 on every Ok (the old behavior) silently
            // under-reported usage whenever a backlog exceeded one call's cap.
            val leftoverAfterClamp = (toSend - TranslateUsageReporter.MAX_DELTA_SECONDS).coerceAtLeast(0)

            when (val res = reporter.heartbeat(toSend)) {
                is TranslateUsageReporter.Result.Ok -> {
                    lastReportElapsed = now
                    carriedSeconds = leftoverAfterClamp
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
                    Log.w(TAG, "heartbeat: transient error: ${res.message}; will retry the interval next tick")
                }
            }
        }
    }

    private suspend fun waitForSocketOpen(socket: LiveSocketClient): Boolean {
        val result = withTimeoutOrNull(5_000) {
            socket.state.first { it == LiveSocketState.OPEN }
        }
        return result != null
    }

    private fun buildWebSocketUrl(bootstrap: com.classeve.earslate.bootstrap.SessionBootstrap): String =
        when (bootstrap.source) {
            // Ephemeral tokens (auth_tokens/…) MUST hit the *Constrained* bidi endpoint and
            // authenticate via the Authorization header (see buildWebSocketHeaders), NOT a
            // query param — Gemini rejects ?access_token= as an "unregistered caller".
            com.classeve.earslate.bootstrap.BootstrapSource.REMOTE_WORKER ->
                GEMINI_LIVE_CONSTRAINED
            com.classeve.earslate.bootstrap.BootstrapSource.LOCAL_DEV ->
                "$GEMINI_LIVE_BASE_V1BETA?key=${bootstrap.ephemeralToken}"
        }

    private fun buildWebSocketHeaders(bootstrap: com.classeve.earslate.bootstrap.SessionBootstrap): Map<String, String> =
        when (bootstrap.source) {
            com.classeve.earslate.bootstrap.BootstrapSource.REMOTE_WORKER ->
                mapOf("Authorization" to "Token ${bootstrap.ephemeralToken}")
            com.classeve.earslate.bootstrap.BootstrapSource.LOCAL_DEV ->
                emptyMap()
        }

    companion object {
        private const val TAG = "SessionCoord"
        private const val MAX_RECONNECT_ATTEMPTS = 4

        /** Output PCM peak below this (model emits zero-PCM silence; peak≈1) is treated as silence. */
        private const val SILENCE_PEAK = 48

        /** ~60 s. The worker caps a single delta at 300 s. */
        private const val HEARTBEAT_INTERVAL_MS = 60_000L
        private const val GEMINI_LIVE_CONSTRAINED =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContentConstrained"
        private const val GEMINI_LIVE_BASE_V1BETA =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

        /** True when the 16-bit PCM frame is (near-)silent — peak amplitude under [SILENCE_PEAK]. */
        private fun isSilent(pcm: ByteArray): Boolean {
            var i = 0
            var peak = 0
            while (i < pcm.size - 1) {
                val lo = pcm[i].toInt() and 0xff
                val hi = pcm[i + 1].toInt()
                var s = (hi shl 8) or lo
                if (s and 0x8000 != 0) s -= 0x10000
                val a = if (s < 0) -s else s
                if (a > peak) {
                    peak = a
                    if (peak >= SILENCE_PEAK) return false
                }
                i += 2
            }
            return true
        }
    }
}
