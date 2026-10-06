package com.classeve.earslate.live

import com.classeve.earslate.bootstrap.SessionCredential
import com.classeve.earslate.session.TranslationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * The OpenAI translation wire, pinned against OpenAI's API reference for
 * Realtime translation sessions: the client events it accepts and the seven
 * server events it sends.
 */
class OpenAiTranslationProtocolTest {

    private val protocol = OpenAiTranslationProtocol
    private val credential = SessionCredential(
        provider = TranslationProvider.OPENAI,
        secret = "ek_test",
        webSocketUrl = "wss://example.invalid",
        model = "gpt-realtime-translate",
        expiresAtMs = Long.MAX_VALUE,
        safetyIdentifier = "earslate_abc",
    )

    @Test
    fun `the first frame sets the language and asks for the source transcript`() {
        val frame = JSONObject(protocol.setupFrame(credential, "es"))
        assertEquals("session.update", frame.getString("type"))
        val audio = frame.getJSONObject("session").getJSONObject("audio")
        assertEquals("es", audio.getJSONObject("output").getString("language"))
        assertEquals(
            "gpt-realtime-whisper",
            audio.getJSONObject("input").getJSONObject("transcription").getString("model"),
        )
        assertEquals("far_field", audio.getJSONObject("input").getJSONObject("noise_reduction").getString("type"))
    }

    // The model is fixed when the session is created and may not appear in an update.
    @Test
    fun `an update never tries to change the model`() {
        assertFalse(JSONObject(protocol.setupFrame(credential, "es")).getJSONObject("session").has("model"))
    }

    @Test
    fun `a running session is re-aimed with one message`() {
        assertTrue(protocol.retargetsInPlace)
        val frame = JSONObject(protocol.retargetFrame("fr"))
        assertEquals("session.update", frame.getString("type"))
        val audio = frame.getJSONObject("session").getJSONObject("audio")
        assertEquals("fr", audio.getJSONObject("output").getString("language"))
        assertFalse("re-aiming must not touch the input settings", audio.has("input"))
    }

    @Test
    fun `audio is appended with the translation event name, at 24 kHz in 200 ms frames`() {
        val pcm = ByteArray(9600) { it.toByte() }
        val frame = JSONObject(protocol.audioFrame(pcm))
        assertEquals("session.input_audio_buffer.append", frame.getString("type"))
        assertTrue(pcm.contentEquals(Base64.getDecoder().decode(frame.getString("audio"))))
        assertEquals(24_000, protocol.inputSampleRateHz)
        assertEquals(200, protocol.inputFrameMs)
    }

    @Test
    fun `the credential is a bearer token and the safety identifier rides along`() {
        assertEquals(
            mapOf("Authorization" to "Bearer ek_test", "OpenAI-Safety-Identifier" to "earslate_abc"),
            protocol.headers(credential),
        )
    }

    @Test
    fun `only the thirteen languages the model can speak are offered`() {
        for (tag in listOf("es-ES", "pt-BR", "fr-FR", "ja-JP", "ru-RU", "zh-CN", "de-DE", "ko-KR", "hi-IN", "id-ID", "vi-VN", "it-IT", "en-US")) {
            assertEquals(tag, tag.substringBefore('-'), protocol.wireLanguage(tag))
        }
        // Chinese and Portuguese go as the bare code: the API takes "zh" and "pt".
        assertEquals("zh", protocol.wireLanguage("zh-TW"))
        assertEquals("pt", protocol.wireLanguage("pt-PT"))
        for (tag in listOf("pa-IN", "bn-IN", "ar-SA", "tr-TR", "pl-PL", "nl-NL", "th-TH", "uk-UA")) {
            assertNull("$tag cannot be spoken by this model", protocol.wireLanguage(tag))
        }
    }

    // ── server ──────────────────────────────────────────────────────────

