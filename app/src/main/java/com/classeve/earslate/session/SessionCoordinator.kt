package com.classeve.earslate.session

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log
import com.classeve.earslate.audio.AudioCaptureEngine
import com.classeve.earslate.audio.AudioDeviceMonitor
import com.classeve.earslate.audio.AudioPlaybackEngine
import com.classeve.earslate.audio.AudioRoute
import com.classeve.earslate.bootstrap.SessionBootstrap
import com.classeve.earslate.bootstrap.SessionBootstrapRepository
import com.classeve.earslate.live.LiveEvent
import com.classeve.earslate.live.LiveSessionConfigFactory
import com.classeve.earslate.live.LiveSocketClient
import com.classeve.earslate.live.LiveSocketState
import com.classeve.earslate.live.TranslationLiveProtocol
import com.classeve.earslate.live.TranslationLiveProtocols
import com.classeve.earslate.ui.captions.CaptionsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
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
import kotlinx.coroutines.withContext

/**
 * Orchestrates the live conversation translator end-to-end:
 *
 *   bootstrap → connect leg(s) → setup → (capture audio ↔ play audio)* → close
 *
 * Gemini supports safe bidirectional operation through one translate "leg"
 * per direction:
 *   - a leg targeting the user's language    (the other person → me)
 *   - a leg targeting the other person's lang (me → the other person)
 * Both legs share the one mic; each leg's `echoTargetLanguage=false` makes it
 * stay SILENT when the input is already its target, so only one leg ever speaks
 * for a given utterance. When both languages match it collapses to a single leg.
 * OpenAI's dedicated translation endpoint has one output language and no
 * echo-suppression control, so it uses the primary listen-to-my-language leg.
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

    /**
     * Set by [stop] so the reconnect loop can tell a user-requested teardown
     * from a socket death. Without it, a stop during BOOTSTRAPPING/CONNECTING
     * fell through the loop's `catch (CancellationException)` and left the state
     * store on a live-looking state with no job behind it — the UI then rendered
     * STOP forever against a dead session and the button was a permanent no-op.
     */
    @Volatile private var stopRequested: Boolean = false

    /**
     * Which leg currently owns playback and the caption line.
     *
     * Both legs share one [AudioPlaybackEngine] and one [CaptionsStore], and both
     * receive the SAME mic audio, so without an owner their chunks interleave in
     * the shared jitter buffer and their transcripts interleave in the shared
     * caption builder — two voices and two languages spliced together, which is
     * what made the output sound garbled. `echoTargetLanguage=false` is what
     * usually keeps one leg quiet, but it is a model behaviour, not a guarantee,
     * so the timeline needs an explicit owner too.
     */
    private val legLock = Any()
    private var speakingLeg: String? = null
    private var lastLegOutputElapsed: Long = 0L

    // Arrival-jitter measurement — see recordArrival. Guarded by legLock.
    private val lastArrivalPerLeg = HashMap<String, Long>()
    private var arrivalCount: Long = 0L
    private var arrivalSumMs: Long = 0L
    private var arrivalMaxGapMs: Long = 0L
    private var arrivalBytes: Long = 0L

    private data class Leg(
        val targetCode: String,
        val bootstrap: SessionBootstrap,
        val protocol: TranslationLiveProtocol,
        val socket: LiveSocketClient,
        val setupReady: CompletableDeferred<Unit> = CompletableDeferred(),
    )

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
            stopRequested = false
            releaseLeg()

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
                } finally {
                    synchronized(this@SessionCoordinator) { lifecycleJob = null }
                    // THE INVARIANT: when the lifecycle job ends, the runtime is
                    // idle. Several early-return paths in runSession sit above the
                    // try/finally that owns teardown, and a cancellation during
                    // CONNECTING unwinds through none of them — so this is the one
                    // place that can guarantee the UI never shows an active session
                    // with nothing running behind it.
                    if (stateStore.state.value != RuntimeState.IDLE) {
                        stateStore.set(RuntimeState.IDLE)
                    }
                    releaseLeg()
                }
            }
        }
    }

    fun stop() {
        stopRequested = true
        val job = synchronized(this) { lifecycleJob }
        if (job == null) {
            // Nothing is running. If the store still reports an active state it is
            // a leftover from an earlier teardown, and leaving it there is exactly
            // what made STOP a dead button — resolve it so the UI can recover
            // without a force-stop.
            if (stateStore.state.value != RuntimeState.IDLE) {
                Log.w(TAG, "stop with no live job; clearing stale ${stateStore.state.value}")
                stateStore.set(RuntimeState.IDLE)
            }
            return
        }
        job.cancel()
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

            // A user stop must never be mistaken for a socket death and retried.
            if (stopRequested) {
                return
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

        // Build the translate legs. One per distinct language direction.
        val myCode = LiveSessionConfigFactory.translateCodeFor(policy.myLanguage.bcp47)
        val theirCode = LiveSessionConfigFactory.translateCodeFor(policy.theirLanguage.bcp47)
        stateStore.set(RuntimeState.BOOTSTRAPPING)
        val legs = try {
            val primary = bootstrapRepository.bootstrap(policy.provider, myCode)
            val specs = mutableListOf(myCode to primary)
            // Gemini's echoTargetLanguage=false supports two simultaneous
            // directions safely. OpenAI's dedicated translation session has
            // one output language and no echo-suppression control, so opening
            // two sockets would produce overlapping audio. Keep its core
            // listening path single-target instead of shipping broken output.
            if (
                primary.provider == TranslationProvider.GEMINI &&
                !theirCode.equals(myCode, ignoreCase = true)
            ) {
                specs += theirCode to bootstrapRepository.bootstrap(primary.provider, theirCode)
            }
            specs.map { (targetCode, bootstrap) ->
                Leg(targetCode, bootstrap, TranslationLiveProtocols.forProvider(bootstrap.provider), socketFactory())
            }
        } catch (t: Throwable) {
            Log.e(TAG, "bootstrap failed: ${t.javaClass.simpleName}")
            stateStore.setError(
                RuntimeError(
                    kind = RuntimeError.Kind.BOOTSTRAP_FAILED,
                    message = t.message ?: "Could not start the translation service.",
                ),
            )
            wasSocketDeath = false
            stateStore.set(RuntimeState.IDLE)
            return@coroutineScope
        }
        Log.i(
            TAG,
            "session legs: ${legs.joinToString { "${it.targetCode}:${it.bootstrap.provider.wireValue}" }}",
        )

        stateStore.set(RuntimeState.CONNECTING)
        // Start frame collectors before connecting. OpenAI can emit its first
        // session event immediately after the upgrade; SharedFlow has no replay.
        for (leg in legs) launch { pumpFrames(leg) }
        for (leg in legs) {
            try {
                leg.socket.connect(
                    leg.bootstrap.webSocketUrl,
                    leg.protocol.headers(leg.bootstrap),
                )
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
                        message = "Could not reach the selected translation provider.",
                    ),
                )
                stateStore.set(RuntimeState.IDLE)
                return@coroutineScope
            }
        }

        // Per-leg socket-death watcher.
        for (leg in legs) {
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
            val setupFrame = leg.protocol.setupFrame(
                bootstrap = leg.bootstrap,
                targetLanguageCode = leg.targetCode,
                captionsEnabled = policy.captionsEnabled,
            )
            val sent = leg.socket.sendText(setupFrame)
            Log.i(TAG, "setup sent=$sent target=${leg.targetCode}")
            if (!sent) {
                for (item in legs) runCatching { item.socket.close() }
                stateStore.setError(
                    RuntimeError(RuntimeError.Kind.CONNECT_FAILED, "Could not configure the translation session."),
                )
                stateStore.set(RuntimeState.IDLE)
                return@coroutineScope
            }
        }

        // Do not open the microphone until every provider has acknowledged the
        // session configuration. This prevents silently dropping the first words.
        for (leg in legs) {
            val ready = withTimeoutOrNull(7_000) { leg.setupReady.await(); true } ?: false
            if (!ready) {
                for (item in legs) runCatching { item.socket.close() }
                stateStore.setError(
                    RuntimeError(RuntimeError.Kind.CONNECT_FAILED, "The translation provider did not become ready."),
                )
                stateStore.set(RuntimeState.IDLE)
                return@coroutineScope
            }
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
                            for (leg in legs) {
                                leg.socket.sendText(leg.protocol.audioFrame(frame))
                            }
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
            awaitCancellation()
        } finally {
            playbackGateActive = false
            gateCooldownJob?.cancel()
            runCatching { captureEngine.stop() }
            runCatching { playbackEngine.stop(graceful = true) }
            withContext(NonCancellable) {
                for (leg in legs) {
                    leg.protocol.gracefulCloseFrame()?.let { leg.socket.sendText(it) }
                }
                if (legs.any { it.protocol.gracefulCloseFrame() != null }) delay(250)
                for (leg in legs) runCatching { leg.socket.close() }
            }
            runCatching { audioManager.abandonAudioFocusRequest(audioFocusRequest) }
            stateStore.set(RuntimeState.IDLE)
        }
    }

    private suspend fun pumpFrames(leg: Leg) {
        leg.socket.frames.collect { raw ->
            val parsed = leg.protocol.parse(raw)
            parsed.forEach { event ->
                if (event is LiveEvent.SetupComplete) leg.setupReady.complete(Unit)
                runCatching { dispatch(leg.targetCode, event) }
                    .onFailure { Log.e(TAG, "dispatch failed for ${event.javaClass.simpleName}: ${it.message}", it) }
                _events.tryEmit(event)
                if (event is LiveEvent.Error) {
                    leg.socket.close(1011, "provider_error")
                }
            }
        }
    }

    private fun dispatch(legCode: String, event: LiveEvent) {
        when (event) {
            is LiveEvent.SetupComplete -> {
                Log.i(TAG, "setupComplete — entering LISTENING")
                stateStore.set(RuntimeState.LISTENING)
            }
            is LiveEvent.AudioChunk -> {
                // Measured on every arriving frame, before any filtering — this is
                // a statement about what the network delivered, not about what we
                // chose to play.
                recordArrival(legCode, event.pcm24k.size)
                // The translate model streams filler/anti-repeat silence as zero
                // PCM. Drop it so we don't gate the mic or flip to PLAYING for
                // inaudible frames. (Keeping the two legs from interleaving is
                // now claimLeg's job, not this threshold's.)
                if (isSilent(event.pcm24k)) return
                // Only the leg that owns this utterance may reach the shared
                // jitter buffer. Without this, both legs' chunks interleave into
                // one stream and play as two spliced voices.
                if (!claimLeg(legCode)) return
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
                // Same ownership rule as audio. Both legs transcribe the same mic
                // audio into different languages, and CaptionsStore is a single
                // shared StringBuilder — unowned deltas interleave two languages
                // into one caption line.
                if (!claimLeg(legCode)) return
                captionsStore.appendDelta(event.text)
            }
            is LiveEvent.TurnComplete -> {
                releaseLeg(legCode)
                // Tell the buffer this quiet is expected, so it does not pay for
                // latency it does not need.
                playbackEngine.notifyTurnEnd()
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

    /**
     * Try to take ownership of playback + captions for [legCode].
     *
     * Granted when nothing owns the stream, when this leg already owns it, or
     * when the current owner has produced nothing for [LEG_HANDOVER_IDLE_MS] —
     * that last case matters because a leg is not guaranteed to send
     * `turnComplete`, and without an idle handover a silent owner would hold the
     * stream for the rest of the session.
     */
    private fun claimLeg(legCode: String): Boolean = synchronized(legLock) {
        val now = android.os.SystemClock.elapsedRealtime()
        val owner = speakingLeg
        val mayTake = owner == null ||
            owner == legCode ||
            now - lastLegOutputElapsed > LEG_HANDOVER_IDLE_MS
        if (!mayTake) return false
        if (owner != legCode) Log.i(TAG, "playback owner → $legCode")
        speakingLeg = legCode
        lastLegOutputElapsed = now
        true
    }

    /** Release the stream. [legCode] null releases unconditionally (session teardown). */
    private fun releaseLeg(legCode: String? = null) = synchronized(legLock) {
        if (legCode == null || speakingLeg == legCode) {
            speakingLeg = null
            lastLegOutputElapsed = 0L
        }
        if (legCode == null) lastArrivalPerLeg.clear()
    }

    /**
     * Measures how evenly the provider's audio actually arrives, and what the
     * buffer had to do about it. This is the number that decides whether playback
     * sounds smooth, so it is measured rather than assumed — a mean gap well
     * under the buffer's target latency with a small max is smooth; a max gap
     * above the target is an audible stutter.
     */
    private fun recordArrival(legCode: String, bytes: Int) {
        val log = synchronized(legLock) {
            val now = android.os.SystemClock.elapsedRealtime()
            // Gaps are per-leg: two legs streaming into one counter would report
            // roughly half the true inter-arrival time and flatter it entirely.
            val previous = lastArrivalPerLeg.put(legCode, now)
            if (previous == null) return
            arrivalCount++
            arrivalBytes += bytes
            val gap = now - previous
            arrivalSumMs += gap
            if (gap > arrivalMaxGapMs) arrivalMaxGapMs = gap
            if (arrivalCount % ARRIVAL_LOG_EVERY != 0L) return
            val line = "n=$arrivalCount meanGap=${arrivalSumMs / arrivalCount}ms " +
                "maxGap=${arrivalMaxGapMs}ms avgChunk=${arrivalBytes / arrivalCount}B"
            arrivalMaxGapMs = 0L
            line
        }
        val snapshot = playbackEngine.snapshot()
        Log.i(
            TAG,
            "audio arrival: $log | buffered=${snapshot.bufferedMs}ms " +
                "target=${snapshot.targetLatencyMs}ms underruns=${snapshot.underruns} " +
                "dropped=${snapshot.droppedChunks}",
        )
    }

    // Half-duplex gate: mute the mic while the translator is speaking. Always on
    // for SPEAKER (the translated audio would otherwise be re-ingested and
    // re-translated by the other leg → feedback loop); opt-in on earbud routes
    // via externalOnly.
    private fun shouldGateMic(): Boolean {
        if (currentPolicy?.externalOnly == true) return true
        return deviceMonitor.route.value == AudioRoute.SPEAKER
    }

    private suspend fun waitForSocketOpen(socket: LiveSocketClient): Boolean {
        val result = withTimeoutOrNull(5_000) {
            socket.state.first { it == LiveSocketState.OPEN }
        }
        return result != null
    }

    companion object {
        private const val TAG = "SessionCoord"
        private const val MAX_RECONNECT_ATTEMPTS = 4

        /**
         * How long an owning leg may go quiet before the other leg may take the
         * stream. Longer than any within-utterance pause the model produces, short
         * enough that a reply in the other direction is never left waiting.
         */
        private const val LEG_HANDOVER_IDLE_MS = 700L

        /** Chunks between arrival-jitter log lines. ~5 s at a 100 ms cadence. */
        private const val ARRIVAL_LOG_EVERY = 50L

        /** Output PCM peak below this (model emits zero-PCM silence; peak≈1) is treated as silence. */
        private const val SILENCE_PEAK = 48

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
