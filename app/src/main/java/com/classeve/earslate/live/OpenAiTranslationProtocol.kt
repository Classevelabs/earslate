package com.classeve.earslate.live

import com.classeve.earslate.audio.Pcm
import com.classeve.earslate.bootstrap.SessionCredential
import com.classeve.earslate.session.TranslationProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.Base64

/**
 * OpenAI Realtime Translation over `wss://api.openai.com/v1/realtime/translations`,
 * as OpenAI's API reference for translation sessions defines it.
 */
internal object OpenAiTranslationProtocol : TranslationLiveProtocol {

    private const val INPUT_TRANSCRIPTION_MODEL = "gpt-realtime-whisper"
    private const val OUTPUT_RATE_HZ = 24_000

    override val provider = TranslationProvider.OPENAI

    // The socket takes 24 kHz only, so the microphone is opened at 24 kHz.
    override val inputSampleRateHz = 24_000

    // The translation engine consumes 200 ms frames.
    override val inputFrameMs = 200

    // session.update may change audio.output.language on a running session.
    override val retargetsInPlace = true

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // The thirteen languages the model can speak, in the codes its API takes.
    private val OUTPUT_LANGUAGES =
        setOf("es", "pt", "fr", "ja", "ru", "zh", "de", "ko", "hi", "id", "vi", "it", "en")

    override fun wireLanguage(bcp47: String): String? =
        primarySubtag(bcp47).takeIf { it in OUTPUT_LANGUAGES }

    override fun headers(credential: SessionCredential): Map<String, String> = buildMap {
        put("Authorization", "Bearer ${credential.secret}")
        credential.safetyIdentifier?.let { put("OpenAI-Safety-Identifier", it) }
    }

    override fun setupFrame(credential: SessionCredential, targetWireLanguage: String): String =
        sessionUpdate(targetWireLanguage)

    // The whole configuration again, as OpenAI's own clients send it: nothing
    // says a setting left out of an update is kept.
    override fun retargetFrame(targetWireLanguage: String): String = sessionUpdate(targetWireLanguage)

    private fun sessionUpdate(targetWireLanguage: String): String = buildJsonObject {
        put("type", "session.update")
        put("session", buildJsonObject {
            put("audio", buildJsonObject {
                put("input", buildJsonObject {
                    // The source transcript is how the app learns whose voice it heard.
                    put("transcription", buildJsonObject { put("model", INPUT_TRANSCRIPTION_MODEL) })
                    // A phone between two people is a far-field microphone.
                    put("noise_reduction", buildJsonObject { put("type", "far_field") })
                })
                put("output", buildJsonObject { put("language", targetWireLanguage) })
            })
        })
    }.toString()

    override fun audioFrame(pcm: ByteArray): String = buildJsonObject {
        put("type", "session.input_audio_buffer.append")
        put("audio", Base64.getEncoder().encodeToString(pcm))
    }.toString()

    override fun parse(frame: String): List<LiveEvent> {
        val event = runCatching { json.parseToJsonElement(frame).jsonObject }.getOrNull()
            ?: return emptyList()
        fun text(name: String): String? =
            event[name]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
        fun obj(name: String): JsonObject? =
            event[name]?.let { runCatching { it.jsonObject }.getOrNull() }

        return when (text("type")) {
            // First event on every connection; the language is not set yet.
            "session.created" -> expiry(obj("session"))

            "session.updated" -> listOf(LiveEvent.SetupComplete) + expiry(obj("session"))

            "session.output_audio.delta" -> {
                val pcm = text("delta")?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
                    ?: return emptyList()
                val rate = runCatching { event["sample_rate"]?.jsonPrimitive?.intOrNull }.getOrNull()
                    ?: OUTPUT_RATE_HZ
                val channels = runCatching { event["channels"]?.jsonPrimitive?.intOrNull }.getOrNull() ?: 1
                listOf(LiveEvent.AudioChunk(if (channels == 2) Pcm.mono(pcm) else pcm, rate))
            }

            // Deltas are append-only, so one that is only a space is kept.
            "session.output_transcript.delta" ->
                text("delta")?.takeIf { it.isNotEmpty() }?.let { listOf(LiveEvent.CaptionDelta(it)) }.orEmpty()

            "session.input_transcript.delta" ->
                text("delta")?.takeIf { it.isNotEmpty() }
                    ?.let { listOf(LiveEvent.SourceTranscript(it, languageCode = null)) }.orEmpty()

            "session.closed" -> listOf(LiveEvent.SessionClosed)

            // Most errors leave the session open, so this does not end it.
            "error" -> listOf(
                LiveEvent.ProviderNotice(
                    obj("error")?.get("message")
                        ?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
                        ?.takeIf { it.isNotBlank() } ?: "OpenAI reported an error.",
                ),
            )

            else -> emptyList()
        }
    }

    private fun expiry(session: JsonObject?): List<LiveEvent> {
        val at = session?.get("expires_at")?.let { runCatching { it.jsonPrimitive.longOrNull }.getOrNull() }
        return if (at != null && at > 0) listOf(LiveEvent.SessionExpiry(at)) else emptyList()
    }

    override fun gracefulCloseFrame(): String = buildJsonObject { put("type", "session.close") }.toString()
}
