package com.classeve.earslate.session

import com.classeve.earslate.audio.AudioPlaybackEngine
import com.classeve.earslate.audio.AudioRoute
import com.classeve.earslate.bootstrap.BootstrapException
import com.classeve.earslate.live.LiveSocketState
import com.classeve.earslate.testing.FakeCapture
import com.classeve.earslate.testing.FakeCredentials
import com.classeve.earslate.testing.FakeFocus
import com.classeve.earslate.testing.FakePlayback
import com.classeve.earslate.testing.FakeSocket
import com.classeve.earslate.testing.LanePlayback
import com.classeve.earslate.testing.TestAudio
import com.classeve.earslate.ui.captions.CaptionsStore
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The whole session, run for real against a scripted provider: sockets,
 * microphone and loudspeaker are stand-ins, everything between them is the
 * app's own code on its own clock.
 */
class SessionCoordinatorTest {

    private val english = TargetLanguage("English", "en-US")
    private val spanish = TargetLanguage("Español", "es-ES")
    private val punjabi = TargetLanguage("ਪੰਜਾਬੀ", "pa-IN")
    private val hindi = TargetLanguage("हिन्दी", "hi-IN")
    private val french = TargetLanguage("Français", "fr-FR")

    private val sockets = CopyOnWriteArrayList<FakeSocket>()
    private val capture = FakeCapture()
    private val playback = FakePlayback()
    private val captions = CaptionsStore()
    private val state = RuntimeStateStore()
    private val focus = FakeFocus()
    private val route = MutableStateFlow(AudioRoute.BLUETOOTH)
    private val networkChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val seen = CopyOnWriteArrayList<RuntimeState>()

    /** What a new socket does; tests swap it to script a refusal. */
    @Volatile private var server: () -> FakeSocket = ::gemini

    private fun gemini() = FakeSocket(onSend = { if (it.contains("\"setup\"")) serve("""{"setupComplete":{}}""") })

    private fun openAi() = FakeSocket(
        onConnect = {
            accept()
            serve("""{"type":"session.created","session":{}}""")
        },
        onSend = { if (it.contains("session.update")) serve("""{"type":"session.updated","session":{}}""") },
    )

    private fun coordinator(credentials: FakeCredentials, playbackEngine: AudioPlaybackEngine) = SessionCoordinator(
        credentials = credentials,
        socketFactory = { server().also { sockets += it } },
        captureEngine = capture,
        playbackEngine = playbackEngine,
        captionsStore = captions,
        stateStore = state,
        audioFocus = focus,
        route = route,
        networkChanged = networkChanged,
        now = { System.nanoTime() / 1_000_000 },
    )

    private var running: SessionCoordinator? = null

    private fun start(
        policy: TranslatorPolicy,
        credentials: FakeCredentials = FakeCredentials(),
        playbackEngine: AudioPlaybackEngine = playback,
    ): SessionCoordinator {
        val coordinator = coordinator(credentials, playbackEngine)
        running = coordinator
        coordinator.start(policy)
        return coordinator
    }

    @After
    fun stopSession() {
        running?.stop()
        await("the session to end") { state.state.value == RuntimeState.IDLE }
    }

