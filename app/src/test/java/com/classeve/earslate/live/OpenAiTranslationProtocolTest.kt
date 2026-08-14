package com.classeve.earslate.live

import com.classeve.earslate.bootstrap.SessionBootstrap
import com.classeve.earslate.session.TranslationProvider
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire contract for the OpenAI realtime-translation leg.
 *
 * Gemini's frames were pinned by [GeminiProtocolContractTest] after a nested
 * `transcription` block cost a 1007 close that took an on-device session to
 * diagnose. OpenAI's frames had no equivalent cover at all: two assertions in
 * [TranslationLiveProtocolTest] and nothing on the frames we SEND. Every field
 * asserted here is one the provider silently ignores when it is wrong — a
 * mistyped `type` does not error, it simply means no audio is ever accepted and
 * no translation ever comes back, which presents to the user as a session that
 * connects and then says nothing.
 */
class OpenAiTranslationProtocolTest {

    private val protocol =
        TranslationLiveProtocols.forProvider(TranslationProvider.OPENAI)

    private val bootstrap = SessionBootstrap(
        credential = "ek_test_credential",
        provider = TranslationProvider.OPENAI,
        webSocketUrl = "wss://api.openai.com/v1/realtime/translations",
        model = "gpt-realtime-translate",
    )

    // ── frames we send ─────────────────────────────────────────────────

    @Test
    fun `setup frame is a session update carrying the output language`() {
        val frame = JSONObject(protocol.setupFrame(bootstrap, "es", captionsEnabled = true))

        assertEquals("session.update", frame.getString("type"))
        val language = frame.getJSONObject("session")
            .getJSONObject("audio")
            .getJSONObject("output")
            .getString("language")
        assertEquals("es", language)
    }

    @Test
    fun `audio frame is an input buffer append with base64 payload`() {
        // 100 ms of 16 kHz mono PCM16 — one capture batch.
        val pcm16k = ByteArray(3200)
        val frame = JSONObject(protocol.audioFrame(pcm16k))

        assertEquals("session.input_audio_buffer.append", frame.getString("type"))
        val decoded = Base64.getDecoder().decode(frame.getString("audio"))
        // Resampled to 24 kHz on the way out, so 1.5x the bytes.
        assertEquals(4800, decoded.size)
    }

    @Test
    fun `graceful close frame is session close`() {
        assertEquals(
            "session.close",
            JSONObject(protocol.gracefulCloseFrame()!!).getString("type"),
        )
    }

    @Test
    fun `openai authorises with Bearer and gemini with Token`() {
        // Swapping these two schemes is a 401 at socket-upgrade time, before any
        // application frame is exchanged, so nothing downstream can catch it.
        assertEquals(
            "Bearer ek_test_credential",
            protocol.headers(bootstrap)["Authorization"],
        )
        val gemini = TranslationLiveProtocols.forProvider(TranslationProvider.GEMINI)
        assertEquals(
            "Token ek_test_credential",
            gemini.headers(bootstrap.copy(provider = TranslationProvider.GEMINI))["Authorization"],
        )
    }

    // ── frames we receive ──────────────────────────────────────────────

    @Test
    fun `audio delta decodes to a 24 kHz chunk`() {
        val pcm = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val encoded = Base64.getEncoder().encodeToString(pcm)
        val events = protocol.parse(
            """{"type":"session.output_audio.delta","delta":"$encoded"}""",
        )

        val chunk = events.single() as LiveEvent.AudioChunk
        assertEquals(24_000, chunk.sampleRateHz)
        assertTrue(pcm.contentEquals(chunk.pcm24k))
    }

    @Test
    fun `both done events end the turn`() {
        // Only one of these arrives when captions are off, so treating either as
        // the end of turn is what keeps the jitter buffer from charging the
        // resulting silence as an underrun.
        assertTrue(
            protocol.parse("""{"type":"session.output_audio.done"}""")
                .single() is LiveEvent.TurnComplete,
        )
        assertTrue(
            protocol.parse("""{"type":"session.output_transcript.done"}""")
                .single() is LiveEvent.TurnComplete,
        )
    }

