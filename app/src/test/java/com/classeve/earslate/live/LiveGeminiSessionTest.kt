package com.classeve.earslate.live

import com.classeve.earslate.audio.AudioPlaybackEngine
import com.classeve.earslate.audio.AudioRoute
import com.classeve.earslate.bootstrap.LocalKeyBootstrapRepository
import com.classeve.earslate.bootstrap.ProviderSessionMinter
import com.classeve.earslate.security.KeyProvider
import com.classeve.earslate.security.ProviderKeyStore
import com.classeve.earslate.security.SecretStore
import com.classeve.earslate.session.HeardLanguageTracker
import com.classeve.earslate.session.RuntimeState
import com.classeve.earslate.session.RuntimeStateStore
import com.classeve.earslate.session.SessionCoordinator
import com.classeve.earslate.session.SupportedLanguages
import com.classeve.earslate.session.TargetLanguage
import com.classeve.earslate.session.TranslatorPolicy
import com.classeve.earslate.testing.FakeCapture
import com.classeve.earslate.testing.FakeFocus
import com.classeve.earslate.testing.FakePlayback
import com.classeve.earslate.testing.LanePlayback
import com.classeve.earslate.ui.captions.CaptionSide
import com.classeve.earslate.ui.captions.CaptionsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * The app's own session code against the real Gemini API: real key exchange,
 * real sockets, the real model. Only the microphone and loudspeaker are
 * stand-ins — the microphone is fed synthesized speech.
 *
 * Skipped unless EARSLATE_LIVE_GEMINI_KEY is set, so a clean checkout and CI
 * need no key. EARSLATE_LIVE_LONG=1 adds the ten-minute run across the
 * provider's own disconnect.
 */
class LiveGeminiSessionTest {

    private val key = System.getenv("EARSLATE_LIVE_GEMINI_KEY").orEmpty()
    private val http = OkHttpLiveSocketClient.newHttpClient()

    private val capture = FakeCapture()
    private val playback = FakePlayback()
    private val captions = CaptionsStore()
    private val state = RuntimeStateStore()
    private val seen = CopyOnWriteArrayList<RuntimeState>()
    private var coordinator: SessionCoordinator? = null
    private val sockets = CopyOnWriteArrayList<Pair<OkHttpLiveSocketClient, Long>>()
    private val startedAt = System.nanoTime()

    private fun now() = (System.nanoTime() - startedAt) / 1_000_000

    private companion object {
        val TTS_MODELS = listOf("gemini-3.8-flash-tts", "gemini-3.1-flash-tts-preview", "gemini-2.5-flash-preview-tts")

        // A phone's own output path, and the room, between them. EARSLATE_LIVE_ECHO_MS
        // tries another: it is how far that can stretch before the phone hears itself.
        val ECHO_DELAY_MS = System.getenv("EARSLATE_LIVE_ECHO_MS")?.toIntOrNull() ?: 150
        const val KEPT_BYTES = 2_000 * 32
    }

    /** Passes a socket through untouched, noting what the model said it heard and said. */
    private inner class Tapped(private val socket: LiveSocketClient, private val index: Int) : LiveSocketClient by socket {
        private val tapped = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        override val frames: kotlinx.coroutines.channels.ReceiveChannel<String> = tapped

        init {
            Thread {
                kotlinx.coroutines.runBlocking {
                    for (frame in socket.frames) {
                        for (event in GeminiTranslationProtocol.parse(frame)) {
                            when (event) {
                                is LiveEvent.SourceTranscript -> if (event.text.isNotBlank()) {
                                    trace += "${now()} #$index heard[${event.languageCode}] ${event.text}"
                                    sources += Source(now(), event.languageCode.orEmpty(), event.text)
                                }
                                is LiveEvent.CaptionDelta -> trace += "${now()} #$index said ${event.text}"
                                is LiveEvent.GoAway -> trace += "${now()} #$index goAway ${event.timeLeftMs}"
                                else -> Unit
                            }
                        }
                        tapped.trySend(frame)
                    }
                    tapped.close()
                }
            }.start()
        }
    }

