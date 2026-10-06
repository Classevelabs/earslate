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
import com.classeve.earslate.session.TranslationProvider
import com.classeve.earslate.session.TranslatorPolicy
import com.classeve.earslate.testing.FakeCapture
import com.classeve.earslate.testing.FakeFocus
import com.classeve.earslate.testing.FakePlayback
import com.classeve.earslate.ui.captions.CaptionsStore
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The app's own session code against OpenAI's real translation service: real
 * key exchange, real sockets, the real model.
 *
 * Skipped unless EARSLATE_LIVE_OPENAI_KEY is set and EARSLATE_LIVE_AUDIO_DIR
 * holds the two recordings [LiveGeminiSessionTest] leaves there (`es1` and
 * `en1`, 16 kHz PCM16).
 *
 * Written on 2026-10-06 without a key to run it with: it has never been run.
 * What it expects of OpenAI is what OpenAI's reference says, and its numbers
 * are the ones Gemini met. The first run with a key is the first time either
 * is checked.
 */
class LiveOpenAiSessionTest {

    private val key = System.getenv("EARSLATE_LIVE_OPENAI_KEY").orEmpty()
    private val audio = System.getenv("EARSLATE_LIVE_AUDIO_DIR")?.let(::File)
    private val http = OkHttpLiveSocketClient.newHttpClient()

    private val capture = FakeCapture()
    private val playback = FakePlayback()
    private val captions = CaptionsStore()
    private val state = RuntimeStateStore()
    private val seen = CopyOnWriteArrayList<RuntimeState>()
    private var coordinator: SessionCoordinator? = null

    private class OneKey(private val key: String) : SecretStore {
        override fun contains(name: String) = name == "api_key_openai"
        override fun get(name: String) = key.takeIf { name == "api_key_openai" }
        override fun put(name: String, secret: String) = Unit
        override fun remove(name: String) = Unit
        override val wasResetByKeystore = false
        override fun acknowledgeKeystoreReset() = Unit
    }

    private fun recording(name: String): ByteArray? = audio?.let { File(it, "$name.16k.pcm") }?.takeIf { it.isFile }?.readBytes()

    @After
    fun stop() {
        coordinator?.stop()
        await("the session to end", 10_000) { state.state.value == RuntimeState.IDLE }
    }

