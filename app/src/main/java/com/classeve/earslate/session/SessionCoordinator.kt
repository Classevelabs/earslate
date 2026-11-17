package com.classeve.earslate.session

import android.os.SystemClock
import android.util.Log
import com.classeve.earslate.audio.AudioCaptureEngine
import com.classeve.earslate.audio.AudioPlaybackEngine
import com.classeve.earslate.audio.AudioRoute
import com.classeve.earslate.audio.VoiceActivity
import com.classeve.earslate.bootstrap.BootstrapException
import com.classeve.earslate.bootstrap.SessionCredential
import com.classeve.earslate.bootstrap.SessionCredentialSource
import com.classeve.earslate.live.LinkFailure
import com.classeve.earslate.live.LiveEvent
import com.classeve.earslate.live.LiveSocketClient
import com.classeve.earslate.live.LiveSocketState
import com.classeve.earslate.live.ProviderLink
import com.classeve.earslate.live.TranslationLiveProtocol
import com.classeve.earslate.live.TranslationLiveProtocols
import com.classeve.earslate.live.failure
import com.classeve.earslate.session.HeardLanguageTracker.Companion.sameLanguage
import com.classeve.earslate.ui.captions.CaptionSide
import com.classeve.earslate.ui.captions.CaptionsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** The platform's audio focus, held for the length of a session. */
interface AudioFocus {
    fun acquire()
    fun release()
}

/**
 * Runs a live conversation: one translate session per direction, both fed by
 * one microphone, each played on a lane of its own.
 *
 * A provider ends every connection sooner or later — Gemini a little under ten
 * minutes in, with fifty seconds' warning. When it says so, the session opens
 * the replacement first and moves the microphone across at a pause, so the
 * conversation does not notice. A connection that is simply lost is retried,
 * a bounded number of times, with the same credential.
 */
