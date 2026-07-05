package com.classeve.earslate.live

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * kotlinx.serialization wire models for the Gemini Live API (v1beta BidiGenerateContent).
 *
 * Fields are nullable + optional so that forward-compat is cheap — the real API adds keys
 * we do not know about, and we silently ignore them via `Json { ignoreUnknownKeys = true }`.
 * When we add or remove a field here, the only thing that breaks is our own parsing, not
 * the whole session.
 *
 * The exact shapes for `gemini-3.5-live-translate-preview` may differ in surface details from
 * the generic v1beta contract this file encodes. Tune here, not in business logic.
 */

// ============================================================================
// Outgoing — client → server
// ============================================================================

@Serializable
internal data class ClientSetupFrame(
    val setup: ClientSetupPayload,
)

@Serializable
internal data class ClientSetupPayload(
    val model: String,
    val generationConfig: GenerationConfig? = null,
    val systemInstruction: Content? = null,
    val sessionResumption: SessionResumptionHandle? = null,
    val contextWindowCompression: ContextWindowCompression? = null,
    val outputAudioTranscription: JsonObject? = null,
    val inputAudioTranscription: JsonObject? = null,
)

@Serializable
internal data class GenerationConfig(
    val responseModalities: List<String>? = null,
    val speechConfig: SpeechConfig? = null,
    val temperature: Double? = null,
    // gemini-3.5-live-translate-preview is a purpose-built speech-to-speech
    // translator. It is driven by this STRUCTURED config (target language +
    // echo toggle), NOT by a freeform systemInstruction prompt. Verified live
    // end-to-end: with translationConfig the model translates; without it (the
    // old prompt-only path) it echoed the source and lagged badly.
    val translationConfig: TranslationConfig? = null,
)

@Serializable
internal data class TranslationConfig(
    // BCP-47 primary subtag the model translates INTO (e.g. "es", "hi", "fr").
    // Chinese keeps its region ("zh-CN"/"zh-TW"); region forms like "es-ES" are
    // rejected by the model — see LiveSessionConfigFactory.translateCodeFor.
    val targetLanguageCode: String,
    // false → stay SILENT when the input is already in the target language
    // (verified: emits pure-zero PCM, peak=1). This is what makes the two-leg
    // bidirectional design work — each leg speaks only for its own direction.
    // NO default on purpose: kotlinx omits default-valued fields under
    // encodeDefaults=false, and we want this explicitly on the wire.
    val echoTargetLanguage: Boolean,
)

@Serializable
internal data class SpeechConfig(
    val voiceConfig: VoiceConfig? = null,
)

@Serializable
internal data class VoiceConfig(
    val prebuiltVoiceConfig: PrebuiltVoiceConfig? = null,
)

@Serializable
internal data class PrebuiltVoiceConfig(
    val voiceName: String,
)

@Serializable
internal data class Content(
    val parts: List<Part>,
    val role: String? = null,
)

@Serializable
internal data class Part(
    val text: String? = null,
    val inlineData: InlineData? = null,
)

@Serializable
internal data class InlineData(
    val mimeType: String,
    val data: String,
)

@Serializable
internal data class SessionResumptionHandle(
    val handle: String? = null,
)

@Serializable
internal data class ContextWindowCompression(
    val slidingWindow: JsonObject? = null,
)

@Serializable
internal data class ClientRealtimeFrame(
    val realtimeInput: RealtimeInput,
)

@Serializable
internal data class RealtimeInput(
    // The translate model only ingests audio sent as `mediaChunks` — the
    // singular `audio` field is silently dropped by this model (verified: zero
    // input transcription, zero output). Always use mediaChunks.
    val mediaChunks: List<AudioBlob>? = null,
    val audio: AudioBlob? = null,
    val video: AudioBlob? = null,
    val text: String? = null,
)

@Serializable
internal data class AudioBlob(
    val data: String,
    val mimeType: String,
)

// ============================================================================
// Incoming — server → client
// ============================================================================

/**
 * Top-level server frame. Exactly one of the branches is populated per message in v1beta
 * today. Any unknown branches are ignored.
 */
@Serializable
internal data class ServerFrame(
    val setupComplete: JsonObject? = null,
    val serverContent: ServerContent? = null,
    val sessionResumptionUpdate: SessionResumptionUpdate? = null,
    val goAway: GoAway? = null,
    @SerialName("usageMetadata")
    val usageMetadata: JsonObject? = null,
)

@Serializable
internal data class ServerContent(
    val modelTurn: Content? = null,
    val turnComplete: Boolean? = null,
    val interrupted: Boolean? = null,
    val outputTranscription: Transcription? = null,
    val inputTranscription: Transcription? = null,
    val generationComplete: Boolean? = null,
)

@Serializable
internal data class Transcription(
    val text: String,
    val finished: Boolean? = null,
)

@Serializable
internal data class SessionResumptionUpdate(
    val newHandle: String? = null,
    val resumable: Boolean? = null,
)

@Serializable
internal data class GoAway(
    val timeLeft: String? = null,
)
