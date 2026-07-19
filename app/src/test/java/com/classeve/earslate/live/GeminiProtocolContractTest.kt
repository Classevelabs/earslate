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

    @Test
    fun transcriptionAndTranslationAreNestedInGenerationConfig() {
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
        assertNotNull(generation["inputAudioTranscription"])
        assertNotNull(generation["outputAudioTranscription"])
        assertFalse(setup.containsKey("inputAudioTranscription"))
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