    private fun await(what: String, timeoutMs: Long = 6_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            state.state.value.let { if (seen.lastOrNull() != it) seen += it }
            check(System.currentTimeMillis() < deadline) { "timed out waiting for $what (state ${state.state.value}, error ${state.lastError.value?.message})" }
            Thread.sleep(10)
        }
    }

    private fun awaitListening() = await("the session to listen") { state.state.value == RuntimeState.LISTENING && capture.listening }

    private fun target(socket: FakeSocket): String {
        val first = JSONObject(socket.sent.first())
        return if (first.has("setup")) {
            first.getJSONObject("setup").getJSONObject("generationConfig").getJSONObject("translationConfig")
                .getString("targetLanguageCode")
        } else {
            first.getJSONObject("session").getJSONObject("audio").getJSONObject("output").getString("language")
        }
    }

    private fun socketFor(language: String, after: Int = 0): FakeSocket {
        await("a socket aimed at $language") { sockets.drop(after).any { it.sent.isNotEmpty() && target(it) == language } }
        return sockets.drop(after).first { it.sent.isNotEmpty() && target(it) == language }
    }

    private fun audio(ms: Int = 250, voiced: Boolean = true): String {
        val pcm = if (voiced) TestAudio.tone(ms) else TestAudio.silence(ms)
        return """{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"${
            Base64.getEncoder().encodeToString(pcm)
        }"}}]}}}"""
    }

    private fun heard(text: String, language: String) =
        """{"serverContent":{"inputTranscription":{"text":"$text","languageCode":"$language"}}}"""

    private fun micFrames(socket: FakeSocket): List<ByteArray> = socket.sent
        .filter { it.contains("realtimeInput") }
        .map { Base64.getDecoder().decode(JSONObject(it).getJSONObject("realtimeInput").getJSONObject("audio").getString("data")) }

    private var spoken = 0
    private val speech get() = TestAudio.speech(100, rateHz = 16_000, peak = 9_000, seed = ++spoken)

    // ── starting ────────────────────────────────────────────────────────

    @Test
    fun `both directions open on one credential, and the microphone opens at the provider's rate`() {
        val credentials = FakeCredentials()
        start(TranslatorPolicy(english, otherLanguage = spanish), credentials)
        awaitListening()

        assertEquals(setOf("en", "es"), sockets.map(::target).toSet())
        assertEquals("one request to the provider for both sockets", 1, credentials.minted.get())
        assertEquals(16_000, capture.sampleRateHz)
        assertEquals(100, capture.frameMs)
        assertTrue(playback.running)
        assertEquals(1, focus.held.get())
        assertNull(state.lastError.value)
    }

    @Test
    fun `when both people speak one language there is one direction`() {
        start(TranslatorPolicy(english))
        awaitListening()
        assertEquals(listOf("en"), sockets.map(::target))
    }

    @Test
    fun `every microphone frame reaches both directions`() {
        start(TranslatorPolicy(english, otherLanguage = spanish))
        awaitListening()
        val frame = speech
        capture.hear(frame)

        for (socket in sockets) assertTrue(micFrames(socket).single().contentEquals(frame))
    }

    // ── what is heard ───────────────────────────────────────────────────

    @Test
    fun `their translation is played and the echo in their own language is silenced, each on its own lane`() {
        start(TranslatorPolicy(english, otherLanguage = spanish))
        awaitListening()
        val inbound = socketFor("en")
        val outbound = socketFor("es")
        for (socket in listOf(inbound, outbound)) socket.serve(heard("Hola, buenos días", "es"))
        repeat(4) {
            inbound.serve(audio())
            outbound.serve(audio())
        }
        await("eight blocks to be handed to playback") { playback.written.size == 8 }

        val lanes = playback.written.map { it.lane }.distinct()
        assertEquals("one lane for each direction", 2, lanes.size)
        val heardPerLane = lanes.map { playback.heardMs(it) }.sorted()
        assertEquals("one direction heard in full, the other not at all", listOf(0, 1_000), heardPerLane)
    }

    @Test
    fun `captions appear as they are spoken and commit when the speaker stops`() {
        start(TranslatorPolicy(english, otherLanguage = spanish))
        awaitListening()
        val inbound = socketFor("en")
        inbound.serve(heard("Hola", "es"))
        inbound.serve(audio())
        inbound.serve("""{"serverContent":{"outputTranscription":{"text":" Hello there.","languageCode":"en"}}}""")
        await("the live caption") { captions.pending.value == listOf("Hello there.") }
        assertTrue(captions.lines.value.isEmpty())

        repeat(3) { inbound.serve(audio(voiced = false)) }
        await("the committed line") { captions.lines.value == listOf("Hello there.") }
        assertTrue(captions.pending.value.isEmpty())
    }

    // The lines stay on the screen after the session. One left half said
    // would look as if it were still coming.
    @Test
    fun `a sentence still being shown when the session is stopped is finished off`() {
        val coordinator = start(TranslatorPolicy(english, otherLanguage = spanish))
        awaitListening()
        val inbound = socketFor("en")
        inbound.serve(heard("Hola", "es"))
        inbound.serve(audio())
        inbound.serve("""{"serverContent":{"outputTranscription":{"text":" Hello there.","languageCode":"en"}}}""")
        await("the live caption") { captions.pending.value == listOf("Hello there.") }

        coordinator.stop()
        await("the session to end") { state.state.value == RuntimeState.IDLE }
        assertEquals(listOf("Hello there."), captions.lines.value)
        assertTrue("nothing is left looking as if it were still being said", captions.pending.value.isEmpty())
    }

    @Test
    fun `a sentence still being shown when the connection is replaced is finished off`() {
        start(TranslatorPolicy(english, otherLanguage = spanish))
        awaitListening()
        val inbound = socketFor("en")
        inbound.serve(heard("Hola", "es"))
        inbound.serve(audio())
        inbound.serve("""{"serverContent":{"outputTranscription":{"text":" Hello there.","languageCode":"en"}}}""")
        await("the live caption") { captions.pending.value == listOf("Hello there.") }

        networkChanged.tryEmit(Unit)
        await("the half-said line to be finished") { captions.pending.value.isEmpty() }
        assertEquals(listOf("Hello there."), captions.lines.value)
        await("the session to be back") { sockets.size >= 4 && state.state.value == RuntimeState.LISTENING }
        assertTrue("and it stays finished", captions.pending.value.isEmpty())
    }

    @Test
    fun `the state follows what is being heard`() {
        start(TranslatorPolicy(english, otherLanguage = spanish))
        awaitListening()
        val inbound = socketFor("en")
        inbound.serve(heard("Hola", "es"))
        inbound.serve(audio())
        await("playing") { state.state.value == RuntimeState.PLAYING }
        repeat(3) { inbound.serve(audio(voiced = false)) }
        await("listening again") { state.state.value == RuntimeState.LISTENING }
    }

    // ── following their language ────────────────────────────────────────

    @Test
    fun `hearing their language opens the direction back to them`() {
        start(TranslatorPolicy(english))
        awaitListening()
        sockets.single().serve(heard("Hola, buenos días", "es"))

        val outbound = socketFor("es")
        await("the heard language to be shown") { state.heardLanguage.value?.bcp47 == "es-ES" }
        await("the microphone to reach the new direction") {
            capture.hear(speech)
            micFrames(outbound).isNotEmpty()
        }
    }

    @Test
    fun `a pinned language is never moved by what is heard`() {
        start(TranslatorPolicy(english, otherLanguage = spanish))
        awaitListening()
        val inbound = socketFor("en")
        repeat(3) { inbound.serve(heard("नमस्ते क्या आप", "hi")) }
        Thread.sleep(400)

        assertEquals(2, sockets.size)
        assertEquals("es-ES", state.heardLanguage.value?.bcp47)
        assertTrue(state.theirLanguagePinned.value)
    }

    // ── the provider ends the connection ────────────────────────────────

    // Gemini ends every connection just under ten minutes in. The app used to wait for it and start over.
    @Test
    fun `a connection the provider warns it will end is replaced without the session noticing`() {
        val credentials = FakeCredentials()
        start(TranslatorPolicy(english, otherLanguage = spanish), credentials)
        awaitListening()
        val old = socketFor("en")
        old.serve("""{"goAway":{"timeLeft":"10s"}}""")

        await("the replacement") { sockets.size == 3 }
        val fresh = socketFor("en", after = 2)
        await("the microphone to move across") {
            capture.hear(speech)
            micFrames(fresh).isNotEmpty()
        }
        // Measured against Gemini: a session that simply stops being sent
        // audio drops the last word of what it was translating.
        val sentToOld = micFrames(old).size
        capture.hear(speech)
        capture.hear(speech)
        val afterwards = micFrames(old).drop(sentToOld)
        assertEquals("the old socket goes on being sent audio", 2, afterwards.size)
        assertTrue("silence, so that it finishes its sentence and starts no other", afterwards.all { f -> f.all { it == 0.toByte() } })
        assertFalse("and it is left open to finish", old.closedByApp)

        await("the old socket to be closed once it has finished", timeoutMs = 13_000) { old.closedByApp }
        assertEquals(RuntimeState.LISTENING, state.state.value)
        assertFalse("the user never saw a reconnect", RuntimeState.RECONNECTING in seen)
        assertEquals("and no second credential was needed", 1, credentials.minted.get())
        assertEquals(1, capture.starts.get())
        assertNull(state.lastError.value)
    }

    @Test
    fun `a connection that is simply lost is reconnected, with the same credential`() {
        val credentials = FakeCredentials()
        start(TranslatorPolicy(english, otherLanguage = spanish), credentials)
        awaitListening()
        sockets.first().fails()

        await("a fresh pair of sockets") { sockets.size == 4 && state.state.value == RuntimeState.LISTENING && capture.starts.get() == 2 }
        assertTrue(RuntimeState.RECONNECTING in seen || state.state.value == RuntimeState.LISTENING)
        assertEquals(1, credentials.minted.get())
        // What the lost connection still had to say is kept, not thrown away with it.
        assertEquals("the loudspeaker is not shut down in between", 0, playback.stops.get())
        assertTrue("its lanes are left to finish", playback.retired.size >= 2)
        assertTrue("the first session's sockets were closed", sockets.take(2).all { it.closedByApp || it.closure != null })
        assertNull(state.lastError.value)
    }

    // Leaving Wi-Fi kills the sockets without telling anyone; a ping would take many seconds to notice.
    @Test
    fun `moving to another network reconnects at once`() {
        val credentials = FakeCredentials()
        start(TranslatorPolicy(english, otherLanguage = spanish), credentials)
        awaitListening()
        networkChanged.tryEmit(Unit)

        await("a fresh pair of sockets", timeoutMs = 3_000) {
            sockets.size == 4 && state.state.value == RuntimeState.LISTENING && capture.starts.get() == 2
        }
        assertTrue(sockets.take(2).all { it.closedByApp })
        assertEquals(1, credentials.minted.get())
    }

    @Test
    fun `a microphone lost mid-session is reopened`() {
        start(TranslatorPolicy(english, otherLanguage = spanish))
        awaitListening()
        capture.breaks()
        await("the microphone to be reopened") { capture.starts.get() == 2 && state.state.value == RuntimeState.LISTENING }
    }

    // Recorded live: the model went quiet for 3.4 s mid-sentence and then carried on.
    @Test
    fun `a provider that pauses for a few seconds has not gone away`() {
        start(TranslatorPolicy(english, otherLanguage = spanish))
        awaitListening()
        val inbound = socketFor("en")
        inbound.serve(heard("Hola", "es"))
        inbound.serve(audio())
        inbound.serve(audio(voiced = false))
        val until = System.currentTimeMillis() + 4_500
        while (System.currentTimeMillis() < until) {
            capture.hear(speech)
            await("nothing") { true }
            Thread.sleep(50)
        }
        inbound.serve(audio())
        await("the audio after the pause") { playback.heardMs(playback.written.first().lane) == 500 }

        assertEquals("the same two sockets", 2, sockets.size)
        assertFalse(RuntimeState.RECONNECTING in seen)
        assertEquals(1, capture.starts.get())
    }

    @Test
    fun `the direction back to them is tried again when it will not open at first`() {
        var opened = 0
        server = {
            // The first socket is the listening one. The next refuses once; after that the provider accepts.
            if (++opened == 2) FakeSocket(onConnect = { fails() }) else gemini()
        }
        start(TranslatorPolicy(english))
        awaitListening()
        sockets.single().serve(heard("Hola, buenos días", "es"))

        val outbound = socketFor("es", after = 2)
        await("the microphone to reach it") {
            capture.hear(speech)
            micFrames(outbound).isNotEmpty()
        }
        assertNull(state.lastError.value)
    }

    // It used to give up after four tries, say "yet", and never try again.
    @Test
    fun `when the direction back to them will not open the user is told, and told no more once it does`() {
        var opened = 0
        var refusing = true
        server = { if (++opened >= 2 && refusing) FakeSocket(onConnect = { fails(httpStatus = 429) }) else gemini() }
        start(TranslatorPolicy(english))
        awaitListening()
        sockets.single().serve(heard("Hola, buenos días", "es"))

        await("the notice", timeoutMs = 15_000) { state.notice.value != null }
        val message = state.notice.value!!
        assertTrue(message, message.startsWith("What you say is not being translated for them yet."))
        assertTrue(message, message.contains("quota"))
        assertNull("it is not an error: the session goes on", state.lastError.value)
        assertEquals("the half that works keeps working", RuntimeState.LISTENING, state.state.value)

        refusing = false
        await("the direction to open at last", timeoutMs = 12_000) {
            capture.hear(speech)
            sockets.any { it.sent.isNotEmpty() && target(it) == "es" && micFrames(it).isNotEmpty() }
        }
        await("the notice to be taken away") { state.notice.value == null }
    }

    // ── refusals ────────────────────────────────────────────────────────

    // This used to be retried four times and then reported as a lost connection.
    @Test
    fun `a provider that refuses the session is final, and the user is told what it said`() {
        val credentials = FakeCredentials()
        server = { FakeSocket(onConnect = { fails(httpStatus = 403, body = "API key not valid. Please pass a valid API key.") }) }
        start(TranslatorPolicy(english, otherLanguage = spanish), credentials)

        await("the refusal") { state.lastError.value != null && state.state.value == RuntimeState.IDLE }
        val error = state.lastError.value!!
        assertEquals(RuntimeError.Kind.PROVIDER_ERROR, error.kind)
        assertTrue(error.message, error.message.contains("API key not valid"))
        assertEquals("tried once more with a fresh credential, then stopped", 2, credentials.minted.get())
        // One or both directions per attempt, depending on which refusal lands first.
        val tried = sockets.size
        assertTrue("sockets tried: $tried", tried in 2..4)
        Thread.sleep(600)
        assertEquals("and nothing is tried after that", tried, sockets.size)
        assertFalse(capture.listening)
    }

    @Test
    fun `a key problem is reported in the minter's words`() {
        val credentials = FakeCredentials().apply { failure = BootstrapException("Google Gemini did not accept that key.") }
        start(TranslatorPolicy(english), credentials)

        await("the failure") { state.lastError.value != null && state.state.value == RuntimeState.IDLE }
        assertEquals(RuntimeError.Kind.BOOTSTRAP_FAILED, state.lastError.value!!.kind)
        assertEquals("Google Gemini did not accept that key.", state.lastError.value!!.message)
        assertTrue(sockets.isEmpty())
    }

    @Test
    fun `a microphone that will not open is said plainly, and nothing is left running`() {
        capture.refuses = true
        start(TranslatorPolicy(english, otherLanguage = spanish))

        await("the failure") { state.lastError.value != null && state.state.value == RuntimeState.IDLE }
        assertTrue(state.lastError.value!!.message.contains("microphone"))
        await("the sockets to be closed") { sockets.all { it.closedByApp } }
        assertFalse(playback.running)
        assertEquals(0, focus.held.get())
    }

    // ── stopping ────────────────────────────────────────────────────────

    @Test
    fun `stop closes the sockets, the microphone and the loudspeaker`() {
        val coordinator = start(TranslatorPolicy(english, otherLanguage = spanish))
        awaitListening()
        assertTrue(coordinator.stop())

        await("idle") { state.state.value == RuntimeState.IDLE }
        assertTrue(sockets.all { it.closedByApp })
        assertFalse(capture.listening)
        assertFalse(playback.running)
        assertEquals(0, focus.held.get())
        assertNull("stopping is not an error", state.lastError.value)
        assertFalse("nothing to stop a second time", coordinator.stop())
    }

    @Test
    fun `a second start while running is ignored`() {
        val coordinator = start(TranslatorPolicy(english, otherLanguage = spanish))
        awaitListening()
        coordinator.start(TranslatorPolicy(english, otherLanguage = spanish))
        Thread.sleep(200)
        assertEquals(2, sockets.size)
    }

    // ── a loudspeaker both people share ─────────────────────────────────

    // The old gate closed the microphone the moment the phone spoke, mid-sentence.
    @Test
    fun `on a loudspeaker the translation waits for a pause, and the microphone hears silence only while it plays`() {
        route.value = AudioRoute.SPEAKER
        start(TranslatorPolicy(english, otherLanguage = spanish))
        awaitListening()
        assertTrue(playback.takingTurns)
        val inbound = socketFor("en")
        val holdsAtStart = playback.holds.get()

        // They are talking, and a few words in the translation starts to arrive.
        val talkUntil = System.currentTimeMillis() + 1_500
        while (System.currentTimeMillis() < talkUntil) {
            capture.hear(speech)
            if (System.currentTimeMillis() > talkUntil - 1_200) playback.waitingMs = 2_000
            Thread.sleep(50)
        }
        assertEquals("nothing is spoken over them", 0, playback.releases.get())
        assertTrue("and every word of theirs was sent", micFrames(inbound).all { it.any { b -> b != 0.toByte() } })

        // They pause.
        await("the translation to be let out") { playback.releases.get() == 1 }
        playback.audible = true
        val before = micFrames(inbound).size
        repeat(5) { capture.hear(speech) }
        val whilePlaying = micFrames(inbound).drop(before)
        assertEquals("the stream to the provider never stops", 5, whilePlaying.size)
        assertTrue("but it carries silence, not the phone's own voice", whilePlaying.all { frame -> frame.all { it == 0.toByte() } })
        assertEquals(RuntimeState.PLAYING, state.state.value)

        // The phone finishes.
        playback.waitingMs = 0
        playback.audible = false
        await("the microphone to open again") { playback.holds.get() == holdsAtStart + 1 }
        val after = micFrames(inbound).size
        capture.hear(speech)
        assertTrue(micFrames(inbound).drop(after).single().any { it != 0.toByte() })
    }

    @Test
    fun `earbuds going in ends turn-taking`() {
        route.value = AudioRoute.SPEAKER
        start(TranslatorPolicy(english, otherLanguage = spanish))
        awaitListening()
        route.value = AudioRoute.BLUETOOTH
        await("live playback") { !playback.takingTurns }
    }

    // The second direction's first audio arrived while the phone was already
    // speaking. Its lane began by waiting for a turn that had been given, the
    // phone showed "playing" for ever, and the microphone never opened again.
    @Test
    fun `on a loudspeaker a direction that first speaks while the phone is talking is still heard`() {
        val lanes = LanePlayback()
        try {
            route.value = AudioRoute.SPEAKER
            start(TranslatorPolicy(english, otherLanguage = spanish), playbackEngine = lanes)
            awaitListening()
            val inbound = socketFor("en")
            val outbound = socketFor("es")

            // Two seconds of their translation arrive. The room is quiet, so the phone speaks.
            inbound.serve(heard("Hola, buenos días", "es"))
            repeat(8) { inbound.serve(audio()) }
            await("the phone to start speaking") { state.state.value == RuntimeState.PLAYING }

            // While it speaks, the other direction says its first words.
            outbound.serve(heard("Hello there", "en"))
            repeat(4) { outbound.serve(audio()) }

            await("everything to have been said", timeoutMs = 10_000) {
                lanes.snapshot().waitingMs == 0 && lanes.totalHeardMs >= 3_000
            }
            await("the phone to be listening again") { state.state.value == RuntimeState.LISTENING }
            val frame = speech
            capture.hear(frame)
            assertTrue("and the microphone is open", micFrames(inbound).last().contentEquals(frame))
        } finally {
            lanes.shutDown()
        }
    }

    // ── the network ─────────────────────────────────────────────────────

    // The first retry used to end the session if the network was not back yet.
    @Test
    fun `a reconnect that finds the network still down keeps trying until it is back`() {
        val credentials = FakeCredentials()
        start(TranslatorPolicy(english, otherLanguage = spanish), credentials)
        awaitListening()

        // The network goes: nothing connects, and the provider cannot be asked for a credential either.
        server = { FakeSocket(onConnect = { fails() }) }
        credentials.failure = BootstrapException("Couldn't reach Google Gemini. Check your connection and try again.", transient = true)
        sockets.first().fails()
        await("reconnecting") { state.state.value == RuntimeState.RECONNECTING }
        Thread.sleep(1_200)
        assertNull("still trying, not given up", state.lastError.value)
        assertTrue(state.state.value != RuntimeState.IDLE)

        // And comes back.
        credentials.failure = null
        server = ::gemini
        await("the session to be listening again", timeoutMs = 12_000) {
            state.state.value == RuntimeState.LISTENING && capture.listening
        }
        assertNull(state.lastError.value)
    }

    @Test
    fun `a refusal while reconnecting is final`() {
        val credentials = FakeCredentials()
        start(TranslatorPolicy(english, otherLanguage = spanish), credentials)
        awaitListening()
        credentials.failure = BootstrapException("Google Gemini did not accept that key.")
        sockets.first().fails()

        await("the session to end") { state.state.value == RuntimeState.IDLE }
        assertEquals("Google Gemini did not accept that key.", state.lastError.value?.message)
    }

    @Test
    fun `a network that never comes back ends the session after a bounded number of tries`() {
        start(TranslatorPolicy(english, otherLanguage = spanish))
        awaitListening()
        server = { FakeSocket(onConnect = { fails() }) }
        sockets.first().fails()

        await("the session to give up", timeoutMs = 25_000) { state.state.value == RuntimeState.IDLE }
        assertEquals("Lost connection and could not reconnect. Tap start to try again.", state.lastError.value?.message)
        assertTrue("it tried more than once and not for ever: ${sockets.size} sockets", sockets.size in 6..16)
    }

    @Test
    fun `a sentence half shown when the connection goes is finished off, not left looking live`() {
        start(TranslatorPolicy(english, otherLanguage = spanish))
        awaitListening()
        val inbound = socketFor("en")
        inbound.serve(heard("Hola", "es"))
        inbound.serve(audio())
        inbound.serve("""{"serverContent":{"outputTranscription":{"text":" Hello there","languageCode":"en"}}}""")
        await("the live caption") { captions.pending.value == listOf("Hello there") }

        inbound.fails()
        await("the line to be finished") { captions.pending.value.isEmpty() }
        assertEquals(listOf("Hello there"), captions.lines.value)
    }

    // ── replacing a connection ──────────────────────────────────────────

    private fun whileSomeoneTalks(block: () -> Unit) {
        val talking = Thread {
            while (true) {
                capture.hear(TestAudio.speech(100, rateHz = 16_000, peak = 9_000, seed = 7))
                try {
                    Thread.sleep(50)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }.also { it.start() }
        try {
            // Long enough for the session to have heard them.
            Thread.sleep(300)
            block()
        } finally {
            talking.interrupt()
            talking.join()
        }
    }

    @Test
    fun `a replacement still waiting for a pause is closed when the session stops`() {
        val coordinator = start(TranslatorPolicy(english, otherLanguage = spanish))
        awaitListening()
        whileSomeoneTalks {
            socketFor("en").serve("""{"goAway":{"timeLeft":"50s"}}""")
            // Set up, not merely open: one ended sooner than that never was a replacement.
            await("the replacement to be set up") { sockets.size == 3 && sockets[2].sent.isNotEmpty() }
            Thread.sleep(200)
            assertFalse("it waits for a pause before it is put to use", micFrames(sockets[2]).isNotEmpty())

            coordinator.stop()
            await("the unused replacement to be closed") { sockets[2].closedByApp }
        }
    }

    @Test
    fun `a replacement that does not survive the wait is not put to use`() {
        start(TranslatorPolicy(english, otherLanguage = spanish))
        awaitListening()
        whileSomeoneTalks {
            socketFor("en").serve("""{"goAway":{"timeLeft":"50s"}}""")
            // Set up, not merely open: one ended sooner than that never was a replacement.
            await("the replacement to be set up") { sockets.size == 3 && sockets[2].sent.isNotEmpty() }
            sockets[2].serverCloses(1001, "")
            await("another to be opened in its place") { sockets.size == 4 && sockets[3].state.value == LiveSocketState.OPEN }
        }
        // They pause, and the switch is made in the pause.
        Thread.sleep(700)
        await("the microphone to move to the one that lived") {
            capture.hear(speech)
            micFrames(sockets[3]).isNotEmpty()
        }
        assertFalse("the user never saw a reconnect", RuntimeState.RECONNECTING in seen)
        assertEquals(RuntimeState.LISTENING, state.state.value)
        // A reconnect can come and go between two looks at the state. What it
        // leaves behind is a microphone opened twice and a fresh pair of sockets.
        assertEquals("the session was never started again", 1, capture.starts.get())
        assertEquals("one replacement died, one lived, and nothing else was opened", 4, sockets.size)
    }

    // ── changing a language while it runs ───────────────────────────────

    @Test
    fun `a language changed mid-session is still the language after a reconnect`() {
        val coordinator = start(TranslatorPolicy(english, otherLanguage = spanish))
        awaitListening()
        coordinator.setLanguages(my = hindi)
        socketFor("hi")

        val before = sockets.size
        socketFor("es").fails()
        await("a fresh pair of sockets") { sockets.size >= before + 2 && state.state.value == RuntimeState.LISTENING }
        await("both to be set up") { sockets.drop(before).all { it.sent.isNotEmpty() } }
        assertEquals(
            "the language chosen, not the one the session started with",
            setOf("hi", "es"),
            sockets.drop(before).map(::target).toSet(),
        )
    }

    @Test
    fun `a language changed while the session is still connecting is the one it ends up with`() {
        // The provider takes a moment to acknowledge.
        server = {
            FakeSocket(
                onSend = {
                    if (it.contains("\"setup\"")) {
                        Thread {
                            Thread.sleep(400)
                            serve("""{"setupComplete":{}}""")
                        }.start()
                    }
                },
            )
        }
        val coordinator = start(TranslatorPolicy(english, otherLanguage = spanish))
        await("connecting") { state.state.value == RuntimeState.CONNECTING }
        coordinator.setLanguages(their = french)
        awaitListening()

        val outbound = socketFor("fr")
        await("the microphone to reach the new direction") {
            capture.hear(speech)
            micFrames(outbound).isNotEmpty()
        }
        val frame = speech
        capture.hear(frame)
        val fed = sockets.filter { micFrames(it).lastOrNull()?.contentEquals(frame) == true }
        assertEquals("one direction each way, and no third", setOf("en", "fr"), fed.map(::target).toSet())
        assertEquals(2, fed.size)
    }

    // The follower kept following while the language was fixed, so letting go
    // of it found nothing new in what it heard next.
    @Test
    fun `going back to following picks up the next language heard`() {
        val coordinator = start(TranslatorPolicy(english, otherLanguage = spanish))
        awaitListening()
        val inbound = socketFor("en")
        repeat(3) { inbound.serve(heard("bom dia tudo bem", "pt")) }
        Thread.sleep(200)
        coordinator.setLanguages(follow = true)
        await("no longer fixed") { !state.theirLanguagePinned.value }
        inbound.serve(heard("obrigado pela ajuda", "pt"))

        socketFor("pt-BR")
        await("the language to be shown") { state.heardLanguage.value?.bcp47 == "pt-BR" }
    }

    // ── OpenAI ──────────────────────────────────────────────────────────

    @Test
    fun `OpenAI told to answer in a language it cannot speak says so from the start`() {
        server = ::openAi
        start(TranslatorPolicy(english, otherLanguage = punjabi), FakeCredentials(TranslationProvider.OPENAI))
        awaitListening()

        await("the notice") { state.notice.value != null }
        assertEquals(
            "OpenAI can't translate into ਪੰਜਾਬੀ, so what you say is not being translated for them.",
            state.notice.value,
        )
        assertEquals("only the direction that can work is open", 1, sockets.size)
        assertNull(state.lastError.value)
    }

    // Inside its last seconds, every failed attempt used to be followed by another at once.
    @Test
    fun `an expiring OpenAI session whose replacement will not open is tried again without hammering`() {
        var opened = 0
        val expiresAt = System.currentTimeMillis() / 1000 + 20
        server = {
            if (++opened > 2) {
                FakeSocket(onConnect = { fails() })
            } else {
                FakeSocket(
                    onConnect = {
                        accept()
                        serve("""{"type":"session.created","session":{"expires_at":$expiresAt}}""")
                    },
                    onSend = { if (it.contains("session.update")) serve("""{"type":"session.updated","session":{}}""") },
                )
            }
        }
        start(TranslatorPolicy(english, otherLanguage = spanish), FakeCredentials(TranslationProvider.OPENAI))
        awaitListening()
        Thread.sleep(4_000)

        assertTrue("replacements attempted in four seconds: ${sockets.size - 2}", sockets.size - 2 in 2..8)
        assertEquals(RuntimeState.LISTENING, state.state.value)
    }

    @Test
    fun `an OpenAI session opens, captures at 24 kHz in 200 ms frames, and sends them as translation audio`() {
        server = ::openAi
        start(TranslatorPolicy(english, otherLanguage = spanish), FakeCredentials(TranslationProvider.OPENAI))
        awaitListening()

        assertEquals(setOf("en", "es"), sockets.map(::target).toSet())
        assertEquals(24_000, capture.sampleRateHz)
        assertEquals(200, capture.frameMs)
        capture.hear(TestAudio.tone(200, rateHz = 24_000))
        assertTrue(sockets.all { socket -> socket.sent.last().contains("session.input_audio_buffer.append") })
    }

    @Test
    fun `OpenAI is re-aimed at a new language on the socket it already has`() {
        server = ::openAi
        start(TranslatorPolicy(english), FakeCredentials(TranslationProvider.OPENAI))
        awaitListening()
        val inbound = sockets.single()
        inbound.serve("""{"type":"session.input_transcript.delta","delta":"hola que tal como está usted"}""")
        val outbound = socketFor("es")

        // A second person, speaking French. Said twice before it is followed.
        inbound.serve("""{"type":"session.input_transcript.delta","delta":" bonjour je cherche les"}""")
        inbound.serve("""{"type":"session.input_transcript.delta","delta":" vous êtes avec nous dans cette"}""")
        inbound.serve("""{"type":"session.input_transcript.delta","delta":" merci beaucoup pour les"}""")
        await("the outbound session to be re-aimed") { outbound.sent.any { it.contains("session.update") && it.contains("\"fr\"") } }
        assertEquals("no third socket", 2, sockets.size)
    }

    @Test
    fun `OpenAI being asked for a language it cannot speak is said at the start`() {
        server = ::openAi
        start(TranslatorPolicy(punjabi), FakeCredentials(TranslationProvider.OPENAI))

        await("the failure") { state.lastError.value != null && state.state.value == RuntimeState.IDLE }
        val message = state.lastError.value!!.message
        assertTrue(message, message.contains("OpenAI can't translate into ਪੰਜਾਬੀ"))
        assertTrue(sockets.isEmpty())
    }

    @Test
    fun `an OpenAI error event does not end the session`() {
        server = ::openAi
        start(TranslatorPolicy(english, otherLanguage = spanish), FakeCredentials(TranslationProvider.OPENAI))
        awaitListening()
        sockets.first().serve("""{"type":"error","error":{"message":"Audio buffer was late."}}""")
        Thread.sleep(300)

        assertEquals(RuntimeState.LISTENING, state.state.value)
        assertNull(state.lastError.value)
        assertNotNull(sockets.first().takeIf { !it.closedByApp })
    }
}