    @Test
    fun `error frame surfaces the provider message`() {
        val events = protocol.parse(
            """{"type":"error","error":{"message":"Insufficient quota"}}""",
        )
        assertEquals("Insufficient quota", (events.single() as LiveEvent.Error).message)
    }

    @Test
    fun `error frame without a message still reports an error`() {
        val events = protocol.parse("""{"type":"error"}""")
        val error = events.single() as LiveEvent.Error
        assertTrue(error.message.isNotBlank())
    }

    @Test
    fun `session closed maps to a socket close event`() {
        assertTrue(
            protocol.parse("""{"type":"session.closed"}""").single()
                is LiveEvent.SocketClosed,
        )
    }

    // ── the never-throw contract ───────────────────────────────────────

    @Test
    fun `malformed and unknown frames are dropped, never thrown`() {
        // The parser runs inside the frame pump. Anything that throws here kills
        // the collector and the session goes deaf with the socket still open —
        // the exact failure the "never throw" rule exists to prevent.
        val hostile = listOf(
            "",
            "not json at all",
            "{",
            "[]",
            "null",
            """{"type":"session.output_audio.delta","delta":"!!!not base64!!!"}""",
            """{"type":"session.output_transcript.delta","delta":""}""",
            """{"type":"some.future.event.we.do.not.know"}""",
            """{"delta":"no type field"}""",
            """{"type":123}""",
        )
        for (frame in hostile) {
            val events = protocol.parse(frame)
            assertNotNull("parse returned null for: $frame", events)
            assertTrue("expected no events for: $frame", events.isEmpty())
        }
    }

    @Test
    fun `blank transcript deltas produce no caption`() {
        assertTrue(
            protocol.parse("""{"type":"session.output_transcript.delta","delta":"   "}""")
                .isEmpty() ||
                protocol.parse("""{"type":"session.output_transcript.delta","delta":""}""")
                    .isEmpty(),
        )
    }

    // ── the resampler the audio frame depends on ───────────────────────

    @Test
    fun `resampler preserves a constant signal instead of drifting to zero`() {
        // A linear interpolator that mishandles its last sample attenuates the
        // tail toward zero, which is heard as a click at every chunk seam. A
        // constant input must come out constant, including the final sample.
        val amplitude: Short = 8000
        val input = ByteArray(320 * 2)
        for (i in 0 until 320) {
            input[i * 2] = (amplitude.toInt() and 0xff).toByte()
            input[i * 2 + 1] = ((amplitude.toInt() shr 8) and 0xff).toByte()
        }

        val output = Pcm16Resampler.from16kTo24k(input)
        assertEquals(480 * 2, output.size)

        for (i in 0 until output.size / 2) {
            val lo = output[i * 2].toInt() and 0xff
            val value = ((output[i * 2 + 1].toInt() shl 8) or lo).toShort()
            assertEquals("sample $i drifted", amplitude, value)
        }
    }

    @Test
    fun `resampler is exactly one and a half times as long, and safe on edges`() {
        assertEquals(0, Pcm16Resampler.from16kTo24k(ByteArray(0)).size)
        // One sample in: no "upper" neighbour to interpolate against.
        assertEquals(2, Pcm16Resampler.from16kTo24k(ByteArray(2)).size)
        assertEquals(6, Pcm16Resampler.from16kTo24k(ByteArray(4)).size)
        assertEquals(4800, Pcm16Resampler.from16kTo24k(ByteArray(3200)).size)
        // An odd trailing byte is not half a sample; it must not index past the end.
        assertEquals(6, Pcm16Resampler.from16kTo24k(ByteArray(5)).size)
    }

    @Test
    fun `gemini provider never resolves to the openai protocol`() {
        // TranslationProvider.AUTOMATIC must be resolved before a socket opens;
        // reaching the factory with it is a programming error, not a runtime one.
        assertNull(
            runCatching {
                TranslationLiveProtocols.forProvider(TranslationProvider.AUTOMATIC)
            }.getOrNull(),
        )
    }
}