    private val trace = CopyOnWriteArrayList<String>()

    /** Something a provider said it heard through the microphone. */
    private class Source(val atMs: Long, val language: String, val text: String)

    private val sources = CopyOnWriteArrayList<Source>()

    /**
     * A room with a loudspeaker in it: whatever the loudspeaker says reaches
     * the microphone a moment later, on top of whoever is speaking.
     */
    private inner class Room {
        private var said = ByteArray(0)

        @Synchronized
        fun sounds(pcm24k: ByteArray) {
            val all = said + to16k(pcm24k)
            said = if (all.size > KEPT_BYTES) all.copyOfRange(all.size - KEPT_BYTES, all.size) else all
        }

        /** What the microphone picks up now: its own [frame], and the loudspeaker as it was [ECHO_DELAY_MS] ago. */
        @Synchronized
        fun reaches(frame: ByteArray): ByteArray {
            val until = said.size - ECHO_DELAY_MS * 32
            val echo = if (until >= frame.size) said.copyOfRange(until - frame.size, until) else return frame
            val out = frame.copyOf()
            for (i in 0 until out.size / 2) {
                fun sample(of: ByteArray) = (((of[i * 2 + 1].toInt() and 0xff) shl 8) or (of[i * 2].toInt() and 0xff)).toShort().toInt()
                val mixed = (sample(out) + sample(echo)).coerceIn(-32768, 32767)
                out[i * 2] = (mixed and 0xff).toByte()
                out[i * 2 + 1] = ((mixed shr 8) and 0xff).toByte()
            }
            return out
        }
    }

    private var room: Room? = null

    private class OneKey(private val key: String) : SecretStore {
        override fun contains(name: String) = name == "api_key_gemini"
        override fun get(name: String) = key.takeIf { name == "api_key_gemini" }
        override fun put(name: String, secret: String) = Unit
        override fun remove(name: String) = Unit
        override val wasResetByKeystore = false
        override fun acknowledgeKeystoreReset() = Unit
    }

    private fun start(
        policy: TranslatorPolicy,
        loudspeaker: AudioPlaybackEngine = playback,
        route: AudioRoute = AudioRoute.BLUETOOTH,
    ) {
        coordinator = SessionCoordinator(
            credentials = LocalKeyBootstrapRepository(
                keys = ProviderKeyStore(OneKey(key)),
                minter = ProviderSessionMinter(http, installId = "live-test"),
            ),
            socketFactory = { Tapped(OkHttpLiveSocketClient(http).also { sockets += it to now() }, sockets.size) },
            captureEngine = capture,
            playbackEngine = loudspeaker,
            captionsStore = captions,
            stateStore = state,
            audioFocus = FakeFocus(),
            route = MutableStateFlow(route),
            now = ::now,
        ).also { it.start(policy) }
        await("the session to listen", 15_000) { state.state.value == RuntimeState.LISTENING && capture.listening }
    }

    @After
    fun stop() {
        println("LIVE sockets at the end: ${sockets.map { (socket, at) -> "at $at ms ${socket.state.value} ${socket.closure}" }}")
        trace.forEach { println("LIVE trace $it") }
        coordinator?.stop()
        await("the session to end", 10_000) { state.state.value == RuntimeState.IDLE }
    }

