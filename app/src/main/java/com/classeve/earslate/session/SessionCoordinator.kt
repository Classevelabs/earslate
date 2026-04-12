package com.classeve.earslate.session

import android.util.Log
import com.classeve.earslate.audio.AudioCaptureEngine
import com.classeve.earslate.audio.AudioPlaybackEngine
import com.classeve.earslate.bootstrap.SessionBootstrapRepository
import com.classeve.earslate.live.LiveEvent
import com.classeve.earslate.live.LiveMessageParser
import com.classeve.earslate.live.LiveSessionConfigFactory
import com.classeve.earslate.live.LiveSocketClient
import com.classeve.earslate.live.LiveSocketState
import com.classeve.earslate.live.PromptConfigBuilder
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
            val kind = if (t.message?.contains("GEMINI_API_KEY", ignoreCase = true) == true) {
                RuntimeError.Kind.MISSING_API_KEY
            } else {
                RuntimeError.Kind.BOOTSTRAP_FAILED
            }
            stateStore.setError(
                RuntimeError(
                    kind = kind,
                    message = t.message ?: "Bootstrap failed",
                ),
            )
            stateStore.set(RuntimeState.IDLE)
            return@coroutineScope
        }

        stateStore.set(RuntimeState.CONNECTING)
        val url = buildWebSocketUrl(bootstrap.ephemeralToken)
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

        try {
            playbackEngine.start()
            captureEngine.start { frame ->
                runCatching {
                    if (!playbackGateActive) {
                        val json = LiveSessionConfigFactory.buildAudioChunk(frame)
                        socketClient.sendText(json)
                    }
                }
            }
            stateStore.set(RuntimeState.READY)
            // Successful setup — fresh retry budget for future transient blips.
            reconnectManager.reset()
            awaitCancellation()
        } finally {
            playbackGateActive = false
            gateCooldownJob?.cancel()
            runCatching { captureEngine.stop() }
            runCatching { playbackEngine.stop(graceful = true) }
            runCatching { socketClient.close() }
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
                if (currentPolicy?.externalOnly == true) {
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
                if (currentPolicy?.externalOnly == true) {
                    gateCooldownJob = scope.launch {
                        delay(500)
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

    private suspend fun waitForSocketOpen(): Boolean {
        val result = withTimeoutOrNull(5_000) {
            socketClient.state.first { it == LiveSocketState.OPEN }
        }
        return result != null
    }

    private fun buildWebSocketUrl(apiKey: String): String =
        "$GEMINI_LIVE_BASE?key=$apiKey"

    companion object {
        private const val TAG = "SessionCoord"
        private const val MAX_RECONNECT_ATTEMPTS = 4
        private const val GEMINI_LIVE_BASE =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
    }
}
