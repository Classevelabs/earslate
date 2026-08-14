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

    /**
     * The session configuration itself, with no frame around it.
     *
     * There are two places this exact object has to appear, and they must be
     * byte-identical:
     *
     *  1. `bidiGenerateContentSetup` inside the ephemeral-token request, which
     *     LOCKS the session — the socket is opened against
     *     `BidiGenerateContentConstrained`.
     *  2. The `setup` frame the client sends once that socket is open.
     *
     * They used to be built twice, by hand, in two files. `LiveWireModels`
     * asserted in a comment that they were "identical shape", and they were
     * not: the token always carried `inputAudioTranscription` /
     * `outputAudioTranscription`, while this builder omitted them when captions
     * were off. Turning captions off therefore produced a session whose token
     * and setup frame disagreed — a locked config the client then contradicted.
     * Both halves had a passing test; neither test compared them, so the suite
     * was green on a contradiction.
     *
     * One builder, two emitters, is why that cannot happen again. Do not
     * reintroduce a second copy of this object anywhere — embed this one.
     */
    private fun sessionSetup(
        model: String,
        targetLanguageCode: String,
        echoTargetLanguage: Boolean,
        captionsEnabled: Boolean,
    ) = ClientSetupPayload(
        model = normalizeModel(model),
        generationConfig = GenerationConfig(
            responseModalities = listOf("AUDIO"),
            translationConfig = TranslationConfig(
                targetLanguageCode = targetLanguageCode,
                echoTargetLanguage = echoTargetLanguage,
            ),
        ),
        // Transcription config sits on the setup, one level ABOVE
        // generationConfig. See ClientSetupPayload for the 1007 close this
        // caused when it was nested.
        inputAudioTranscription = if (captionsEnabled) JsonObject(emptyMap()) else null,
        outputAudioTranscription = if (captionsEnabled) JsonObject(emptyMap()) else null,
    )

    /** The first frame after the socket opens: the setup, wrapped in `setup`. */
    fun buildSetup(
        model: String,
        targetLanguageCode: String,
        echoTargetLanguage: Boolean,
        captionsEnabled: Boolean,
    ): String = json.encodeToString(
        ClientSetupFrame(
            setup = sessionSetup(model, targetLanguageCode, echoTargetLanguage, captionsEnabled),
        ),
    )

    /**
     * The same setup, unwrapped, for `bidiGenerateContentSetup` in the token
     * request. Returned as JSON text so the minter — which builds its body with
     * `org.json` — can embed it without this module owning a second serializer.
     */
    fun buildTokenSessionSetup(
        model: String,
        targetLanguageCode: String,
        echoTargetLanguage: Boolean,
        captionsEnabled: Boolean,
    ): String = json.encodeToString(
        sessionSetup(model, targetLanguageCode, echoTargetLanguage, captionsEnabled),
    )

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
