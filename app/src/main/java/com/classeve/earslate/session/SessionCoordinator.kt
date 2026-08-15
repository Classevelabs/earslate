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
import com.classeve.earslate.live.ProviderMessage
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    /**
     * True once this attempt has decided to fail and has begun closing its own
     * sockets.
     *
     * Without it the runtime could not tell a socket that DIED from a socket it
     * had just closed on purpose, and every deliberate failure laundered itself
     * into a network fault. `return@coroutineScope` does not end the scope —
     * it waits for the children — so the close() in a failure branch drove each
     * socket to CLOSED, the still-running death watcher fired, and
     * `wasSocketDeath` was set on sockets the app itself had shut.
     *
     * The user paid for that twice. Four more full sessions were retried
     * against a failure that had already been diagnosed as terminal, each one
     * minting a fresh credential on their own API key; and the accurate message
     * ("Could not open the microphone.", "The translation provider did not
     * become ready.") was then overwritten by "Lost connection and could not
     * reconnect" — sending someone to debug their network for a microphone
     * conflict.
     */
    @Volatile private var deliberateTeardown: Boolean = false
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

    private class Leg(
        val targetCode: String,
        val bootstrap: SessionBootstrap,
        val protocol: TranslationLiveProtocol,
        val socket: LiveSocketClient,
        val setupReady: CompletableDeferred<Unit> = CompletableDeferred(),
    ) {
        var pumpJob: Job? = null
        var watchJob: Job? = null

        /**
         * Set when this leg is being REPLACED rather than lost. Its socket close
         * must not read as a network death, for the same reason
         * [deliberateTeardown] exists — otherwise every language change would
         * trip a full session reconnect.
         */
        @Volatile var retired: Boolean = false
    }

    /**
     * The legs currently receiving microphone audio. Replaced wholesale rather
     * than mutated, so the capture callback — which runs on the audio thread and
     * must not take a lock — always reads one consistent list.
     */
    @Volatile private var activeLegs: List<Leg> = emptyList()

    /** The leg targeting my own language. Never retargeted; it is the one that listens. */
    @Volatile private var primaryCode: String? = null

    /** Null when the user has pinned both languages by hand. */
    @Volatile private var heardLanguages: HeardLanguageTracker? = null

    /**
     * Set for the life of a session. Nulled in teardown so a transcript still in
     * flight cannot start a retarget against a session that has ended.
     */
    @Volatile private var onHeardLanguageChange: ((String) -> Unit)? = null

    private val retargetLock = Mutex()

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
            onHeardLanguageChange = null
            activeLegs = emptyList()
            stateStore.setHeardLanguage(null)
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
                    stateStore.setHeardLanguage(null)
                    releaseLeg()
                }
            }
        }
    }

    /**
     * @return true if a live session was actually cancelled.
     *
     * The caller needs this from the coordinator rather than from
     * [RuntimeStateStore], which is a mirror of the session and not the session.
     * TranslatorService decided whether to stopSelf() by reading
     * `stateStore.state.value.isActive`, and the two disagree in both
     * directions: a coordinator that is still tearing down while the store
     * already reads IDLE made STOP take the "nothing running" branch and kill
     * the foreground service out from under a live capture — and this
     * coordinator is a process-wide singleton with its own scope, so it would
     * have carried on recording without one.
     */
    fun stop(): Boolean {
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
            return false
        }
        job.cancel()
        return true
    }

    private suspend fun reconnectLoop(policy: TranslatorPolicy) {
        while (true) {
            sessionStartElapsed = android.os.SystemClock.elapsedRealtime()
            firstAudioSeen = false
            wasSocketDeath = false
            deliberateTeardown = false

            try {
                runSession(policy)
            } catch (_: CancellationException) {
                // either user stop or socket-death trip — both catch here
            }

            // Same predicate the teardown used to decide whether to announce
            // IDLE, so the two can never disagree about what happens next.
            // A user stop must never be mistaken for a socket death and retried.
            if (!willReconnect()) {
                // Told apart here rather than by three separate returns: the
                // only case that owes the user a message is a real socket death
                // that ran out of attempts. A user stop and a non-socket exit
                // have both already said whatever needed saying.
                if (wasSocketDeath && !stopRequested) {
                    Log.w(TAG, "reconnect budget exhausted after ${reconnectManager.attemptNumber} attempts")
                    stateStore.setError(
                        RuntimeError(
                            kind = RuntimeError.Kind.CONNECT_FAILED,
                            message = "Lost connection and could not reconnect. Tap start to try again.",
                        ),
                    )
                }
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

        val myCode = LiveSessionConfigFactory.translateCodeFor(policy.myLanguage.bcp47)
        val theirCode = LiveSessionConfigFactory.translateCodeFor(policy.theirLanguage.bcp47)
        primaryCode = myCode
        // Manual pins both directions; automatic follows the room. Built per
        // session rather than per app so a language learned in one conversation
        // is not carried into the next one.
        heardLanguages = if (policy.manualLanguages) {
            null
        } else {
            HeardLanguageTracker(policy.myLanguage.bcp47, policy.theirLanguage.bcp47)
        }
        stateStore.setHeardLanguage(null)

        stateStore.set(RuntimeState.BOOTSTRAPPING)
        val legs = try {
            val primary = openLeg(policy.provider, myCode, policy.captionsEnabled)
            val opened = mutableListOf(primary)
            if (
                canHoldTwoDirections(primary.bootstrap.provider) &&
                !theirCode.equals(myCode, ignoreCase = true)
            ) {
                opened += openLeg(primary.bootstrap.provider, theirCode, policy.captionsEnabled)
            }
            opened
        } catch (cancelled: CancellationException) {
            // A stop is not a failure. This catch was `Throwable` alone, and
            // bootstrap is two suspending network calls, so stopping while the
            // pill still read BOOTSTRAPPING landed here and showed the user a
            // red banner — often literally "StandaloneCoroutine was cancelled" —
            // for their own deliberate stop. lastError is sticky, so it then sat
            // there until dismissed. Rethrowing keeps cancellation cancellation.
            throw cancelled
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
        setActiveLegs(legs)
        Log.i(
            TAG,
            "session legs: ${legs.joinToString { "${it.targetCode}:${it.bootstrap.provider.wireValue}" }}",
        )

        stateStore.set(RuntimeState.CONNECTING)
        val failure = bringUp(outer, legs, policy.captionsEnabled)
        if (failure != null) {
            abortSession(outer, legs, failure)
            return@coroutineScope
        }

        // Automatic mode: when the listening leg reports hearing a language that
        // is not mine, point the outbound direction at it.
        onHeardLanguageChange = { code ->
            outer.launch { retargetOutbound(outer, policy, code) }
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
                            // activeLegs, not the list built at startup: the
                            // outbound leg is replaced whenever the other person
                            // turns out to be speaking something else, and a
                            // captured `legs` would keep feeding the socket that
                            // replacement already closed.
                            for (leg in activeLegs) {
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
                // Through abortSession so the finally's socket closes are not
                // mistaken for a network death. This is the case the laundering
                // hurt most: the mic is typically held by a call or another
                // recorder, and the user was told to check their connection.
                abortSession(
                    outer,
                    activeLegs,
                    RuntimeError(
                        kind = RuntimeError.Kind.UNKNOWN,
                        message = "Could not open the microphone. Another app may be using it.",
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
            onHeardLanguageChange = null
            runCatching { captureEngine.stop() }
            runCatching { playbackEngine.stop(graceful = true) }
            withContext(NonCancellable) {
                // Whatever is live NOW. A leg replaced mid-session is already
                // closed; the one that replaced it is not in `legs`.
                val open = activeLegs
                for (leg in open) {
                    leg.protocol.gracefulCloseFrame()?.let { leg.socket.sendText(it) }
                }
                if (open.any { it.protocol.gracefulCloseFrame() != null }) delay(250)
                for (leg in open) runCatching { leg.socket.close() }
            }
            runCatching { audioManager.abandonAudioFocusRequest(audioFocusRequest) }
            // IDLE only when nothing more is coming.
            //
            // This was unconditional, and IDLE is not a neutral "attempt over"
            // marker — it is the terminal resting state, and TranslatorService
            // treats it as the signal to demote the foreground service and
            // stopSelf(). So every socket death announced IDLE on its way to
            // RECONNECTING, killing the microphone-typed foreground service that
            // the reconnect it was about to perform depends on. RuntimeState's
            // own KDoc says failure branches go through RECONNECTING "without
            // tearing down the service"; the teardown path did not honour it.
            //
            // The consequence was that any network blip ended the session
            // instead of recovering from it, which is the one moment reconnect
            // exists for.
            stateStore.set(
                if (willReconnect()) RuntimeState.RECONNECTING else RuntimeState.IDLE,
            )
        }
    }

    /**
     * Whether [reconnectLoop] will retry once the current attempt has unwound.
     *
     * Deliberately ONE predicate, read both by the teardown above and by the
     * loop itself. Written out twice these would eventually disagree, and the
     * disagreement is invisible: the state machine would simply announce the
     * wrong thing on a path nobody exercises by hand.
     */
    /**
     * End the current attempt with a diagnosis, and make sure it stays the
     * diagnosis.
     *
     * Marking the teardown deliberate BEFORE closing anything is the whole
     * point: the close() calls below are what used to trip the death watchers
     * and turn this terminal failure into four retries and a wrong message.
     * Every failure branch in [runSession] goes through here so none of them
     * can forget the flag — five hand-written copies of this sequence is how
     * one of them ended up with no message at all.
     */
    private fun abortSession(session: CoroutineScope, legs: List<Leg>, error: RuntimeError) {
        deliberateTeardown = true
        for (leg in legs) runCatching { leg.socket.close() }
        stateStore.setError(error)
        stateStore.set(RuntimeState.IDLE)
        // Cancelling the scope is not tidiness — it is the only thing that ends
        // it.
        //
        // The per-leg frame pumps are children of this scope, and each one is a
        // `collect` on a SharedFlow, which never completes. `return@coroutineScope`
        // does not end a scope; it waits for the children. So the ONLY thing
        // that ever terminated these paths was the death watcher's
        // `outer.cancel(...)` — and teaching the watcher to ignore a deliberate
        // close removed exactly that, without putting anything in its place.
        //
        // The result was worse than the bug it fixed: runSession never returned,
        // reconnectLoop never returned, lifecycleJob was never nulled, and every
        // later start() hit "start ignored; already active" for the life of the
        // process. The banner read correctly and the service stopped itself, so
        // nothing looked wrong — the app just never translated again until it
        // was force-stopped. Reachable on a first tap: a captive-portal network
        // times out at 5s here against OkHttp's 10s connect timeout, and a
        // microphone held by a phone call does it too.
        session.cancel(CancellationException("session aborted: ${error.message}"))
    }

    /**
     * Mint a credential and build the leg around it. Nothing is connected yet.
     *
     * captionsEnabled travels with the credential, not only with the setup frame:
     * the Gemini token LOCKS the session config, so minting for one config and
     * asking for another is a contradiction the client cannot win.
     */
    private suspend fun openLeg(
        provider: TranslationProvider,
        targetCode: String,
        captionsEnabled: Boolean,
    ): Leg {
        val bootstrap = bootstrapRepository.bootstrap(provider, targetCode, captionsEnabled)
        return Leg(
            targetCode = targetCode,
            bootstrap = bootstrap,
            protocol = TranslationLiveProtocols.forProvider(bootstrap.provider),
            socket = socketFactory(),
        )
    }

    /**
     * Gemini's `echoTargetLanguage=false` lets two sockets share one microphone
     * safely — each stays silent unless the speech is going its way. OpenAI's
     * translation session has one output language and no echo suppression, so a
     * second socket would just talk over the first.
     */
    private fun canHoldTwoDirections(provider: TranslationProvider): Boolean =
        provider == TranslationProvider.GEMINI

    /**
     * Connect, configure and confirm a set of legs. Phase by phase across the
     * whole set rather than leg by leg, so two directions come up in parallel
     * instead of one waiting on the other's round trips.
     *
     * @return null on success, or the error that stopped it. The caller decides
     *   whether that ends the session — it does at startup, and does not when a
     *   language change fails and the existing legs are still working.
     */
    private suspend fun bringUp(
        scope: CoroutineScope,
        legs: List<Leg>,
        captionsEnabled: Boolean,
    ): RuntimeError? {
        // Collectors first. OpenAI can emit its first session event immediately
        // after the upgrade, and SharedFlow has no replay.
        for (leg in legs) {
            leg.pumpJob = scope.launch {
                pumpFrames(leg) { message ->
                    abortSession(scope, activeLegs, RuntimeError(RuntimeError.Kind.PROVIDER_ERROR, message))
                }
            }
        }
        for (leg in legs) {
            try {
                leg.socket.connect(leg.bootstrap.webSocketUrl, leg.protocol.headers(leg.bootstrap))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                Log.e(TAG, "socket connect failed: ${t.message}")
                return RuntimeError(
                    kind = RuntimeError.Kind.CONNECT_FAILED,
                    message = "Could not reach the selected translation provider.",
                )
            }
        }
        for (leg in legs) {
            leg.watchJob = scope.launch {
                val death = leg.socket.state.first {
                    it == LiveSocketState.CLOSED || it == LiveSocketState.FAILED
                }
                // A socket we closed ourselves is not a death — whether because
                // the session is ending or because this leg was replaced by one
                // aimed at a different language. See [deliberateTeardown]; this
                // check is what stops a diagnosed failure being retried four
                // times and then reported as a network fault.
                if (deliberateTeardown || leg.retired) {
                    Log.i(TAG, "leg ${leg.targetCode} socket $death — expected")
                    return@launch
                }
                Log.i(TAG, "leg ${leg.targetCode} socket $death — tripping reconnect")
                wasSocketDeath = true
                scope.cancel(CancellationException("socket $death"))
            }
        }
        for (leg in legs) {
            if (!waitForSocketOpen(leg.socket)) {
                Log.w(TAG, "leg ${leg.targetCode} did not reach OPEN in 5s")
                // A slow or captive-portal network used to show the pill going
                // CONNECTING and then simply stopping, with an empty banner. The
                // timeout itself is the actionable fact.
                return RuntimeError(
                    kind = RuntimeError.Kind.CONNECT_FAILED,
                    message = "The translation provider took too long to accept the connection.",
                )
            }
        }
        for (leg in legs) {
            val sent = leg.socket.sendText(
                leg.protocol.setupFrame(
                    bootstrap = leg.bootstrap,
                    targetLanguageCode = leg.targetCode,
                    captionsEnabled = captionsEnabled,
                ),
            )
            Log.i(TAG, "setup sent=$sent target=${leg.targetCode}")
            if (!sent) {
                return RuntimeError(
                    RuntimeError.Kind.CONNECT_FAILED,
                    "Could not configure the translation session.",
                )
            }
        }
        // The microphone does not open until every provider has acknowledged its
        // configuration, or the first words are dropped on the floor.
        for (leg in legs) {
            val ready = withTimeoutOrNull(SETUP_TIMEOUT_MS) { leg.setupReady.await(); true } ?: false
            if (!ready) {
                return RuntimeError(
                    RuntimeError.Kind.CONNECT_FAILED,
                    "The translation provider did not become ready.",
                )
            }
        }
        return null
    }

    private fun setActiveLegs(legs: List<Leg>) {
        activeLegs = legs.toList()
    }

    /**
     * Aim the outbound direction at [newCode] — the language the other person
     * has actually been heard speaking.
     *
     * The Gemini credential locks the target language into the session, so this
     * cannot be a config update; it is a new socket. The new leg is brought all
     * the way up BEFORE the old one is touched, so a failure here costs nothing
     * — the conversation carries on in the language it was already using — and
     * there is no window where our own speech has nowhere to go.
     */
    private suspend fun retargetOutbound(
        scope: CoroutineScope,
        policy: TranslatorPolicy,
        newCode: String,
    ) {
        val target = LiveSessionConfigFactory.translateCodeFor(newCode)
        retargetLock.withLock {
            val my = primaryCode ?: return
            if (target.equals(my, ignoreCase = true)) return
            val current = activeLegs
            val primary = current.firstOrNull { it.targetCode.equals(my, ignoreCase = true) } ?: return
            if (!canHoldTwoDirections(primary.bootstrap.provider)) return
            val outbound = current.firstOrNull { it !== primary }
            if (outbound != null && outbound.targetCode.equals(target, ignoreCase = true)) return

            Log.i(TAG, "retargeting outbound ${outbound?.targetCode ?: "none"} → $target")
            val replacement = try {
                openLeg(primary.bootstrap.provider, target, policy.captionsEnabled)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                Log.w(TAG, "retarget bootstrap failed: ${t.javaClass.simpleName}")
                return
            }
            val failure = bringUp(scope, listOf(replacement), policy.captionsEnabled)
            if (failure != null) {
                Log.w(TAG, "retarget failed: ${failure.message}")
                retire(replacement)
                return
            }
            setActiveLegs(listOf(primary, replacement))
            outbound?.let {
                retire(it)
                releaseLeg(it.targetCode)
            }
        }
    }

    /** Close a leg we are replacing, without it reading as a socket death. */
    private fun retire(leg: Leg) {
        leg.retired = true
        leg.watchJob?.cancel()
        leg.pumpJob?.cancel()
        runCatching { leg.socket.close() }
    }

    private fun willReconnect(): Boolean =
        !stopRequested &&
            wasSocketDeath &&
            reconnectManager.attemptNumber < MAX_RECONNECT_ATTEMPTS

    /**
     * @param onProviderError ends the whole session with the provider's verdict.
     *   This used to be a bare `leg.socket.close(1011, "provider_error")`, which
     *   is a close the death watcher cannot tell from a network failure — so a
     *   quota refusal was retried four times (re-minting a credential on the
     *   user's key each attempt, twice over for a two-leg Gemini session) and
     *   then reported as "Lost connection and could not reconnect". The one
     *   path that finally carried the provider's real message was also the one
     *   path that threw it away again.
     */
    private suspend fun pumpFrames(leg: Leg, onProviderError: (String) -> Unit) {
        leg.socket.frames.collect { raw ->
            val parsed = leg.protocol.parse(raw)
            parsed.forEach { event ->
                if (event is LiveEvent.SetupComplete) leg.setupReady.complete(Unit)
                runCatching { dispatch(leg.targetCode, event) }
                    .onFailure { Log.e(TAG, "dispatch failed for ${event.javaClass.simpleName}: ${it.message}", it) }
                _events.tryEmit(event)
                if (event is LiveEvent.Error) {
                    // Terminal by nature: a bad key, an exhausted quota or a
                    // model the account cannot reach will not resolve itself in
                    // four retries.
                    onProviderError(
                        ProviderMessage.sanitize(event.message)
                            ?: "The translation provider ended the session.",
                    )
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
                // Ending a turn is an OWNERSHIP decision, exactly like the audio
                // and caption paths above, and these three lines were the only
                // ones that ignored it.
                //
                // Both legs hear the same microphone, so the leg that stayed
                // silent finishes its turn too. Its turnComplete was disarming
                // the shared jitter buffer while the OTHER leg was mid-sentence
                // — the buffer treats an expected quiet as a reason to stop
                // draining, so playback stalled until the cushion refilled —
                // and it committed the caption line underneath the leg that was
                // still writing it, then flicked PLAYING back to LISTENING.
                //
                // Skipped only when a DIFFERENT leg holds the stream. With no
                // owner nothing is speaking, so there is nobody to cut off and
                // the buffer should still learn that the quiet was expected.
                val owner = synchronized(legLock) { speakingLeg }
                releaseLeg(legCode)
                if (owner != null && owner != legCode) return
                // Tell the buffer this quiet is expected, so it does not pay for
                // latency it does not need.
                playbackEngine.notifyTurnEnd()
                captionsStore.commitLine()
                if (stateStore.state.value == RuntimeState.PLAYING) {
                    stateStore.set(RuntimeState.LISTENING)
                }
            }
            is LiveEvent.SourceTranscript -> {
                // Only the listening leg. Both legs transcribe the same
                // microphone, so counting them both would feed every utterance
                // to the tracker twice and make its two-in-a-row confirmation
                // rule pass on a single sentence.
                if (!legCode.equals(primaryCode, ignoreCase = true)) return
                val tracker = heardLanguages ?: return
                val changed = tracker.observe(event.text) ?: return
                Log.i(TAG, "heard a new language: $changed")
                stateStore.setHeardLanguage(
                    SupportedLanguages.firstOrNull { it.bcp47 == changed },
                )
                onHeardLanguageChange?.invoke(changed)
            }
            is LiveEvent.ResumptionHandle -> Unit // resumption disabled for the translate model
            is LiveEvent.GoAway -> {
                Log.i(TAG, "server GoAway — will reconnect")
                stateStore.set(RuntimeState.RECONNECTING)
            }
            is LiveEvent.SocketClosed -> Unit
            is LiveEvent.Error -> {
                Log.w(TAG, "live error: ${event.message}")
                // The provider's own verdict is the most useful sentence
                // available — "You exceeded your current quota" tells someone
                // what to do; "Lost connection" sends them to reboot a router
                // that is working. It used to go to Log.w and nowhere else, so
                // the socket was closed, the retries ran, and the user was
                // finally told the network had dropped.
                //
                // It is sanitised rather than trusted: this is the one string in
                // the system we did not write, and providers do echo request
                // parameters — including the key — back inside it. See
                // [ProviderMessage]. If nothing survives redaction we say
                // something true in our own words rather than show a blank.
                // The error itself is raised by pumpFrames' onProviderError,
                // which ends the session with it rather than letting the retry
                // loop overwrite it. Setting it here as well would be a second
                // copy of the same decision in a second place.
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

        /** How long a provider gets to acknowledge a setup frame. */
        private const val SETUP_TIMEOUT_MS = 7_000L

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
