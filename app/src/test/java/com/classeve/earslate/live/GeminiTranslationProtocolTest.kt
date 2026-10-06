package com.classeve.earslate.live

import com.classeve.earslate.bootstrap.SessionCredential
import com.classeve.earslate.session.TranslationProvider
import com.classeve.earslate.testing.RecordedSession
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * The Gemini wire, pinned against what the live endpoint accepts and sends.
 * The frames under "server" are taken from a session recorded on 2026-10-06.
 */
class GeminiTranslationProtocolTest {

    private val protocol = GeminiTranslationProtocol
    private val credential = SessionCredential(
        provider = TranslationProvider.GEMINI,
        secret = "auth_tokens/test",
        webSocketUrl = "wss://example.invalid",
        model = "gemini-3.5-live-translate-preview",
        expiresAtMs = Long.MAX_VALUE,
    )

    private fun setup(target: String = "es") = JSONObject(protocol.setupFrame(credential, target)).getJSONObject("setup")

    @Test
    fun `the setup names the model with its prefix`() {
        assertEquals("models/gemini-3.5-live-translate-preview", setup().getString("model"))
    }

    // Nested in generationConfig the server closes the socket with 1007.
    @Test
    fun `transcription is asked for on the setup, never inside generationConfig`() {
        val setup = setup()
        assertTrue(setup.has("inputAudioTranscription"))
        assertTrue(setup.has("outputAudioTranscription"))
        val generation = setup.getJSONObject("generationConfig")
        assertFalse(generation.has("inputAudioTranscription"))
        assertFalse(generation.has("outputAudioTranscription"))
    }

    @Test
    fun `the language and the echo switch travel in translationConfig`() {
        val translation = setup("hi").getJSONObject("generationConfig").getJSONObject("translationConfig")
        assertEquals("hi", translation.getString("targetLanguageCode"))
        assertTrue("echoTargetLanguage must be written explicitly", translation.has("echoTargetLanguage"))
        assertFalse(translation.getBoolean("echoTargetLanguage"))
        assertEquals("AUDIO", setup().getJSONObject("generationConfig").getJSONArray("responseModalities").getString(0))
    }

    // A prompt makes the translate model answer questions instead of translating them.
    @Test
    fun `no prompt can be sent`() {
        assertFalse(setup().has("systemInstruction"))
        assertFalse(protocol.setupFrame(credential, "es").contains("systemInstruction"))
    }

    @Test
    fun `microphone audio goes out as 16 kHz realtime input`() {
        val pcm = ByteArray(3200) { it.toByte() }
        val audio = JSONObject(protocol.audioFrame(pcm)).getJSONObject("realtimeInput").getJSONObject("audio")
        assertEquals("audio/pcm;rate=16000", audio.getString("mimeType"))
        assertTrue(pcm.contentEquals(Base64.getDecoder().decode(audio.getString("data"))))
        assertEquals(16_000, protocol.inputSampleRateHz)
        assertEquals(100, protocol.inputFrameMs)
    }

    @Test
    fun `the token goes in the Authorization header`() {
        assertEquals(mapOf("Authorization" to "Token auth_tokens/test"), protocol.headers(credential))
    }

    @Test
    fun `app language tags become the codes the model accepts`() {
        assertEquals("es", protocol.wireLanguage("es-ES"))
        assertEquals("hi", protocol.wireLanguage("hi-IN"))
        assertEquals("fil", protocol.wireLanguage("fil-PH"))
        assertEquals("zh-Hans", protocol.wireLanguage("zh-CN"))
        assertEquals("zh-Hant", protocol.wireLanguage("zh-TW"))
        assertEquals("zh-Hans", protocol.wireLanguage("zh"))
        assertEquals("pt-BR", protocol.wireLanguage("pt-BR"))
        assertEquals("pt-PT", protocol.wireLanguage("pt-PT"))
        assertEquals("pt-BR", protocol.wireLanguage("pt"))
    }

    @Test
    fun `a script that is named outranks what the region suggests`() {
        assertEquals("zh-Hans", protocol.wireLanguage("zh-Hans-TW"))
        assertEquals("zh-Hant", protocol.wireLanguage("zh-Hant-CN"))
    }

    // The model says "no" and "tl" for Norwegian and Filipino, and takes both back as targets.
    @Test
    fun `the model's own names for a language are sent back as it gave them`() {
        assertEquals("no", protocol.wireLanguage("no"))
        assertEquals("tl", protocol.wireLanguage("tl"))
        assertEquals("nb", protocol.wireLanguage("nb-NO"))
    }

    @Test
    fun `a tag with an extension is still its language`() {
        assertEquals("en", protocol.wireLanguage("en-US-u-hc-h23"))
    }