    private fun await(what: String, timeoutMs: Long, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            watch()
            check(System.currentTimeMillis() < deadline) {
                "timed out waiting for $what (state ${state.state.value}, error ${state.lastError.value?.message}, " +
                    "notice ${state.notice.value})"
            }
            Thread.sleep(20)
        }
    }

    private fun watch() {
        state.state.value.let { if (seen.lastOrNull() != it) seen += it }
    }

    /** 16 kHz to the 24 kHz OpenAI takes: three output samples from every two. */
    private fun to24k(pcm16k: ByteArray): ByteArray {
        val input = ShortArray(pcm16k.size / 2) {
            (((pcm16k[it * 2 + 1].toInt() and 0xff) shl 8) or (pcm16k[it * 2].toInt() and 0xff)).toShort()
        }
        val out = ByteArray(input.size * 3 / 2 * 2)
        for (i in 0 until out.size / 2) {
            val at = i / 1.5
            val base = at.toInt().coerceAtMost(input.size - 2)
            val mix = at - base
            val s = (input[base] * (1 - mix) + input[base + 1] * mix).toInt()
            out[i * 2] = (s and 0xff).toByte()
            out[i * 2 + 1] = ((s shr 8) and 0xff).toByte()
        }
        return out
    }

    /** Speak [pcm24k] into the microphone at real time, in the frames the app asked for. */
    private fun speak(pcm24k: ByteArray) {
        val frameBytes = capture.sampleRateHz * capture.frameMs / 1000 * 2
        val began = System.nanoTime()
        var sent = 0
        var offset = 0
        while (offset < pcm24k.size) {
            capture.hear(pcm24k.copyOfRange(offset, minOf(pcm24k.size, offset + frameBytes)).copyOf(frameBytes))
            offset += frameBytes
            sent++
            watch()
            val wait = (began + sent * capture.frameMs * 1_000_000L - System.nanoTime()) / 1_000_000
            if (wait > 0) Thread.sleep(wait)
        }
    }

    private fun quiet(ms: Int) = speak(ByteArray(ms * 48))

    private fun quietUntil(what: String, timeoutMs: Long = 30_000, condition: () -> Boolean) {
        val began = System.currentTimeMillis()
        while (!condition()) {
            check(System.currentTimeMillis() - began < timeoutMs) {
                "timed out waiting for $what (state ${state.state.value}, error ${state.lastError.value?.message}, " +
                    "notice ${state.notice.value})"
            }
            quiet(200)
        }
    }

    @Test
    fun `a two-way conversation is translated in both directions, with no echo`() {
        assumeTrue("EARSLATE_LIVE_OPENAI_KEY is not set", key.isNotEmpty())
        val spanish = recording("es1")
        val english = recording("en1")
        assumeTrue("EARSLATE_LIVE_AUDIO_DIR does not hold es1 and en1", spanish != null && english != null)

        coordinator = SessionCoordinator(
            credentials = LocalKeyBootstrapRepository(
                keys = ProviderKeyStore(OneKey(key)),
                minter = ProviderSessionMinter(http, installId = "live-test"),
            ),
            socketFactory = { OkHttpLiveSocketClient(http) },
            captureEngine = capture,
            playbackEngine = playback,
            captionsStore = captions,
            stateStore = state,
            audioFocus = FakeFocus(),
            route = MutableStateFlow(AudioRoute.BLUETOOTH),
        ).also { it.start(TranslatorPolicy(TargetLanguage("English", "en-US"), provider = TranslationProvider.OPENAI)) }
        await("the session to listen", 20_000) { state.state.value == RuntimeState.LISTENING && capture.listening }
        assertEquals("OpenAI takes 24 kHz", 24_000, capture.sampleRateHz)

        // They speak Spanish.
        quiet(1_000)
        speak(to24k(spanish!!))
        quietUntil("their translation to be committed") { captions.lines.value.isNotEmpty() }
        quiet(1_000)
        val inbound = playback.written.filter { it.voiced }.groupBy { it.lane }.maxByOrNull { it.value.size }!!.key
        val englishHeard = playback.heardMs(inbound)
        println("LIVE OpenAI Spanish -> English heard $englishHeard ms; captions ${captions.lines.value}")
        assertTrue("their Spanish was translated: $englishHeard ms", englishHeard >= 2_000)
        assertTrue(captions.lines.value.joinToString(" ").lowercase().let { it.contains("train") || it.contains("station") })
        // OpenAI does not name the language it hears; the app works it out from the words.
        assertEquals("their language was recognised", "es-ES", state.heardLanguage.value?.bcp47)

        // I answer in English.
        val linesBefore = captions.lines.value.size
        speak(to24k(english!!))
        quietUntil("my translation to be committed") { captions.lines.value.size > linesBefore }
        quiet(1_000)
        val outbound = playback.written.map { it.lane }.distinct().firstOrNull { it != inbound }
        val spanishHeard = outbound?.let(playback::heardMs) ?: 0
        val echo = playback.heardMs(inbound) - englishHeard
        println("LIVE OpenAI English -> Spanish heard $spanishHeard ms; my own English repeated back: $echo ms")
        println("LIVE OpenAI captions ${captions.lines.value}")
        assertTrue("my English was translated for them: $spanishHeard ms", spanishHeard >= 2_000)
        assertTrue("my own words were not played back to me: $echo ms", echo <= 500)
        assertTrue(captions.lines.value.drop(linesBefore).joinToString(" ").lowercase().let { it.contains("minutos") || it.contains("banco") })

        assertNull(state.lastError.value)
        assertNull(state.notice.value)
        assertFalse("never dropped", RuntimeState.RECONNECTING in seen)
    }
}
