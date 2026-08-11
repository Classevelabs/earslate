package com.classeve.earslate.live

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiProtocolContractTest {
    @Test
    fun normalizesRegionTagsToProviderTranslationCodes() {
        org.junit.Assert.assertEquals("es", LiveSessionConfigFactory.translateCodeFor("es-ES"))
        org.junit.Assert.assertEquals("zh-Hans", LiveSessionConfigFactory.translateCodeFor("zh-CN"))
        org.junit.Assert.assertEquals("zh-Hant", LiveSessionConfigFactory.translateCodeFor("zh-TW"))
        org.junit.Assert.assertEquals("pt-PT", LiveSessionConfigFactory.translateCodeFor("pt-PT"))
    }

    /**
     * The socket setup frame must use the SAME field placement the mint body
     * uses (see GeminiAuthTokenShapeTest): transcription on the setup,
     * translation inside generationConfig.
     *
     * This test previously asserted the exact opposite and so pinned a real bug
     * in place — the suite stayed green while every session with captions on was
     * closed by the server with:
     *   1007 Invalid JSON payload received. Unknown name
     *   "outputAudioTranscription" at 'setup.generation_config': Cannot find field.
     * Measured on-device 2026-07-26. Do not "tidy" these fields back down.
     */
    @Test
    fun transcriptionSitsOnSetupAndTranslationInsideGenerationConfig() {
        val root = Json.parseToJsonElement(
            LiveSessionConfigFactory.buildSetup(
                model = "gemini-3.5-live-translate-preview",
                targetLanguageCode = "es",
                echoTargetLanguage = false,
                captionsEnabled = true,
            ),
        ).jsonObject
        val setup = root.getValue("setup").jsonObject
        val generation = setup.getValue("generationConfig").jsonObject

        assertNotNull(generation["translationConfig"])
        assertNotNull(setup["inputAudioTranscription"])
        assertNotNull(setup["outputAudioTranscription"])
        assertFalse(
            "generationConfig rejects inputAudioTranscription",
            generation.containsKey("inputAudioTranscription"),
        )
        assertFalse(
            "generationConfig rejects outputAudioTranscription",
            generation.containsKey("outputAudioTranscription"),
        )
        assertFalse(
            "the setup rejects translationConfig",
            setup.containsKey("translationConfig"),
        )
    }

    /** Captions off must omit both fields entirely rather than sending nulls. */
    @Test
    fun captionsOffOmitsTranscriptionFields() {
        val root = Json.parseToJsonElement(
            LiveSessionConfigFactory.buildSetup(
                model = "gemini-3.5-live-translate-preview",
                targetLanguageCode = "es",
                echoTargetLanguage = false,
                captionsEnabled = false,
            ),
        ).jsonObject
        val setup = root.getValue("setup").jsonObject
        assertFalse(setup.containsKey("inputAudioTranscription"))
        assertFalse(setup.containsKey("outputAudioTranscription"))
    }

    @Test
    fun realtimeAudioUsesTheDocumentedSingularAudioField() {
        val root = Json.parseToJsonElement(
            LiveSessionConfigFactory.buildAudioChunk(ByteArray(3200)),
        ).jsonObject
        val realtime = root.getValue("realtimeInput").jsonObject
        assertTrue(realtime.containsKey("audio"))
        assertFalse(realtime.containsKey("mediaChunks"))
    }
}