    private fun await(what: String, timeoutMs: Long, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            watch()
            check(System.currentTimeMillis() < deadline) {
                "timed out waiting for $what (state ${state.state.value}, error ${state.lastError.value?.message})"
            }
            Thread.sleep(20)
        }
    }

    private fun watch() {
        state.state.value.let { if (seen.lastOrNull() != it) seen += it }
    }

    /**
     * Speech for the microphone, as 16 kHz PCM16. Kept between runs in
     * EARSLATE_LIVE_AUDIO_DIR when that is set, because text-to-speech has a
     * small daily allowance.
     */
    private fun say(name: String, text: String, voice: String): ByteArray {
        val kept = System.getenv("EARSLATE_LIVE_AUDIO_DIR")?.let { java.io.File(it, "$name.16k.pcm") }
        if (kept != null && kept.isFile) return kept.readBytes()
        return synthesize(text, voice).also { kept?.writeBytes(it) }
    }

    private fun synthesize(text: String, voice: String): ByteArray {
        val body = JSONObject()
            .put("contents", listOf(mapOf("parts" to listOf(mapOf("text" to text)))).let(::toJson))
            .put(
                "generationConfig",
                JSONObject()
                    .put("responseModalities", org.json.JSONArray().put("AUDIO"))
                    .put(
                        "speechConfig",
                        JSONObject().put(
                            "voiceConfig",
                            JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", voice)),
                        ),
                    ),
            )
        val client = OkHttpClient.Builder().callTimeout(90, TimeUnit.SECONDS).build()
        var refused = ""
        val reply = TTS_MODELS.firstNotNullOfOrNull { model ->
            val request = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
                .header("x-goog-api-key", key)
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(request).execute().use {
                if (it.isSuccessful) JSONObject(it.body!!.string()) else null.also { _ -> refused += " $model:${it.code}" }
            }
        } ?: error("text-to-speech failed:$refused")
        val part = reply.getJSONArray("candidates").getJSONObject(0).getJSONObject("content")
            .getJSONArray("parts").getJSONObject(0).getJSONObject("inlineData")
        return to16k(pcm24k(part.getString("mimeType"), Base64.getDecoder().decode(part.getString("data"))))
    }

    /** The reply is either bare 24 kHz PCM or a WAV file around it, depending on the model. */
    private fun pcm24k(mimeType: String, audio: ByteArray): ByteArray {
        if (!mimeType.contains("wav")) {
            check(mimeType.contains("rate=24000")) { mimeType }
            return audio
        }
        fun int(at: Int) = (0..3).sumOf { (audio[at + it].toInt() and 0xff) shl (8 * it) }
        check(String(audio, 0, 4, Charsets.US_ASCII) == "RIFF" && int(24) == 24_000) { "unexpected WAV" }
        var at = 12
        while (String(audio, at, 4, Charsets.US_ASCII) != "data") at += 8 + int(at + 4)
        return audio.copyOfRange(at + 8, minOf(audio.size, at + 8 + int(at + 4)))
    }

    private fun toJson(value: Any?): Any = when (value) {
        is Map<*, *> -> JSONObject().also { json -> value.forEach { (k, v) -> json.put(k as String, toJson(v)) } }
        is List<*> -> org.json.JSONArray().also { json -> value.forEach { json.put(toJson(it)) } }
        else -> value!!
    }

    /** 24 kHz to 16 kHz: each pair of output samples is taken from three input samples. */
    private fun to16k(pcm24k: ByteArray): ByteArray {
        val input = ShortArray(pcm24k.size / 2) {
            (((pcm24k[it * 2 + 1].toInt() and 0xff) shl 8) or (pcm24k[it * 2].toInt() and 0xff)).toShort()
        }
        val out = ByteArray(input.size * 2 / 3 * 2)
        for (i in 0 until out.size / 2) {
            val at = i * 1.5
            val base = at.toInt().coerceAtMost(input.size - 2)
            val mix = at - base
            // A neighbour-weighted average: enough of a low-pass for speech into a recogniser.
            val s = (input[base] * (1 - mix) + input[base + 1] * mix).toInt()
            out[i * 2] = (s and 0xff).toByte()
            out[i * 2 + 1] = ((s shr 8) and 0xff).toByte()
        }
        return out
    }

    /** Speak [pcm] into the microphone at real time, in the 100 ms frames the app captures. */
    private fun speak(pcm: ByteArray) {
        val frameBytes = 3_200
        val began = System.nanoTime()
        var sent = 0
        var offset = 0
        while (offset < pcm.size) {
            val frame = pcm.copyOfRange(offset, minOf(pcm.size, offset + frameBytes)).copyOf(frameBytes)
            capture.hear(room?.reaches(frame) ?: frame)
            offset += frameBytes
            sent++
            watch()
            val due = began + sent * 100_000_000L
            val wait = (due - System.nanoTime()) / 1_000_000
            if (wait > 0) Thread.sleep(wait)
        }
    }

    private fun quiet(ms: Int) = speak(ByteArray(ms * 32))

    /**
     * Keep the room silent until [condition] holds. The model answers a few
     * seconds behind and a poor network adds to that, so nothing is asserted
     * at a fixed moment.
     * @return how long it took.
     */
    private fun quietUntil(what: String, timeoutMs: Long = 20_000, condition: () -> Boolean): Long {
        val began = System.currentTimeMillis()
        while (!condition()) {
            check(System.currentTimeMillis() - began < timeoutMs) {
                "timed out waiting for $what (state ${state.state.value}, error ${state.lastError.value?.message})"
            }
            quiet(100)
        }
        return System.currentTimeMillis() - began
    }

    private fun heardMs(lane: Int) = playback.heardMs(lane)

    @Test
    fun `a two-way conversation is translated in both directions, with no echo`() {
        assumeTrue("EARSLATE_LIVE_GEMINI_KEY is not set", key.isNotEmpty())
        val spanish = say("es1", "Hola, buenos días. ¿Me puede decir dónde está la estación de tren más cercana? Necesito llegar antes de las cinco.", "Puck")
        val english = say("en1", "Sure. It is about ten minutes from here. Go straight down this street and turn left at the bank.", "Kore")

        val began = System.currentTimeMillis()
        start(TranslatorPolicy(TargetLanguage("English", "en-US")))
        println("LIVE start to listening: ${System.currentTimeMillis() - began} ms")
        println("LIVE sockets opened to get there: ${sockets.map { (socket, at) -> "at $at ms ${socket.state.value} ${socket.closure}" }}")

        // They speak Spanish.
        quiet(1_000)
        val spanishFrom = playback.written.size
        val spokeAt = System.currentTimeMillis()
        Thread { await("the first translated audio", 20_000) { playback.written.any { it.voiced } }; println("LIVE first translated audio after ${System.currentTimeMillis() - spokeAt} ms") }.start()
        speak(spanish)
        val finished = quietUntil("their translation to be committed") { captions.settled().isNotEmpty() }
        println("LIVE their translation was finished and committed $finished ms after they stopped speaking")
        quiet(1_000)

        val lanesAfterSpanish = playback.written.map { it.lane }.distinct()
        val inbound = playback.written.drop(spanishFrom).filter { it.voiced }.groupBy { it.lane }.maxByOrNull { it.value.size }!!.key
        val englishHeard = heardMs(inbound)
        println("LIVE Spanish ${spanish.size / 32} ms -> English heard $englishHeard ms; captions ${captions.settled()}")
        assertTrue("their Spanish was translated: $englishHeard ms", englishHeard >= 2_000)
        assertTrue("captions were committed: ${captions.settled()}", captions.settled().isNotEmpty())
        assertTrue(captions.settled().joinToString(" ").lowercase().let { it.contains("train") || it.contains("station") })
        assertEquals("their language was recognised", "es-ES", state.heardLanguage.value?.bcp47)
        await("the direction back to them", 5_000) { playback.written.map { it.lane }.distinct().size >= 2 || lanesAfterSpanish.size >= 2 }

        // I answer in English.
        val linesBefore = captions.settled().size
        speak(english)
        val answered = quietUntil("my translation to be committed") { captions.settled().size > linesBefore }
        println("LIVE my translation was finished and committed $answered ms after I stopped speaking")
        quiet(1_000)

        val outbound = playback.written.map { it.lane }.distinct().first { it != inbound }
        val spanishHeard = heardMs(outbound)
        val echo = heardMs(inbound) - englishHeard
        println("LIVE English ${english.size / 32} ms -> Spanish heard $spanishHeard ms; my own English repeated back: $echo ms")
        println("LIVE captions ${captions.settled()}")
        assertTrue("my English was translated for them: $spanishHeard ms", spanishHeard >= 2_000)
        assertTrue("my own words were not played back to me: $echo ms", echo <= 500)
        assertTrue(captions.settled().size > linesBefore)
        assertTrue(captions.settled().drop(linesBefore).joinToString(" ").lowercase().let { it.contains("minutos") || it.contains("banco") })

        assertNull(state.lastError.value)
        assertFalse("never dropped", RuntimeState.RECONNECTING in seen)
    }

    // On a loudspeaker the microphone hears the phone. Whatever the phone
    // says must stay in the room: sent on, it would be translated back, and
    // that translation heard and translated again, without end.
    @Test
    fun `on a loudspeaker the phone takes turns and never translates its own voice`() {
        assumeTrue("EARSLATE_LIVE_GEMINI_KEY is not set", key.isNotEmpty())
        val spanish = say("es1", "Hola, buenos días. ¿Me puede decir dónde está la estación de tren más cercana? Necesito llegar antes de las cinco.", "Puck")
        val more = say("es2", "Muchas gracias. ¿Y sabe si hay algún restaurante bueno cerca de la estación?", "Puck")
        val english = say("en1", "Sure. It is about ten minutes from here. Go straight down this street and turn left at the bank.", "Kore")

        val loudspeaker = LanePlayback()
        val air = Room().also { room = it }
        loudspeaker.onSound = { sound, _ -> air.sounds(sound) }
        fun words(language: String, fromMs: Long, toMs: Long = Long.MAX_VALUE) = sources
            .filter { it.atMs in fromMs until toMs && HeardLanguageTracker.sameLanguage(it.language, language) }
            .joinToString(" ") { it.text.trim() }.split(' ').filter { it.isNotBlank() }
        fun turnOver(what: String) = quietUntil("$what, and the phone to listen again", 40_000) {
            val now = loudspeaker.snapshot()
            state.state.value == RuntimeState.LISTENING && now.waitingMs == 0 && !now.audible
        }

        try {
            start(TranslatorPolicy(TargetLanguage("English", "en-US")), loudspeaker, AudioRoute.SPEAKER)
            quiet(1_000)

            // They speak Spanish, twice. The second time proves the microphone opened again.
            speak(spanish)
            quietUntil("their translation to be said", 40_000) { loudspeaker.totalHeardMs >= 2_000 }
            turnOver("their translation to end")
            quiet(3_000)
            val afterFirst = loudspeaker.totalHeardMs
            println("LIVE speaker: first translation said, ${afterFirst} ms; captions ${captions.settled()}")

            speak(more)
            quietUntil("their second translation to be said", 40_000) { loudspeaker.totalHeardMs - afterFirst >= 1_500 }
            turnOver("their second translation to end")
            quiet(3_000)
            val afterSecond = loudspeaker.totalHeardMs
            val answeredAt = now()
            val englishSaid = captions.settled().joinToString(" ").lowercase()
            println("LIVE speaker: second translation said, ${afterSecond - afterFirst} ms; captions ${captions.settled()}")

            assertTrue("the first was translated: $englishSaid", englishSaid.contains("train") || englishSaid.contains("station"))
            assertTrue("and the second: $englishSaid", englishSaid.contains("restaurant"))
            val ownEnglish = words("en", 0, answeredAt)
            assertTrue("the phone's English never reached the provider: $ownEnglish", ownEnglish.size < 3)
            assertEquals("their language was recognised", "es-ES", state.heardLanguage.value?.bcp47)

            // I answer in English.
            speak(english)
            quietUntil("my answer to be said for them", 40_000) { loudspeaker.totalHeardMs - afterSecond >= 2_000 }
            turnOver("my answer to end")
            quiet(5_000)
            val spanishSaid = captions.settled().joinToString(" ").lowercase()
            println("LIVE speaker: answer said, ${loudspeaker.totalHeardMs - afterSecond} ms; captions ${captions.settled()}")

            assertTrue("my answer was translated for them: $spanishSaid", spanishSaid.contains("minutos") || spanishSaid.contains("banco"))
            val ownSpanish = words("es", answeredAt)
            assertTrue("the phone's Spanish never reached the provider: $ownSpanish", ownSpanish.size < 3)
            // Nothing set the phone talking again once the conversation had stopped.
            val settled = loudspeaker.totalHeardMs
            quiet(6_000)
            assertEquals("the phone said nothing more on its own", settled, loudspeaker.totalHeardMs)

            assertNull(state.lastError.value)
            assertNull(state.notice.value)
            assertFalse("never dropped", RuntimeState.RECONNECTING in seen)
            assertEquals(1, capture.starts.get())
            assertEquals("nothing was dropped from a lane", 0, loudspeaker.snapshot().droppedMs)
        } finally {
            room = null
            loudspeaker.shutDown()
        }
    }

    // A language Google stops accepting, or renames, is otherwise found by
    // the first person who picks it.
    @Test
    fun `every language in the pickers opens a session`() {
        assumeTrue("EARSLATE_LIVE_GEMINI_KEY is not set", key.isNotEmpty())
        val credential = runBlocking { ProviderSessionMinter(http, installId = "live-test").mint(KeyProvider.GEMINI, key) }
        val refused = SupportedLanguages.mapNotNull { language ->
            val wire = GeminiTranslationProtocol.wireLanguage(language.bcp47)
                ?: return@mapNotNull "${language.bcp47}: no code for it"
            val link = ProviderLink(credential, GeminiTranslationProtocol, OkHttpLiveSocketClient(http))
            try {
                runBlocking { link.open(wire) }
                null
            } catch (failure: LinkFailure) {
                "${language.bcp47} as $wire: ${failure.message}"
            } finally {
                link.close()
            }
        }
        println("LIVE languages opened: ${SupportedLanguages.size - refused.size} of ${SupportedLanguages.size}")
        assertEquals(emptyList<String>(), refused)
    }

    // What I say goes out in whatever the other person was last heard
    // speaking. Before anyone has been heard there is no such language, and
    // guessing one sends my words out in a language nobody present speaks.
    @Test
    fun `my words go out in the language they were last heard speaking, and in none before`() {
        assumeTrue("EARSLATE_LIVE_GEMINI_KEY is not set", key.isNotEmpty())
        val punjabi = say("pa1", "ਠੀਕ ਹੈ। ਇੱਥੋਂ ਲਗਭਗ ਦਸ ਮਿੰਟ ਲੱਗਦੇ ਹਨ। ਇਸ ਸੜਕ 'ਤੇ ਸਿੱਧੇ ਜਾਓ ਅਤੇ ਬੈਂਕ ਕੋਲੋਂ ਖੱਬੇ ਮੁੜੋ।", "Puck")
        val mixed = say("pa_mix", "ਮੈਂ ਕੱਲ੍ਹ office ਜਾ ਰਿਹਾ ਹਾਂ ਕਿਉਂਕਿ meeting ਦਸ ਵਜੇ ਹੈ, ਅਤੇ ਮੈਨੂੰ report ਵੀ submit ਕਰਨੀ ਹੈ।", "Puck")
        val english = say("en1", "Sure. It is about ten minutes from here. Go straight down this street and turn left at the bank.", "Kore")
        // A third person, in a voice of their own. One voice that changes
        // language is heard by the model as the language it spoke before.
        val chinese = say("zh2", "你好，请问最近的地铁站在哪里？我想去市中心，大概要多长时间？", "Charon")
        fun said(side: CaptionSide) = captions.captions.value.filter { it.side == side && !it.live }.map { it.text }
        // The session that listens for them is the first one opened, and its lane the first.
        val forMe = 1
        fun toMe() = playback.written.sumOf { if (it.voiced && it.lane == forMe) it.ms else 0 }
        fun toThem() = playback.written.sumOf { if (it.voiced && it.lane != forMe) it.ms else 0 }
        fun String.has(script: Character.UnicodeScript) = any { Character.UnicodeScript.of(it.code) == script }

        start(TranslatorPolicy(TargetLanguage("ਪੰਜਾਬੀ", "pa-IN")))
        quiet(1_000)

        // I speak first. Nobody else has been heard.
        speak(punjabi)
        quiet(7_000)
        println("LIVE direction: before anyone is heard: sockets ${sockets.size}, to them ${toThem()} ms, back to me ${toMe()} ms, captions ${captions.captions.value.map { it.side to it.text }}")
        assertEquals("no direction is open for what I say", 1, sockets.size)
        assertEquals("and nothing is said for me", 0, toThem())
        assertTrue("nor written as mine: ${said(CaptionSide.MINE)}", said(CaptionSide.MINE).isEmpty())
        assertNull("no language has been taken for theirs", state.heardLanguage.value)

        // They speak English: it comes to me in Punjabi, and English is now their language.
        var theirs = said(CaptionSide.THEIRS).size
        var heardBefore = toMe()
        speak(english)
        quietUntil("their English to reach me in Punjabi") { said(CaptionSide.THEIRS).size > theirs }
        quiet(1_500)
        println("LIVE direction: their English -> ${said(CaptionSide.THEIRS).drop(theirs)} (${toMe() - heardBefore} ms)")
        assertTrue("their English reached me in Punjabi", said(CaptionSide.THEIRS).last().has(Character.UnicodeScript.GURMUKHI))
        assertTrue("and was spoken: ${toMe() - heardBefore} ms", toMe() - heardBefore >= 2_000)
        assertEquals("en-US", state.heardLanguage.value?.bcp47)

        // Now what I say goes out in English: a plain sentence, then one with English words in it.
        for ((name, sentence) in listOf("plain" to punjabi, "mixed with English" to mixed)) {
            val mine = said(CaptionSide.MINE).size
            val sentBefore = toThem()
            heardBefore = toMe()
            speak(sentence)
            quietUntil("my Punjabi ($name) to go out in English") { said(CaptionSide.MINE).size > mine }
            quiet(2_500)
            val out = said(CaptionSide.MINE).drop(mine)
            println("LIVE direction: my Punjabi ($name) -> $out (${toThem() - sentBefore} ms to them, ${toMe() - heardBefore} ms back to me)")
            assertTrue("my Punjabi ($name) went out in English: $out", out.all { it.has(Character.UnicodeScript.LATIN) && !it.has(Character.UnicodeScript.GURMUKHI) })
            assertTrue("and was spoken: ${toThem() - sentBefore} ms", toThem() - sentBefore >= 2_000)
            assertEquals("their language is still English", "en-US", state.heardLanguage.value?.bcp47)
        }

        // Somebody speaks Chinese: it comes to me in Punjabi, and Chinese is now their language.
        theirs = said(CaptionSide.THEIRS).size
        var sentBefore = toThem()
        speak(chinese)
        quietUntil("their Chinese to reach me in Punjabi") { said(CaptionSide.THEIRS).size > theirs }
        quietUntil("Chinese to be the language they speak") { state.heardLanguage.value?.bcp47?.startsWith("zh") == true }
        quiet(1_500)
        println("LIVE direction: their Chinese -> ${said(CaptionSide.THEIRS).drop(theirs)}; heard ${state.heardLanguage.value?.bcp47}; also said in English for ${toThem() - sentBefore} ms")
        assertTrue("their Chinese reached me in Punjabi", said(CaptionSide.THEIRS).last().has(Character.UnicodeScript.GURMUKHI))
        assertTrue("it was not also put into English: ${toThem() - sentBefore} ms", toThem() - sentBefore <= 1_000)

        // And now what I say goes out in Chinese.
        val mine = said(CaptionSide.MINE).size
        sentBefore = toThem()
        speak(punjabi)
        quietUntil("my Punjabi to go out in Chinese") { said(CaptionSide.MINE).size > mine }
        quiet(2_500)
        val out = said(CaptionSide.MINE).drop(mine)
        println("LIVE direction: my Punjabi -> $out (${toThem() - sentBefore} ms)")
        assertTrue("my Punjabi went out in Chinese: $out", out.all { it.has(Character.UnicodeScript.HAN) })
        assertTrue("and was spoken: ${toThem() - sentBefore} ms", toThem() - sentBefore >= 2_000)

        assertNull(state.lastError.value)
        assertNull(state.notice.value)
        assertFalse("never dropped", RuntimeState.RECONNECTING in seen)
    }

    @Test
    fun `the provider's own ten-minute disconnect passes unnoticed`() {
        assumeTrue("EARSLATE_LIVE_GEMINI_KEY is not set", key.isNotEmpty())
        assumeTrue("EARSLATE_LIVE_LONG is not set", System.getenv("EARSLATE_LIVE_LONG") == "1")
        val spanish = say("es2", "Muchas gracias. ¿Y sabe si hay algún restaurante bueno cerca de la estación?", "Puck")

        start(TranslatorPolicy(TargetLanguage("English", "en-US"), otherLanguage = TargetLanguage("Español", "es-ES")))
        fun translatedMs() = playback.written.sumOf { if (it.voiced) it.ms else 0 }
        val heard = ArrayList<Int>()
        val finishedAfter = ArrayList<Long>()
        val until = System.currentTimeMillis() + 11 * 60_000L
        while (System.currentTimeMillis() < until) {
            val before = translatedMs()
            speak(spanish)
            val stopped = System.currentTimeMillis()
            quietUntil("the translation to be heard") { translatedMs() - before >= 1_500 }
            // It has finished once nothing more is heard for a second and a half.
            var total = translatedMs()
            var lastHeardAt = System.currentTimeMillis()
            while (System.currentTimeMillis() - lastHeardAt < 1_500) {
                quiet(100)
                val now = translatedMs()
                if (now != total) {
                    total = now
                    lastHeardAt = System.currentTimeMillis()
                }
                check(lastHeardAt - stopped < 30_000) { "the translation never finished" }
            }
            heard += total - before
            finishedAfter += lastHeardAt - stopped
            println("LIVE minute ${now() / 60_000}: translated ${heard.last()} ms, finished ${finishedAfter.last()} ms after they stopped, lanes ${playback.written.map { it.lane }.distinct().size}")
        }

        fun median(values: List<Long>) = values.sorted()[values.size / 2]
        val early = median(finishedAfter.take(10))
        val late = median(finishedAfter.takeLast(10))
        println("LIVE finished after they stopped: median $early ms in the first ten utterances, $late ms in the last ten")
        assertTrue("every utterance was translated, before and after the disconnect: $heard", heard.all { it >= 1_500 })
        assertTrue("the sockets were replaced: lanes ${playback.written.map { it.lane }.distinct()}", playback.written.map { it.lane }.distinct().size >= 4)
        assertTrue("the translation fell no further behind as the session went on: $early ms, then $late ms", late <= early + 1_500)
        assertFalse("the user never saw a reconnect: $seen", RuntimeState.RECONNECTING in seen)
        assertEquals(1, capture.starts.get())
        assertNull(state.lastError.value)
    }
}
