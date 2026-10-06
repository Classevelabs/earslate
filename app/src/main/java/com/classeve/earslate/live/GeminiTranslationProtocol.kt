package com.classeve.earslate.live

import android.util.Log
import com.classeve.earslate.bootstrap.SessionCredential
import com.classeve.earslate.session.TranslationProvider
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.util.Base64

/**
 * Gemini Live Translate over the Live API's BidiGenerateContent socket. One
 * structured field, `translationConfig`, configures the whole session; the
 * model works out the source language itself and reports it on each transcript.
 */
internal object GeminiTranslationProtocol : TranslationLiveProtocol {

    private const val TAG = "GeminiProtocol"
    private const val DEFAULT_OUTPUT_RATE_HZ = 24_000

    override val provider = TranslationProvider.GEMINI
    override val inputSampleRateHz = 16_000

    // The translate guide's own figure; smaller frames did not arrive sooner.
    override val inputFrameMs = 100

    // The language is fixed by the socket's first frame.
    override val retargetsInPlace = false

    private val outgoing = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    private val incoming = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    private val RATE = Regex("rate=(\\d+)")
    private val LANGUAGE_TAG = Regex("^[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*$")
    private val TRADITIONAL_CHINESE = listOf("-tw", "-hk", "-mo", "hant")
    private val ENABLED = JsonObject(emptyMap())

    // Region forms like "es-ES" are rejected, except that Chinese takes a
    // script and Portuguese keeps its region.
    override fun wireLanguage(bcp47: String): String? {
        val tag = bcp47.trim()
        if (!LANGUAGE_TAG.matches(tag)) return null
        val lower = tag.lowercase()
        return when (primarySubtag(tag)) {
            "zh" -> if (TRADITIONAL_CHINESE.any { lower.contains(it) }) "zh-Hant" else "zh-Hans"
            "pt" -> if (lower.startsWith("pt-pt")) "pt-PT" else "pt-BR"
            else -> primarySubtag(tag)
        }
    }

    override fun headers(credential: SessionCredential): Map<String, String> =
        mapOf("Authorization" to "Token ${credential.secret}")

    override fun setupFrame(credential: SessionCredential, targetWireLanguage: String): String =
        outgoing.encodeToString(
            ClientSetupFrame(
                setup = ClientSetupPayload(
                    model = "models/${credential.model.removePrefix("models/")}",
                    generationConfig = GenerationConfig(
                        responseModalities = listOf("AUDIO"),
                        translationConfig = TranslationConfig(
                            targetLanguageCode = targetWireLanguage,
                            echoTargetLanguage = false,
                        ),
                    ),
                    // Always on: the input transcript carries the language
                    // the model hears, which decides which direction speaks.
                    inputAudioTranscription = ENABLED,
                    outputAudioTranscription = ENABLED,
                ),
            ),
        )

    override fun audioFrame(pcm: ByteArray): String = outgoing.encodeToString(
        ClientRealtimeFrame(
            realtimeInput = RealtimeInput(
                audio = AudioBlob(
                    data = Base64.getEncoder().encodeToString(pcm),
                    mimeType = "audio/pcm;rate=$inputSampleRateHz",
                ),
            ),
        ),
    )

    override fun parse(frame: String): List<LiveEvent> {
        val server = try {
            incoming.decodeFromString(ServerFrame.serializer(), frame)
        } catch (t: Throwable) {
            Log.w(TAG, "unreadable frame: ${t.javaClass.simpleName}")
            return emptyList()
        }

        val events = ArrayList<LiveEvent>(2)
        if (server.setupComplete != null) events += LiveEvent.SetupComplete

        server.serverContent?.let { content ->
            content.modelTurn?.parts?.forEach { part ->
                val inline = part.inlineData ?: return@forEach
                if (!inline.mimeType.startsWith("audio/")) return@forEach
                val pcm = decode(inline.data) ?: return@forEach
                val rate = RATE.find(inline.mimeType)?.groupValues?.get(1)?.toIntOrNull()
                    ?: DEFAULT_OUTPUT_RATE_HZ
                events += LiveEvent.AudioChunk(pcm, rate)
            }
            content.inputTranscription?.let { heard ->
                val language = heard.languageCode?.takeIf { it.isNotBlank() }
                if (heard.text.isNotEmpty() || language != null) {
                    events += LiveEvent.SourceTranscript(heard.text, language)
                }
            }
            content.outputTranscription?.let { said ->
                if (said.text.isNotEmpty()) events += LiveEvent.CaptionDelta(said.text)
            }
        }

        server.goAway?.let { events += LiveEvent.GoAway(durationMs(it.timeLeft)) }
        return events
    }

    private fun decode(data: String): ByteArray? = try {
        Base64.getDecoder().decode(data)
    } catch (t: IllegalArgumentException) {
        Log.w(TAG, "audio that is not base64")
        null
    }

    private fun durationMs(duration: String?): Long? {
        val seconds = duration?.trim()?.removeSuffix("s")?.toDoubleOrNull() ?: return null
        return (seconds * 1000).toLong().takeIf { it >= 0 }
    }
}
