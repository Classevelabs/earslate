package com.classeve.earslate.live

import java.util.Base64
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
 * - [buildAudioChunk] — per-batch realtime input as `realtimeInput.audio`.
 *   16 kHz PCM16 mono, matching the current Live Translate contract.
 *
 * Shapes are locked by contract tests against the current provider docs.
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
                inputAudioTranscription = if (captionsEnabled) JsonObject(emptyMap()) else null,
                outputAudioTranscription = if (captionsEnabled) JsonObject(emptyMap()) else null,
            ),
        )
        return json.encodeToString(ClientSetupFrame(setup = setup))
    }

    fun buildAudioChunk(pcm16k: ByteArray): String {
        val base64 = Base64.getEncoder().encodeToString(pcm16k)
        val frame = ClientRealtimeFrame(
            realtimeInput = RealtimeInput(
                audio = AudioBlob(data = base64, mimeType = "audio/pcm;rate=16000"),
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
     * Most languages use the primary subtag. Chinese uses the documented
     * `zh-Hans`/`zh-Hant` scripts and Portuguese retains its regional form.
     */
    fun translateCodeFor(bcp47: String): String {
        val normalized = bcp47.trim()
        val code = when {
            normalized.startsWith("zh", ignoreCase = true) -> {
                val lower = normalized.lowercase()
                if (lower.contains("tw") || lower.contains("hk") || lower.contains("mo") || lower.contains("hant")) {
                    "zh-Hant"
                } else {
                    "zh-Hans"
                }
            }
            normalized.startsWith("pt-PT", ignoreCase = true) -> "pt-PT"
            normalized.startsWith("pt", ignoreCase = true) -> "pt-BR"
            else -> normalized.substringBefore('-').lowercase()
        }
        // A blank/corrupted language entry would otherwise flow straight into
        // the setup frame as an empty targetLanguageCode, which the model
        // will reject — fail to a safe default instead of sending garbage
        // over the wire.
        return code.ifBlank { "en" }
    }
}
