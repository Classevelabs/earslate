package com.classeve.earslate.live

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/*
 * Wire models for the Gemini Live API (v1beta BidiGenerateContent). Only the
 * fields the app reads or writes are declared. Every incoming field has a
 * default: a missing required field would throw and cost the whole frame,
 * audio included.
 */

@Serializable
internal data class ClientSetupFrame(
    val setup: ClientSetupPayload,
)

// No systemInstruction field: given a prompt, the translate model answers
// questions instead of translating them.
@Serializable
internal data class ClientSetupPayload(
    val model: String,
    val generationConfig: GenerationConfig,
    // On the setup, not inside generationConfig: nested there the server
    // closes the socket with 1007 "Cannot find field".
    val inputAudioTranscription: JsonObject,
    val outputAudioTranscription: JsonObject,
)

@Serializable
internal data class GenerationConfig(
    val responseModalities: List<String>,
    val translationConfig: TranslationConfig,
)

@Serializable
internal data class TranslationConfig(
    val targetLanguageCode: String,
    // No default, so it is always written to the wire.
    val echoTargetLanguage: Boolean,
)

@Serializable
internal data class ClientRealtimeFrame(
    val realtimeInput: RealtimeInput,
)

@Serializable
internal data class RealtimeInput(
    val audio: AudioBlob,
)

@Serializable
internal data class AudioBlob(
    val data: String,
    val mimeType: String,
)

@Serializable
internal data class ServerFrame(
    val setupComplete: JsonObject? = null,
    val serverContent: ServerContent? = null,
    val goAway: GoAway? = null,
)

@Serializable
internal data class ServerContent(
    val modelTurn: Content? = null,
    val outputTranscription: Transcription? = null,
    val inputTranscription: Transcription? = null,
)

@Serializable
internal data class Content(
    val parts: List<Part> = emptyList(),
)

@Serializable
internal data class Part(
    val inlineData: InlineData? = null,
)

@Serializable
internal data class InlineData(
    val mimeType: String = "",
    val data: String = "",
)

@Serializable
internal data class Transcription(
    val text: String = "",
    // The language the model decided it heard (input) or spoke (output).
    val languageCode: String? = null,
)

@Serializable
internal data class GoAway(
    // A protobuf Duration as JSON: "50s", "49.500s".
    val timeLeft: String? = null,
)