class SessionCoordinator(
    private val credentials: SessionCredentialSource,
    private val socketFactory: () -> LiveSocketClient,
    private val captureEngine: AudioCaptureEngine,
    private val playbackEngine: AudioPlaybackEngine,
    private val captionsStore: CaptionsStore,
    private val stateStore: RuntimeStateStore,
    private val audioFocus: AudioFocus,
    private val route: StateFlow<AudioRoute>,
    /** Emits when the phone moves to another network; sockets on the old one are dead. */
    private val networkChanged: Flow<Unit> = emptyFlow(),
    private val now: () -> Long = SystemClock::elapsedRealtime,
    private val wallClock: () -> Long = System::currentTimeMillis,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val reconnect = ReconnectManager()
    private val legIds = AtomicInteger()

    @Volatile private var lifecycleJob: Job? = null
    @Volatile private var stopRequested = false
    @Volatile private var live: LiveSession? = null

    // What the session is asked to be. Kept here, not in the attempt that is
    // running, so a language changed mid-session is still the language after
    // a reconnect.
    @Volatile private var policy: TranslatorPolicy? = null

    // Kept across reconnects, or after a dropped connection what I say would
    // go untranslated until they had spoken again.
    @Volatile private var heardTheirs: String? = null

    @Volatile private var focusHeld = false

    private sealed interface Outcome {
        /** Cannot work as asked; another attempt would fail the same way. */
        class Failed(val error: RuntimeError) : Outcome

        /** Was working and stopped. Worth another attempt. */
        class Lost(val why: LinkFailure, val microphone: Boolean = false) : Outcome
    }

    /** @return the session's whole life: complete when nothing of it is running any more. */
    fun start(policy: TranslatorPolicy): Job = synchronized(this) {
        lifecycleJob?.let {
            Log.i(TAG, "start ignored; already active")
            return it
        }
        this.policy = policy
        captionsStore.clear()
        stateStore.clearError()
        stateStore.setNotice(null)
        reconnect.reset()
        stopRequested = false
        heardTheirs = null
        stateStore.setHeardLanguage(null)
        stateStore.setTheirLanguagePinned(false)

        scope.launch {
            try {
                playbackEngine.start()
                reconnectLoop()
            } catch (_: CancellationException) {
                // stop()
            } catch (t: Throwable) {
                Log.e(TAG, "session crashed", t)
                stateStore.setError(
                    RuntimeError(
                        RuntimeError.Kind.UNKNOWN,
                        "The session stopped unexpectedly. Tap start to try again.",
                    ),
                )
            } finally {
                synchronized(this@SessionCoordinator) { lifecycleJob = null }
                playbackEngine.stop(graceful = true)
                if (focusHeld) {
                    focusHeld = false
                    audioFocus.release()
                }
                stateStore.setNotice(null)
                // Whatever path ended the job, nothing is running now, and
                // the screen must not go on showing a session.
                if (stateStore.state.value != RuntimeState.IDLE) stateStore.set(RuntimeState.IDLE)
                stateStore.setHeardLanguage(null)
            }
        }.also { lifecycleJob = it }
    }

    /** @return true if a live session was actually cancelled. */
    fun stop(): Boolean {
        stopRequested = true
        val job = synchronized(this) { lifecycleJob }
        if (job == null) {
            if (stateStore.state.value != RuntimeState.IDLE) stateStore.set(RuntimeState.IDLE)
            return false
        }
        job.cancel()
        return true
    }

    /**
     * Change a running session's languages without restarting it. A no-op when
     * nothing is running.
     *
     * @param their pins the direction: a correction the room could overrule at
     *   once would not be a correction.
     * @param follow true to go back to following what is heard.
     */
    fun setLanguages(
        my: TargetLanguage? = null,
        their: TargetLanguage? = null,
        follow: Boolean? = null,
    ) {
        synchronized(this) {
            var next = policy ?: return
            if (lifecycleJob == null) return
            if (my != null) next = next.copy(myLanguage = my)
            if (follow == true) next = next.copy(otherLanguage = null)
            if (their != null) next = next.copy(otherLanguage = their)
            policy = next
        }
        live?.sync()
    }

    private suspend fun reconnectLoop() {
        var retrying = false
        while (true) {
            val outcome = coroutineScope {
                val attempt = coroutineContext.job
                val session = LiveSession(policy ?: return@coroutineScope null, this, retrying)
                try {
                    session.run()
                } finally {
                    live = null
                    withContext(NonCancellable) {
                        // Its helpers first: one of them may be about to put a
                        // new connection to use, and one adopted after the
                        // close would never be closed.
                        attempt.children.forEach { it.cancel() }
                        attempt.children.forEach { it.join() }
                        session.close()
                    }
                }
            } ?: return
            retrying = true
            when (outcome) {
                is Outcome.Failed -> {
                    stateStore.setError(outcome.error)
                    return
                }
                is Outcome.Lost -> {
                    if (stopRequested) return
                    if (reconnect.attemptNumber >= MAX_RECONNECT_ATTEMPTS) {
                        Log.w(TAG, "gave up after ${reconnect.attemptNumber} attempts")
                        // What the provider said, when it said anything, is
                        // the reason; "lost connection" is only for silence.
                        stateStore.setError(
                            when {
                                outcome.why.providerSpoke ->
                                    RuntimeError(RuntimeError.Kind.PROVIDER_ERROR, outcome.why.message.orEmpty())
                                outcome.microphone ->
                                    RuntimeError(RuntimeError.Kind.UNKNOWN, outcome.why.message.orEmpty())
                                else -> RuntimeError(
                                    RuntimeError.Kind.CONNECT_FAILED,
                                    "Lost connection and could not reconnect. Tap start to try again.",
                                )
                            },
                        )
                        return
                    }
                    stateStore.set(RuntimeState.RECONNECTING)
                    delay(reconnect.nextDelayMs())
                }
            }
        }
    }

    private inner class Leg(val role: LegRole, val link: ProviderLink) {
        val id = legIds.incrementAndGet()

        /** Being replaced or closed on purpose; its socket ending is not a failure. */
        @Volatile var retired = false
        @Volatile var replacing = false

        /** True once the session is using it; one that never is must be closed by whoever opened it. */
        @Volatile var adopted = false

        /** When the provider will end it, on this phone's own clock. */
        @Volatile var expiresAtMs = NEVER

        val providerClosed = CompletableDeferred<Unit>()

        val alive: Boolean get() = link.socket.state.value == LiveSocketState.OPEN
    }

    /** One attempt at a session: everything it owns ends with it. */
    private inner class LiveSession(
        private val asked: TranslatorPolicy,
        private val sessionScope: CoroutineScope,
        /** True when this attempt replaces a session that was working. */
        private val retrying: Boolean,
    ) : ConversationSink {

        private lateinit var protocol: TranslationLiveProtocol
        private lateinit var engine: ConversationEngine
        private lateinit var voice: VoiceActivity
        private lateinit var silentFrame: String

        private val engineLock = Any()
        private val legs = ConcurrentHashMap<Int, Leg>()
        private val retargeting = Mutex()
        private val floor = FloorControl()
        private val ending = CompletableDeferred<Outcome>()

        // The legs being sent the microphone. Replaced whole, never mutated,
        // because the capture thread reads it without a lock.
        @Volatile private var micLegs: List<Leg> = emptyList()

        // Replaced legs still finishing their last sentence.
        private val draining = CopyOnWriteArrayList<Leg>()

        @Volatile private var myLanguage = asked.myLanguage
        @Volatile private var pinnedTheirs: TargetLanguage? = asked.otherLanguage
        @Volatile private var consecutive = false
        @Volatile private var micClosed = false
        @Volatile private var micTaken = false
        @Volatile private var lastMicSpeechAtMs = 0L
        @Volatile private var engineSpeaking = false
        @Volatile private var lastNotice: String? = null
        @Volatile private var readyAtMs = 0L
        @Volatile private var lastOpenFailure: String? = null
        @Volatile private var syncing: Job? = null

        private val providerName get() = protocol.provider.displayName

        suspend fun run(): Outcome {
            stateStore.set(RuntimeState.BOOTSTRAPPING)
            var credential = try {
                credentials.credential(asked.provider)
            } catch (failure: BootstrapException) {
                return failed(failure)
            }
            protocol = TranslationLiveProtocols.forProvider(credential.provider)
            val myWire = protocol.wireLanguage(myLanguage.bcp47) ?: return failed(
                RuntimeError.Kind.BOOTSTRAP_FAILED,
                "$providerName can't translate into ${myLanguage.displayName}. " +
                    "Choose another language, or another provider, in Settings.",
            )

            // No language is assumed for them. Until one has been heard, or
            // set by hand, what I say has nowhere to go and is left alone.
            val pinned = pinnedTheirs
            val theirs = pinned?.bcp47 ?: heardTheirs
            engine = ConversationEngine(myLanguage.bcp47, theirs, followsTheirLanguage = pinned == null, sink = this)
            stateStore.setTheirLanguagePinned(pinned != null)
            stateStore.setHeardLanguage(pinned ?: heardTheirs?.let(TargetLanguage::forCode))

            stateStore.set(RuntimeState.CONNECTING)
            val theirWire = theirs?.takeUnless { sameLanguage(it, myLanguage.bcp47) }?.let(protocol::wireLanguage)
            val opened = try {
                openBoth(credential, myWire, theirWire)
            } catch (first: LinkFailure) {
                // Only a refusal can mean the credential went stale. A network
                // that is down is not cured by asking for a new one.
                if (!first.providerSpoke) return failed(first)
                credentials.discard(credential)
                try {
                    credential = credentials.credential(asked.provider)
                    openBoth(credential, myWire, theirWire)
                } catch (failure: BootstrapException) {
                    return failed(failure)
                } catch (failure: LinkFailure) {
                    return failed(failure)
                }
            }
            opened.forEach(::adopt)
            micLegs = opened

            if (!focusHeld) {
                focusHeld = true
                audioFocus.acquire()
            }
            applyRoute(route.value.sharedWithMicrophone)

            voice = VoiceActivity(protocol.inputSampleRateHz)
            silentFrame = protocol.audioFrame(
                ByteArray(protocol.inputSampleRateHz * protocol.inputFrameMs / 1000 * 2),
            )
            val listening = captureEngine.start(
                sampleRateHz = protocol.inputSampleRateHz,
                frameMs = protocol.inputFrameMs,
                onFrame = ::onMicFrame,
                onError = {
                    ending.complete(
                        Outcome.Lost(
                            LinkFailure("The microphone stopped working. Another app may be using it.", providerSpoke = false),
                            microphone = true,
                        ),
                    )
                },
                onTaken = { micTaken = it },
            )
            if (!listening) {
                return failed(RuntimeError.Kind.UNKNOWN, "Could not open the microphone. Another app may be using it.")
            }

            readyAtMs = now()
            stateStore.set(RuntimeState.LISTENING)
            // Only now can a change of language reach this attempt; one made
            // while it was connecting is caught up with here.
            live = this
            sync()
            // Reconnect now, rather than when a ping finally goes unanswered.
            sessionScope.launch {
                networkChanged.collect {
                    ending.complete(Outcome.Lost(LinkFailure("The network changed.", providerSpoke = false)))
                }
            }
            sessionScope.launch { tick() }
            return ending.await()
        }

        private fun failed(kind: RuntimeError.Kind, message: String?): Outcome =
            Outcome.Failed(RuntimeError(kind, message ?: "Could not start the translation service."))

        // A network that is still coming back is a reason to try again, not to
        // give up; a refusal from the provider is the same on every attempt.
        private fun failed(failure: LinkFailure): Outcome = when {
            failure.providerSpoke -> failed(RuntimeError.Kind.PROVIDER_ERROR, failure.message)
            retrying -> Outcome.Lost(failure)
            else -> failed(RuntimeError.Kind.CONNECT_FAILED, failure.message)
        }

        private fun failed(failure: BootstrapException): Outcome =
            if (retrying && failure.transient) {
                Outcome.Lost(LinkFailure(failure.message.orEmpty(), providerSpoke = false))
            } else {
                failed(RuntimeError.Kind.BOOTSTRAP_FAILED, failure.message)
            }

        fun close() {
            captureEngine.stop()
            // A sentence half shown when the connection went is finished off,
            // not left looking as if it were still being spoken.
            if (::engine.isInitialized) synchronized(engineLock) { legs.keys.forEach(engine::legClosed) }
            for (leg in legs.values) {
                leg.retired = true
                leg.link.close()
                // The lane outlives the leg: what it holds is still said.
                playbackEngine.retire(leg.id)
            }
            // Only a session that stayed up earns a fresh, immediate retry.
            reconnect.noteSessionEnded(if (readyAtMs == 0L) 0L else now() - readyAtMs)
        }

        // ── legs ────────────────────────────────────────────────────────

        /** Both directions at once, so neither waits on the other's round trips. */
        private suspend fun openBoth(credential: SessionCredential, myWire: String, theirWire: String?): List<Leg> {
            val opened = CopyOnWriteArrayList<Leg>()
            try {
                return coroutineScope {
                    val inbound = async { open(LegRole.INBOUND, myWire, credential).also { opened += it } }
                    val outbound = theirWire?.let { async { open(LegRole.OUTBOUND, it, credential).also { opened += it } } }
                    listOfNotNull(inbound.await(), outbound?.await())
                }
            } catch (t: Throwable) {
                // One half failing must not leave the other half connected.
                for (leg in opened) leg.link.close()
                throw t
            }
        }

        private suspend fun open(role: LegRole, wire: String, credential: SessionCredential): Leg {
            val link = ProviderLink(credential, protocol, socketFactory())
            val early = try {
                link.open(wire)
            } catch (t: Throwable) {
                link.close()
                throw t
            }
            return Leg(role, link).also { leg -> early.forEach { handle(leg, it) } }
        }

        private suspend fun openOrNull(role: LegRole, wire: String): Leg? {
            var credential: SessionCredential? = null
            return try {
                credential = credentials.credential(asked.provider)
                open(role, wire, credential)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Exception) {
                Log.w(TAG, "could not open a ${role.name.lowercase()} leg: ${t.javaClass.simpleName}")
                // A refusal may mean the credential has gone stale; the next
                // attempt asks for a new one.
                if (t is LinkFailure && t.providerSpoke) credential?.let(credentials::discard)
                // Only failures written for the user are kept to show them.
                lastOpenFailure = t.message.takeIf { t is LinkFailure || t is BootstrapException }
                null
            }
        }

        private fun adopt(leg: Leg) {
            leg.adopted = true
            legs[leg.id] = leg
            synchronized(engineLock) { engine.legOpened(leg.id, leg.role) }
            sessionScope.launch { pump(leg) }
        }

        private suspend fun pump(leg: Leg) {
            for (frame in leg.link.socket.frames) {
                for (event in protocol.parse(frame)) handle(leg, event)
            }
            legs.remove(leg.id)
            draining.remove(leg)
            synchronized(engineLock) { engine.legClosed(leg.id) }
            playbackEngine.retire(leg.id)
            if (!leg.retired) {
                Log.i(TAG, "leg ${leg.id} ended: ${leg.link.socket.closure?.code}")
                ending.complete(Outcome.Lost(leg.link.socket.closure.failure(providerName, lastNotice)))
            }
        }

        private fun handle(leg: Leg, event: LiveEvent) {
            when (event) {
                is LiveEvent.AudioChunk, is LiveEvent.CaptionDelta, is LiveEvent.SourceTranscript ->
                    synchronized(engineLock) { engine.onEvent(leg.id, event, now()) }
                is LiveEvent.GoAway -> rollOver(leg, event.timeLeftMs ?: DEFAULT_NOTICE_MS)
                // The provider's clock, not this phone's: read through the
                // difference between them.
                is LiveEvent.SessionExpiry ->
                    leg.expiresAtMs = now() + event.epochSeconds * 1000 - (wallClock() + leg.link.credential.serverAheadMs)
                is LiveEvent.ProviderNotice -> {
                    lastNotice = event.message
                    Log.w(TAG, "provider notice on leg ${leg.id}")
                }
                LiveEvent.SessionClosed -> leg.providerClosed.complete(Unit)
                LiveEvent.SetupComplete -> Unit
            }
        }

        /**
         * The provider is about to end [old]. Open its replacement now, move
         * the microphone across at a pause, and let [old] finish its sentence.
         * Keeps trying, unhurried, for as long as [old] lives.
         */
        private fun rollOver(old: Leg, withinMs: Long) {
            if (old.retired || old.replacing) return
            old.replacing = true
            sessionScope.launch {
                val switchBy = now() + (withinMs - ROLLOVER_MARGIN_MS).coerceAtLeast(0)
                while (!old.retired) {
                    val fresh = openOrNull(old.role, old.link.targetWireLanguage)
                    if (fresh != null) {
                        try {
                            // At a pause, so no sentence is split between two sessions.
                            while (fresh.alive && now() < switchBy && now() - lastMicSpeechAtMs < SWITCH_QUIET_MS) {
                                delay(SWITCH_POLL_MS)
                            }
                            retargeting.withLock {
                                val current = old.link.targetWireLanguage
                                val aimed = fresh.link.targetWireLanguage.equals(current, ignoreCase = true) ||
                                    fresh.link.retarget(current)
                                if (!old.retired && aimed && fresh.alive) swap(old, fresh)
                            }
                        } finally {
                            // Opened and never put to use: the session ended
                            // first, or it did not last the wait.
                            if (!fresh.adopted) fresh.link.close()
                        }
                        if (fresh.adopted) return@launch
                    }
                    delay(RETRY_DELAY_MS)
                }
            }
        }

        private fun swap(old: Leg?, fresh: Leg) {
            adopt(fresh)
            micLegs = micLegs.filter { it !== old } + fresh
            old?.let(::retire)
        }

        /** Stop sending [leg] speech, let it finish what it is saying, then close it. */
        private fun retire(leg: Leg) {
            leg.retired = true
            micLegs = micLegs.filter { it !== leg }
            // Silence, not nothing: a session that simply stops being sent
            // audio drops the end of the sentence it was translating.
            draining += leg
            sessionScope.launch {
                try {
                    delay(DRAIN_MIN_MS)
                    val deadline = now() + DRAIN_MAX_MS
                    while (now() < deadline && synchronized(engineLock) { engine.isMidRun(leg.id) }) delay(DRAIN_POLL_MS)
                    draining.remove(leg)
                    // A provider with a goodbye is given the chance to answer
                    // it: closing first can cut off what it was still sending.
                    if (leg.link.sayGoodbye()) withTimeoutOrNull(GOODBYE_MS) { leg.providerClosed.await() }
                } finally {
                    draining.remove(leg)
                    leg.link.socket.close()
                }
            }
        }

        // ── languages ───────────────────────────────────────────────────

        /**
         * Brings this session to what is asked of it now — my language, their
         * language, fixed or followed — and keeps at it until it is there.
         */
        fun sync() {
            synchronized(this) {
                syncing?.cancel()
                syncing = sessionScope.launch {
                    var attempt = 0
                    while (true) {
                        val want = policy ?: return@launch
                        var told = false
                        val settled = settleMine(want.myLanguage) { told = true } && settleTheirs(want.otherLanguage) { told = true }
                        if (settled) {
                            if (!told) stateStore.setNotice(null)
                            return@launch
                        }
                        attempt++
                        if (attempt >= ATTEMPTS_BEFORE_SAYING) {
                            stateStore.setNotice(
                                "What you say is not being translated for them yet. " +
                                    (lastOpenFailure ?: "The connection for it could not be opened."),
                            )
                        }
                        delay(minOf(RETRY_DELAY_MS * attempt, MAX_RETRY_DELAY_MS))
                    }
                }
            }
        }

        /** @return false when it could not be done yet and is worth another try. */
        private suspend fun settleMine(want: TargetLanguage, told: () -> Unit): Boolean {
            if (want.bcp47 == myLanguage.bcp47) return true
            val wire = protocol.wireLanguage(want.bcp47)
            if (wire == null) {
                told()
                stateStore.setNotice(
                    "$providerName can't translate into ${want.displayName}, " +
                        "so it is still translating into ${myLanguage.displayName}.",
                )
                return true
            }
            return retargeting.withLock {
                val current = micLegs.firstOrNull { it.role == LegRole.INBOUND } ?: return@withLock false
                if (!current.link.targetWireLanguage.equals(wire, ignoreCase = true)) {
                    val moved = protocol.retargetsInPlace && current.alive && current.link.retarget(wire)
                    if (!moved) swap(current, openOrNull(LegRole.INBOUND, wire) ?: return@withLock false)
                }
                myLanguage = want
                synchronized(engineLock) { engine.reconfigure(want.bcp47, engine.theirLanguage) }
                true
            }
        }

        /** [fixed] null follows what is heard. */
        private suspend fun settleTheirs(fixed: TargetLanguage?, told: () -> Unit): Boolean {
            if (fixed?.bcp47 != pinnedTheirs?.bcp47) {
                pinnedTheirs = fixed
                stateStore.setTheirLanguagePinned(fixed != null)
                synchronized(engineLock) {
                    engine.followsTheirLanguage = fixed == null
                    if (fixed != null) engine.reconfigure(myLanguage.bcp47, fixed.bcp47)
                }
                if (fixed != null) {
                    heardTheirs = fixed.bcp47
                    stateStore.setHeardLanguage(fixed)
                }
            }
            val theirs = synchronized(engineLock) { engine.theirLanguage }
            return retargeting.withLock {
                val current = micLegs.firstOrNull { it.role == LegRole.OUTBOUND }
                // Nobody else has been heard yet, or both sides speak one
                // language: there is no second direction.
                if (theirs == null || sameLanguage(theirs, myLanguage.bcp47)) {
                    current?.let(::retire)
                    return@withLock true
                }
                val wire = protocol.wireLanguage(theirs)
                if (wire == null) {
                    current?.let(::retire)
                    told()
                    cannotSpeak(theirs)
                    return@withLock true
                }
                when {
                    current != null && current.link.targetWireLanguage.equals(wire, ignoreCase = true) -> true
                    current != null && protocol.retargetsInPlace && current.alive && current.link.retarget(wire) -> true
                    else -> {
                        swap(current, openOrNull(LegRole.OUTBOUND, wire) ?: return@withLock false)
                        true
                    }
                }
            }
        }

        private fun cannotSpeak(languageCode: String) {
            stateStore.setNotice(
                "$providerName can't translate into ${TargetLanguage.forCode(languageCode).displayName}, " +
                    "so what you say is not being translated for them.",
            )
        }

        // ── audio in ────────────────────────────────────────────────────

        // Runs on the capture thread, every frame.
        private fun onMicFrame(frame: ByteArray) {
            val closed = micClosed
            if (!closed && voice.isSpeech(frame)) lastMicSpeechAtMs = now()
            // Silence, not nothing: a provider that stops receiving treats what
            // comes next as joined on to what came before.
            val json = if (closed) silentFrame else protocol.audioFrame(frame)
            for (leg in micLegs) leg.link.socket.sendText(json)
            for (leg in draining) leg.link.socket.sendText(silentFrame)
        }

        private fun applyRoute(shared: Boolean) {
            consecutive = shared
            playbackEngine.setConsecutive(shared)
            floor.reset()
            micClosed = false
            // Whatever an earlier attempt left playing waits its turn again.
            if (shared) playbackEngine.hold()
        }

        private suspend fun tick() {
            while (true) {
                delay(TICK_MS)
                val at = now()
                synchronized(engineLock) { engine.onTick(at) }
                // Read here and nowhere else, so the route and the floor
                // cannot change under each other.
                val shared = route.value.sharedWithMicrophone
                if (shared != consecutive) applyRoute(shared)
                if (consecutive) takeTurns(at)
                show()
                watch(at)
            }
        }

        private fun takeTurns(at: Long) {
            val playback = playbackEngine.snapshot()
            val before = floor.floor
            floor.update(at, lastMicSpeechAtMs, playback.waitingMs, playback.audible, arriving = engineSpeaking)
            if (floor.floor != before) {
                when (floor.floor) {
                    FloorControl.Floor.SPEAKING -> playbackEngine.release()
                    FloorControl.Floor.LISTENING -> playbackEngine.hold()
                    FloorControl.Floor.SETTLING -> Unit
                }
            }
            micClosed = floor.micClosed
        }

        private fun show() {
            val current = stateStore.state.value
            if (current !in WHILE_UP) return
            // On a loudspeaker "listening" is only true once the microphone is open again.
            val heard = if (consecutive) floor.micClosed else engineSpeaking
            val next = when {
                // Said first: while a call has the microphone nothing can be
                // heard, whatever is still being played.
                micTaken -> RuntimeState.MICROPHONE_TAKEN
                heard -> RuntimeState.PLAYING
                else -> RuntimeState.LISTENING
            }
            if (next != current) stateStore.set(next)
        }

        private fun watch(at: Long) {
            for (leg in micLegs) {
                val expires = leg.expiresAtMs
                if (expires != NEVER && expires - at < EXPIRY_NOTICE_MS) rollOver(leg, expires - at)
            }
        }

        // ── what the engine decided ─────────────────────────────────────

        override fun play(leg: Int, pcm: ByteArray, sampleRateHz: Int, voiced: Boolean, begins: Boolean) =
            playbackEngine.write(leg, pcm, sampleRateHz, voiced, begins)

        override fun muteQueued(leg: Int) = playbackEngine.muteQueued(leg)

        // What I said is carried by the direction that speaks their language.
        override fun captionDelta(leg: Int, text: String) = captionsStore.appendDelta(
            source = leg,
            side = if (legs[leg]?.role == LegRole.OUTBOUND) CaptionSide.MINE else CaptionSide.THEIRS,
            text = text,
        )

        override fun captionCommit(leg: Int) = captionsStore.commitLine(leg)

        override fun captionDiscard(leg: Int) = captionsStore.discardPending(leg)

        override fun speakingChanged(speaking: Boolean) {
            engineSpeaking = speaking
        }

        override fun theirLanguageHeard(languageCode: String) {
            Log.i(TAG, "they are speaking $languageCode")
            heardTheirs = languageCode
            stateStore.setHeardLanguage(TargetLanguage.forCode(languageCode))
            sync()
        }
    }

    companion object {
        private const val TAG = "SessionCoord"
        private const val NEVER = -1L
        // Long enough to ride out a move between Wi-Fi and mobile data.
        private const val MAX_RECONNECT_ATTEMPTS = 6

        private const val TICK_MS = 50L
        private const val RETRY_DELAY_MS = 1_500L
        private const val MAX_RETRY_DELAY_MS = 6_000L

        /** The states a session that is up moves between by itself. */
        private val WHILE_UP = setOf(RuntimeState.LISTENING, RuntimeState.PLAYING, RuntimeState.MICROPHONE_TAKEN)

        /** A stumble or two is not worth a word to the user; this many is. */
        private const val ATTEMPTS_BEFORE_SAYING = 4

        /** Used when a provider warns it is leaving without saying when. */
        private const val DEFAULT_NOTICE_MS = 30_000L

        /** A replacement is in place this long before the provider's deadline. */
        private const val ROLLOVER_MARGIN_MS = 15_000L
        private const val EXPIRY_NOTICE_MS = 60_000L

        /** No voice for this long is a gap between sentences, safe to switch in. */
        private const val SWITCH_QUIET_MS = 400L
        private const val SWITCH_POLL_MS = 40L

        /** The model answers a few seconds behind, so a replaced leg is kept past that. */
        private const val DRAIN_MIN_MS = 5_000L
        private const val DRAIN_MAX_MS = 5_000L
        private const val DRAIN_POLL_MS = 100L
        private const val GOODBYE_MS = 3_000L
    }
}