    @Test
    fun `session created is not the go-ahead, but it says when the session ends`() {
        val events = protocol.parse(
            """{"type":"session.created","event_id":"e1","session":{"id":"sess_1","type":"translation","model":"gpt-realtime-translate","expires_at":1790000000}}""",
        )
        assertEquals(listOf<LiveEvent>(LiveEvent.SessionExpiry(1_790_000_000)), events)
    }

    @Test
    fun `session updated is the go-ahead`() {
        val events = protocol.parse("""{"type":"session.updated","event_id":"e2","session":{"id":"sess_1"}}""")
        assertEquals(listOf<LiveEvent>(LiveEvent.SetupComplete), events)
    }

    @Test
    fun `translated audio is decoded at the rate the event gives`() {
        val pcm = ByteArray(9600) { 3 }
        val encoded = Base64.getEncoder().encodeToString(pcm)
        val chunk = protocol.parse(
            """{"type":"session.output_audio.delta","event_id":"e3","delta":"$encoded","sample_rate":24000,"format":"pcm16","channels":1}""",
        ).single() as LiveEvent.AudioChunk
        assertEquals(24_000, chunk.sampleRateHz)
        assertTrue(pcm.contentEquals(chunk.pcm))

        val bare = protocol.parse("""{"type":"session.output_audio.delta","delta":"$encoded"}""").single() as LiveEvent.AudioChunk
        assertEquals(24_000, bare.sampleRateHz)
    }

    // "Clients should not insert unconditional spaces between deltas."
    @Test
    fun `a transcript delta that is only a space is kept`() {
        assertEquals(
            listOf<LiveEvent>(LiveEvent.CaptionDelta(" ")),
            protocol.parse("""{"type":"session.output_transcript.delta","delta":" "}"""),
        )
        assertEquals(
            listOf<LiveEvent>(LiveEvent.CaptionDelta("Hola")),
            protocol.parse("""{"type":"session.output_transcript.delta","delta":"Hola","elapsed_ms":400}"""),
        )
    }

    @Test
    fun `the source transcript arrives without a language`() {
        assertEquals(
            listOf<LiveEvent>(LiveEvent.SourceTranscript("Hello there", null)),
            protocol.parse("""{"type":"session.input_transcript.delta","delta":"Hello there"}"""),
        )
    }

    // "Most errors are recoverable and the session will stay open."
    @Test
    fun `an error event is reported, not treated as the end of the session`() {
        val events = protocol.parse(
            """{"type":"error","event_id":"e9","error":{"type":"invalid_request_error","code":"x","message":"Unsupported output language."}}""",
        )
        assertEquals(listOf<LiveEvent>(LiveEvent.ProviderNotice("Unsupported output language.")), events)
    }

    @Test
    fun `session closed is recognised`() {
        assertEquals(listOf<LiveEvent>(LiveEvent.SessionClosed), protocol.parse("""{"type":"session.closed","event_id":"e7"}"""))
        assertEquals("session.close", JSONObject(protocol.gracefulCloseFrame()).getString("type"))
    }

    // The app used to wait on these two for the end of every sentence. They are not in the reference.
    @Test
    fun `events that do not exist for translation sessions produce nothing`() {
        assertTrue(protocol.parse("""{"type":"session.output_audio.done"}""").isEmpty())
        assertTrue(protocol.parse("""{"type":"session.output_transcript.done"}""").isEmpty())
        assertTrue(protocol.parse("""{"type":"response.done"}""").isEmpty())
    }

    @Test
    fun `a frame that cannot be read costs that frame and nothing else`() {
        assertTrue(protocol.parse("not json").isEmpty())
        assertTrue(protocol.parse("""{"type":"session.output_audio.delta","delta":"%%%"}""").isEmpty())
        assertTrue(protocol.parse("""{"type":7}""").isEmpty())
        assertTrue(protocol.parse("""{"type":"session.output_transcript.delta","delta":{"nested":true}}""").isEmpty())
    }
}
