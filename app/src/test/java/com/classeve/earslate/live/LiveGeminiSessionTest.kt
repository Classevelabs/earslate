package com.classeve.earslate.live

import com.classeve.earslate.audio.AudioRoute
import com.classeve.earslate.bootstrap.LocalKeyBootstrapRepository
import com.classeve.earslate.bootstrap.ProviderSessionMinter
import com.classeve.earslate.security.ProviderKeyStore
import com.classeve.earslate.security.SecretStore
import com.classeve.earslate.session.RuntimeState
import com.classeve.earslate.session.RuntimeStateStore
import com.classeve.earslate.session.SessionCoordinator
import com.classeve.earslate.session.TargetLanguage
import com.classeve.earslate.session.TranslatorPolicy
import com.classeve.earslate.testing.FakeCapture
import com.classeve.earslate.testing.FakeFocus
import com.classeve.earslate.testing.FakePlayback
import com.classeve.earslate.ui.captions.CaptionsStore
import kotlinx.coroutines.flow.MutableStateFlow
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
                                is LiveEvent.SourceTranscript -> if (event.text.isNotBlank()) trace += "${now()} #$index heard[${event.languageCode}] ${event.text}"
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

    private class OneKey(private val key: String) : SecretStore {
        override fun contains(name: String) = name == "api_key_gemini"
        override fun get(name: String) = key.takeIf { name == "api_key_gemini" }
        override fun put(name: String, secret: String) = Unit
        override fun remove(name: String) = Unit
        override val wasResetByKeystore = false
        override fun acknowledgeKeystoreReset() = Unit
    }

    private fun start(policy: TranslatorPolicy) {
        coordinator = SessionCoordinator(
            credentials = LocalKeyBootstrapRepository(
                keys = ProviderKeyStore(OneKey(key)),
                minter = ProviderSessionMinter(http, installId = "live-test"),
            ),
            socketFactory = { Tapped(OkHttpLiveSocketClient(http).also { sockets += it to now() }, sockets.size) },
            captureEngine = capture,
            playbackEngine = playback,
            captionsStore = captions,
            stateStore = state,
            audioFocus = FakeFocus(),
            route = MutableStateFlow(AudioRoute.BLUETOOTH),
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
            capture.hear(pcm.copyOfRange(offset, minOf(pcm.size, offset + frameBytes)).copyOf(frameBytes))
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
        val finished = quietUntil("their translation to be committed") { captions.lines.value.isNotEmpty() }
        println("LIVE their translation was finished and committed $finished ms after they stopped speaking")
        quiet(1_000)

        val lanesAfterSpanish = playback.written.map { it.lane }.distinct()
        val inbound = playback.written.drop(spanishFrom).filter { it.voiced }.groupBy { it.lane }.maxByOrNull { it.value.size }!!.key
        val englishHeard = heardMs(inbound)
        println("LIVE Spanish ${spanish.size / 32} ms -> English heard $englishHeard ms; captions ${captions.lines.value}")
        assertTrue("their Spanish was translated: $englishHeard ms", englishHeard >= 2_000)
        assertTrue("captions were committed: ${captions.lines.value}", captions.lines.value.isNotEmpty())
        assertTrue(captions.lines.value.joinToString(" ").lowercase().let { it.contains("train") || it.contains("station") })
        assertEquals("their language was recognised", "es-ES", state.heardLanguage.value?.bcp47)
        await("the direction back to them", 5_000) { playback.written.map { it.lane }.distinct().size >= 2 || lanesAfterSpanish.size >= 2 }

        // I answer in English.
        val linesBefore = captions.lines.value.size
        speak(english)
        val answered = quietUntil("my translation to be committed") { captions.lines.value.size > linesBefore }
        println("LIVE my translation was finished and committed $answered ms after I stopped speaking")
        quiet(1_000)

        val outbound = playback.written.map { it.lane }.distinct().first { it != inbound }
        val spanishHeard = heardMs(outbound)
        val echo = heardMs(inbound) - englishHeard
        println("LIVE English ${english.size / 32} ms -> Spanish heard $spanishHeard ms; my own English repeated back: $echo ms")
        println("LIVE captions ${captions.lines.value}")
        assertTrue("my English was translated for them: $spanishHeard ms", spanishHeard >= 2_000)
        assertTrue("my own words were not played back to me: $echo ms", echo <= 500)
        assertTrue(captions.lines.value.size > linesBefore)
        assertTrue(captions.lines.value.drop(linesBefore).joinToString(" ").lowercase().let { it.contains("minutos") || it.contains("banco") })

        assertNull(state.lastError.value)
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