    // It used to answer "en", so a broken setting translated into the wrong language without a word.
    @Test
    fun `a tag that is not a language is refused, not turned into English`() {
        assertNull(protocol.wireLanguage(""))
        assertNull(protocol.wireLanguage("   "))
        assertNull(protocol.wireLanguage("not a language"))
    }

    // ── server ──────────────────────────────────────────────────────────

    @Test
    fun `setupComplete is recognised`() {
        assertEquals(listOf<LiveEvent>(LiveEvent.SetupComplete), protocol.parse("""{"setupComplete":{}}"""))
    }

    @Test
    fun `a heard fragment carries the language the model named`() {
        val events = protocol.parse(
            """{"serverContent":{"inputTranscription":{"text":"Hola, buenos días. ¿Me","languageCode":"es"}}}""",
        )
        assertEquals(listOf<LiveEvent>(LiveEvent.SourceTranscript("Hola, buenos días. ¿Me", "es")), events)
    }

    // About once a second the model repeats its verdict with no text at all.
    @Test
    fun `a transcription with only a language code is still an event, and not a crash`() {
        val events = protocol.parse("""{"serverContent":{"inputTranscription":{"languageCode":"en"}}}""")
        assertEquals(listOf<LiveEvent>(LiveEvent.SourceTranscript("", "en")), events)
    }

    @Test
    fun `translated text keeps its leading space so fragments join into words`() {
        val events = protocol.parse(
            """{"serverContent":{"outputTranscription":{"text":" Can you tell me where","languageCode":"en"}}}""",
        )
        assertEquals(listOf<LiveEvent>(LiveEvent.CaptionDelta(" Can you tell me where")), events)
    }

    @Test
    fun `an output transcription with no text says nothing`() {
        assertTrue(protocol.parse("""{"serverContent":{"outputTranscription":{"languageCode":"en"}}}""").isEmpty())
    }

    @Test
    fun `audio is decoded at the rate the frame declares`() {
        val pcm = ByteArray(12_000) { 7 }
        val frame = """{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"${
            Base64.getEncoder().encodeToString(pcm)
        }"}}]}}}"""
        val chunk = protocol.parse(frame).single() as LiveEvent.AudioChunk
        assertEquals(24_000, chunk.sampleRateHz)
        assertTrue(pcm.contentEquals(chunk.pcm))
    }

    @Test
    fun `goAway carries how long is left`() {
        assertEquals(listOf<LiveEvent>(LiveEvent.GoAway(50_000)), protocol.parse("""{"goAway":{"timeLeft":"50s"}}"""))
        assertEquals(listOf<LiveEvent>(LiveEvent.GoAway(49_500)), protocol.parse("""{"goAway":{"timeLeft":"49.500s"}}"""))
        assertEquals(listOf<LiveEvent>(LiveEvent.GoAway(null)), protocol.parse("""{"goAway":{}}"""))
    }

    @Test
    fun `frames the app has no use for are ignored without complaint`() {
        assertTrue(protocol.parse("""{"serverContent":{}}""").isEmpty())
        assertTrue(protocol.parse("""{"sessionResumptionUpdate":{"newHandle":"x","resumable":true}}""").isEmpty())
        assertTrue(
            protocol.parse("""{"serverContent":{},"usageMetadata":{"promptTokenCount":50,"totalTokenCount":100}}""").isEmpty(),
        )
    }

    @Test
    fun `a frame that is not JSON costs that frame and nothing else`() {
        assertTrue(protocol.parse("not json").isEmpty())
        assertTrue(protocol.parse("").isEmpty())
        assertTrue(protocol.parse("""{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm","data":"%%%"}}]}}}""").isEmpty())
    }

    // The app waited on turnComplete for years of model versions; this one never sends it.
    @Test
    fun `a whole recorded conversation parses, and the model never marks a turn`() {
        val session = RecordedSession.load("conversation-en-es.jsonl")
        var audio = 0
        var heardWithLanguage = 0
        var captions = 0
        for (frame in session.frames) {
            assertFalse("the model is not expected to send turnComplete", frame.json.contains("turnComplete"))
            for (event in protocol.parse(frame.json)) {
                when (event) {
                    is LiveEvent.AudioChunk -> audio++
                    is LiveEvent.SourceTranscript -> if (event.languageCode != null && event.text.isNotBlank()) heardWithLanguage++
                    is LiveEvent.CaptionDelta -> captions++
                    else -> Unit
                }
            }
        }
        assertEquals("every audio frame in the recording", 370, audio)
        assertTrue("fragments that name their language: $heardWithLanguage", heardWithLanguage >= 40)
        assertTrue("translated text fragments: $captions", captions >= 20)
    }
}
