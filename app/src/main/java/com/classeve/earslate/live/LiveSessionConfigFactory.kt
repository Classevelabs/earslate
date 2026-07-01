package com.classeve.earslate.live

import android.util.Base64
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Builds the client-side wire messages for a `gemini-3.5-live-translate-preview`
 * Live session.
 *
 * This model is a purpose-built speech-to-speech translator, NOT a general
 * assistant. The whole session is configured by one structured field —
 * `generationConfig.translationConfig` (target language + echo toggle) — and the
 * model auto-detects the source language. There is no systemInstruction; sending
 * one (the old approach) made the model echo the source and lag by seconds.
 *
 * Two outputs:
 * - [buildSetup] — first frame after the socket opens. model + translationConfig
 *   + audio-in/out transcription (for captions).
 * - [buildAudioChunk] — per-batch realtime input as `realtimeInput.mediaChunks`
 *   (the singular `audio` field is ignored by this model). 16 kHz PCM16 mono.
 *
 * Every shape here was verified live end-to-end against the real model.
 */
object LiveSessionConfigFactory {

    private val json = Json {
        encodeDefaults = false
        explicitNulls = false
    }

    fun buildSetup(
        model: String,
        targetLanguageCode: String,
        echoTargetLanguage: Boolean,
        captionsEnabled: Boolean,
    ): String {
        val setup = ClientSetupPayload(
            model = normalizeModel(model),
            generationConfig = GenerationConfig(
                responseModalities = listOf("AUDIO"),
                translationConfig = TranslationConfig(
                    targetLanguageCode = targetLanguageCode,
                    echoTargetLanguage = echoTargetLanguage,
                ),
            ),
            // Transcription drives the captions UI. The model returns input
            // (what it heard) + output (the translation) transcriptions. These
            // are setup-LEVEL fields — nesting them inside generationConfig is
            // rejected by the model ("Unknown name ... at setup.generation_config").
            inputAudioTranscription = if (captionsEnabled) JsonObject(emptyMap()) else null,
            outputAudioTranscription = if (captionsEnabled) JsonObject(emptyMap()) else null,
        )
        return json.encodeToString(ClientSetupFrame(setup = setup))
    }

    fun buildAudioChunk(pcm16k: ByteArray): String {
        val base64 = Base64.encodeToString(pcm16k, Base64.NO_WRAP)
        val frame = ClientRealtimeFrame(
            realtimeInput = RealtimeInput(
                mediaChunks = listOf(
                    AudioBlob(data = base64, mimeType = "audio/pcm;rate=16000"),
                ),
            ),
        )
        return json.encodeToString(frame)
    }

    /**
     * Gemini Live expects the model prefixed with `models/`.
     */
    private fun normalizeModel(raw: String): String =
        if (raw.startsWith("models/")) raw else "models/$raw"

    /**
     * Map an app BCP-47 tag (e.g. "es-ES", "hi-IN", "zh-CN") to the
     * `targetLanguageCode` the translate model accepts.
     *
     * Verified live: the model takes the PRIMARY SUBTAG ("es", "hi", "fr", "en")
     * and REJECTS region forms ("es-ES", "hi-IN" → close 1007 invalid argument).
     * The sole exception is Chinese, where the script/region is meaningful and
     * the model accepts (and needs) "zh-CN" / "zh-TW".
     */
    fun translateCodeFor(bcp47: String): String {
        val code = if (bcp47.startsWith("zh", ignoreCase = true)) bcp47
                    else bcp47.substringBefore('-')
        // A blank/corrupted language entry would otherwise flow straight into
        // the setup frame as an empty targetLanguageCode, which the model
        // will reject — fail to a safe default instead of sending garbage
        // over the wire.
        return code.ifBlank { "en" }
    }
}
