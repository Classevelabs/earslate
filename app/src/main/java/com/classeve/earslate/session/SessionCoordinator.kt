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
import com.classeve.earslate.live.ProviderLink
import com.classeve.earslate.live.TranslationLiveProtocol
import com.classeve.earslate.live.TranslationLiveProtocols
import com.classeve.earslate.live.failure
import com.classeve.earslate.ui.captions.CaptionsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
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

    // Kept across reconnects, or a dropped connection would send my next
    // sentence out in English again.
    @Volatile private var heardTheirs: String? = null

    private sealed interface Outcome {
        /** Cannot work as asked; another attempt would fail the same way. */
        class Failed(val error: RuntimeError) : Outcome

        /** Was working and the connection went. Worth another attempt. */
        class Lost(val why: LinkFailure) : Outcome
    }

    fun start(policy: TranslatorPolicy) {
        synchronized(this) {
            if (lifecycleJob != null) {
                Log.i(TAG, "start ignored; already active")
                return
            }
            captionsStore.clear()
            stateStore.clearError()
            reconnect.reset()
            stopRequested = false
            heardTheirs = null
            stateStore.setHeardLanguage(null)
            stateStore.setTheirLanguagePinned(false)

            lifecycleJob = scope.launch {
                try {
                    reconnectLoop(policy)
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
                    // Whatever path ended the job, nothing is running now, and
                    // the screen must not go on showing a session.
                    if (stateStore.state.value != RuntimeState.IDLE) stateStore.set(RuntimeState.IDLE)
                    stateStore.setHeardLanguage(null)
                }
            }
        }
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
        live?.change(my, their, follow)
    }

    private suspend fun reconnectLoop(policy: TranslatorPolicy) {
        var retrying = false
        while (true) {
            val outcome = coroutineScope {
                val session = LiveSession(policy, this, retrying)
                try {
                    session.run()
                } finally {
                    live = null
                    withContext(NonCancellable) { session.close() }
                    coroutineContext.job.cancelChildren()
                }
            }
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
                            if (outcome.why.providerSpoke) {
                                RuntimeError(RuntimeError.Kind.PROVIDER_ERROR, outcome.why.message.orEmpty())
                            } else {
                                RuntimeError(
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
        @Volatile var expiresAtWallMs = NEVER
    }

    /** One attempt at a session: everything it owns ends with it. */
    private inner class LiveSession(
        private val policy: TranslatorPolicy,
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

        @Volatile private var myLanguage = policy.myLanguage
        @Volatile private var consecutive = false
        @Volatile private var micClosed = false
        @Volatile private var lastMicSpeechAtMs = 0L
        @Volatile private var engineSpeaking = false
        @Volatile private var lastNotice: String? = null
        @Volatile private var readyAtMs = 0L
        @Volatile private var unsupportedTheirs: String? = null
        @Volatile private var lastOpenFailure: String? = null

        private val providerName get() = protocol.provider.displayName

        suspend fun run(): Outcome {
            stateStore.set(RuntimeState.BOOTSTRAPPING)
            var credential = try {
                credentials.credential(policy.provider)
            } catch (failure: BootstrapException) {
                return failed(RuntimeError.Kind.BOOTSTRAP_FAILED, failure.message)
            }
            protocol = TranslationLiveProtocols.forProvider(credential.provider)
            val myWire = protocol.wireLanguage(myLanguage.bcp47) ?: return failed(
                RuntimeError.Kind.BOOTSTRAP_FAILED,
                "$providerName can't translate into ${myLanguage.displayName}. " +
                    "Choose another language, or another provider, in Settings.",
            )

            val pinned = policy.otherLanguage
            val theirs = pinned?.bcp47 ?: heardTheirs ?: TargetLanguage.EnglishUS.bcp47
            engine = ConversationEngine(myLanguage.bcp47, theirs, followsTheirLanguage = pinned == null, sink = this)
            live = this
            stateStore.setTheirLanguagePinned(pinned != null)
            stateStore.setHeardLanguage(pinned ?: heardTheirs?.let(TargetLanguage::forCode))

            stateStore.set(RuntimeState.CONNECTING)
            val theirWire = protocol.wireLanguage(theirs)?.takeUnless { it.equals(myWire, ignoreCase = true) }
            val opened = try {
                openBoth(credential, myWire, theirWire)
            } catch (first: LinkFailure) {
                // A credential handed out earlier may have gone stale since.
                // A fresh one either works or says exactly what is wrong.
                credentials.discard(credential)
                try {
                    credential = credentials.credential(policy.provider)
                    openBoth(credential, myWire, theirWire)
                } catch (failure: BootstrapException) {
                    return failed(RuntimeError.Kind.BOOTSTRAP_FAILED, failure.message)
                } catch (failure: LinkFailure) {
                    return failed(failure)
                }
            }
            opened.forEach(::adopt)
            micLegs = opened

            audioFocus.acquire()
            playbackEngine.start()
            applyRoute(route.value)

            voice = VoiceActivity(protocol.inputSampleRateHz)
            silentFrame = protocol.audioFrame(
                ByteArray(protocol.inputSampleRateHz * protocol.inputFrameMs / 1000 * 2),
            )
            val listening = captureEngine.start(
                sampleRateHz = protocol.inputSampleRateHz,
                frameMs = protocol.inputFrameMs,
                onFrame = ::onMicFrame,
                onError = { ending.complete(Outcome.Lost(LinkFailure("The microphone was lost.", providerSpoke = false))) },
            )
            if (!listening) {
                return failed(RuntimeError.Kind.UNKNOWN, "Could not open the microphone. Another app may be using it.")
            }

            readyAtMs = now()
            stateStore.set(RuntimeState.LISTENING)
            sessionScope.launch { route.collect { applyRoute(it) } }
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

        fun close() {
            captureEngine.stop()
            for (leg in legs.values) {
                leg.retired = true
                leg.link.close()
            }
            playbackEngine.stop(graceful = true)
            audioFocus.release()
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

        private fun adopt(leg: Leg) {
            legs[leg.id] = leg
            synchronized(engineLock) { engine.legOpened(leg.id, leg.role) }
            sessionScope.launch { pump(leg) }
        }

        private suspend fun pump(leg: Leg) {
            for (frame in leg.link.socket.frames) {
                for (event in protocol.parse(frame)) handle(leg, event)
            }
            legs.remove(leg.id)
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
                is LiveEvent.SessionExpiry -> leg.expiresAtWallMs = event.epochSeconds * 1000
                is LiveEvent.ProviderNotice -> {
                    lastNotice = event.message
                    Log.w(TAG, "provider notice on leg ${leg.id}")
                }
                LiveEvent.SetupComplete, LiveEvent.SessionClosed -> Unit
            }
        }

        /**
         * The provider is about to end [old]. Open its replacement now, move
         * the microphone across at a pause, and let [old] finish its sentence.
         */
        private fun rollOver(old: Leg, withinMs: Long) {
            if (old.retired || old.replacing) return
            old.replacing = true
            sessionScope.launch {
                val switchBy = now() + (withinMs - ROLLOVER_MARGIN_MS).coerceAtLeast(0)
                var fresh: Leg? = null
                while (fresh == null && !old.retired) {
                    fresh = openOrNull(old.role, old.link.targetWireLanguage)
                    if (fresh == null) {
                        if (now() >= switchBy) break
                        delay(RETRY_DELAY_MS)
                    }
                }
                if (fresh == null) {
                    old.replacing = false
                    return@launch
                }
                while (now() < switchBy && now() - lastMicSpeechAtMs < SWITCH_QUIET_MS) delay(SWITCH_POLL_MS)
                retargeting.withLock {
                    val current = old.link.targetWireLanguage
                    val aimed = fresh.link.targetWireLanguage.equals(current, ignoreCase = true) ||
                        fresh.link.retarget(current)
                    if (old.retired || !aimed) fresh.link.close() else swap(old, fresh)
                }
            }
        }

        private suspend fun openOrNull(role: LegRole, wire: String): Leg? = try {
            open(role, wire, credentials.credential(policy.provider))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Exception) {
            Log.w(TAG, "could not open a ${role.name.lowercase()} leg: ${t.javaClass.simpleName}")
            // Only failures written for the user are kept to show them.
            lastOpenFailure = t.message.takeIf { t is LinkFailure || t is BootstrapException }
            null
        }

        private fun swap(old: Leg?, fresh: Leg) {
            adopt(fresh)
            micLegs = micLegs.filter { it !== old } + fresh
            old?.let(::retire)
        }

        /** Stop feeding [leg], let it finish what it is saying, then close it. */
        private fun retire(leg: Leg) {
            leg.retired = true
            micLegs = micLegs.filter { it !== leg }
            sessionScope.launch {
                // Its translation of the last words it heard is still to come.
                delay(DRAIN_MIN_MS)
                val deadline = now() + DRAIN_MAX_MS
                while (now() < deadline && synchronized(engineLock) { engine.isMidRun(leg.id) }) delay(DRAIN_POLL_MS)
                leg.link.close()
            }
        }

        /** Point "me → them" at [languageCode], the language the other person speaks. */
        private suspend fun aimOutbound(languageCode: String, attempt: Int = 1) {
            val done = retargeting.withLock {
                val myWire = protocol.wireLanguage(myLanguage.bcp47)
                val wire = protocol.wireLanguage(languageCode)
                val current = micLegs.firstOrNull { it.role == LegRole.OUTBOUND }
                when {
                    wire == null -> {
                        if (unsupportedTheirs != languageCode) {
                            unsupportedTheirs = languageCode
                            stateStore.setError(
                                RuntimeError(
                                    RuntimeError.Kind.PROVIDER_ERROR,
                                    "$providerName can't translate into ${TargetLanguage.forCode(languageCode).displayName}, " +
                                        "so what you say is not being translated for them.",
                                ),
                            )
                        }
                        true
                    }
                    // Both sides speak one language: there is no second direction.
                    wire.equals(myWire, ignoreCase = true) -> {
                        current?.let(::retire)
                        true
                    }
                    current != null && current.link.targetWireLanguage.equals(wire, ignoreCase = true) -> true
                    current != null && protocol.retargetsInPlace -> current.link.retarget(wire)
                    else -> {
                        val fresh = openOrNull(LegRole.OUTBOUND, wire)
                        if (fresh != null) swap(current, fresh)
                        fresh != null
                    }
                }
            }
            // The reply's only route into their language: a stumble must not
            // leave it shut, and a lasting failure must not be silent.
            if (done || engine.theirLanguage != languageCode) return
            if (attempt < RETARGET_ATTEMPTS) {
                delay(RETRY_DELAY_MS * attempt)
                aimOutbound(languageCode, attempt + 1)
            } else {
                stateStore.setError(
                    RuntimeError(
                        RuntimeError.Kind.CONNECT_FAILED,
                        "What you say is not being translated for them yet. " +
                            (lastOpenFailure ?: "The connection for it could not be opened."),
                    ),
                )
            }
        }

        private suspend fun aimInbound(language: TargetLanguage) {
            val wire = protocol.wireLanguage(language.bcp47)
            if (wire == null) {
                stateStore.setError(
                    RuntimeError(
                        RuntimeError.Kind.PROVIDER_ERROR,
                        "$providerName can't translate into ${language.displayName}. Your language was not changed.",
                    ),
                )
                return
            }
            retargeting.withLock {
                val current = micLegs.firstOrNull { it.role == LegRole.INBOUND } ?: return@withLock
                if (!current.link.targetWireLanguage.equals(wire, ignoreCase = true)) {
                    if (!(protocol.retargetsInPlace && current.link.retarget(wire))) {
                        swap(current, openOrNull(LegRole.INBOUND, wire) ?: return@withLock)
                    }
                }
                myLanguage = language
                synchronized(engineLock) { engine.reconfigure(language.bcp47, engine.theirLanguage) }
            }
            aimOutbound(engine.theirLanguage)
        }

        fun change(my: TargetLanguage?, their: TargetLanguage?, follow: Boolean?) {
            sessionScope.launch {
                follow?.let {
                    engine.followsTheirLanguage = it
                    stateStore.setTheirLanguagePinned(!it)
                }
                my?.let { aimInbound(it) }
                their?.let {
                    engine.followsTheirLanguage = false
                    stateStore.setTheirLanguagePinned(true)
                    heardTheirs = it.bcp47
                    stateStore.setHeardLanguage(it)
                    synchronized(engineLock) { engine.reconfigure(myLanguage.bcp47, it.bcp47) }
                    aimOutbound(it.bcp47)
                }
            }
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
        }

        private fun applyRoute(route: AudioRoute) {
            consecutive = route.sharedWithMicrophone
            playbackEngine.setConsecutive(consecutive)
            if (!consecutive) {
                floor.reset()
                micClosed = false
            }
        }

        private suspend fun tick() {
            while (true) {
                delay(TICK_MS)
                val at = now()
                synchronized(engineLock) { engine.onTick(at) }
                if (consecutive) takeTurns(at)
                show()
                watch()
            }
        }

        private fun takeTurns(at: Long) {
            val playback = playbackEngine.snapshot()
            val before = floor.floor
            floor.update(at, lastMicSpeechAtMs, playback.waitingMs, playback.audible)
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
            if (current != RuntimeState.LISTENING && current != RuntimeState.PLAYING) return
            val heard = if (consecutive) floor.playing else engineSpeaking
            val next = if (heard) RuntimeState.PLAYING else RuntimeState.LISTENING
            if (next != current) stateStore.set(next)
        }

        private fun watch() {
            val wall = wallClock()
            for (leg in micLegs) {
                val expires = leg.expiresAtWallMs
                if (expires != NEVER && expires - wall < EXPIRY_NOTICE_MS) rollOver(leg, expires - wall)
            }
        }

        // ── what the engine decided ─────────────────────────────────────

        override fun play(leg: Int, pcm: ByteArray, sampleRateHz: Int, voiced: Boolean) =
            playbackEngine.write(leg, pcm, sampleRateHz, voiced)

        override fun muteQueued(leg: Int) = playbackEngine.muteQueued(leg)

        override fun captionDelta(leg: Int, text: String) = captionsStore.appendDelta(leg, text)

        override fun captionCommit(leg: Int) = captionsStore.commitLine(leg)

        override fun captionDiscard(leg: Int) = captionsStore.discardPending(leg)

        override fun speakingChanged(speaking: Boolean) {
            engineSpeaking = speaking
        }

        override fun theirLanguageHeard(languageCode: String) {
            Log.i(TAG, "they are speaking $languageCode")
            heardTheirs = languageCode
            stateStore.setHeardLanguage(TargetLanguage.forCode(languageCode))
            sessionScope.launch { aimOutbound(languageCode) }
        }
    }

    companion object {
        private const val TAG = "SessionCoord"
        private const val NEVER = -1L
        // Long enough to ride out a move between Wi-Fi and mobile data.
        private const val MAX_RECONNECT_ATTEMPTS = 6

        private const val TICK_MS = 50L
        private const val RETARGET_ATTEMPTS = 4
        private const val RETRY_DELAY_MS = 1_500L

        /** Used when a provider warns it is leaving without saying when. */
        private const val DEFAULT_NOTICE_MS = 30_000L

        /** A replacement is in place this long before the provider's deadline. */
        private const val ROLLOVER_MARGIN_MS = 15_000L
        private const val EXPIRY_NOTICE_MS = 60_000L

        /** No voice for this long is a gap between sentences, safe to switch in. */
        private const val SWITCH_QUIET_MS = 400L
        private const val SWITCH_POLL_MS = 40L

        /** The model answers a few seconds behind, so a replaced leg is kept at least this long. */
        private const val DRAIN_MIN_MS = 3_000L
        private const val DRAIN_MAX_MS = 5_000L
        private const val DRAIN_POLL_MS = 100L
    }
}
