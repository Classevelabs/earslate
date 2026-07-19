package com.classeve.earslate.live

import java.util.Base64
import com.classeve.earslate.bootstrap.SessionBootstrap
import com.classeve.earslate.session.TranslationProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

interface TranslationLiveProtocol {
    fun headers(bootstrap: SessionBootstrap): Map<String, String>
    fun setupFrame(bootstrap: SessionBootstrap, targetLanguageCode: String, captionsEnabled: Boolean): String
    fun audioFrame(pcm16k: ByteArray): String
    fun parse(frame: String): List<LiveEvent>
    fun gracefulCloseFrame(): String? = null
}

object TranslationLiveProtocols {
    fun forProvider(provider: TranslationProvider): TranslationLiveProtocol = when (provider) {
        TranslationProvider.GEMINI -> GeminiTranslationProtocol
        TranslationProvider.OPENAI -> OpenAiTranslationProtocol
        TranslationProvider.AUTOMATIC -> error("Broker must resolve the provider before opening a socket")
    }
}

private object GeminiTranslationProtocol : TranslationLiveProtocol {
    override fun headers(bootstrap: SessionBootstrap): Map<String, String> =
        mapOf("Authorization" to "Token ${bootstrap.credential}")

    override fun setupFrame(
        bootstrap: SessionBootstrap,
        targetLanguageCode: String,
        captionsEnabled: Boolean,
    ): String = LiveSessionConfigFactory.buildSetup(
        model = bootstrap.model,
        targetLanguageCode = targetLanguageCode,
        echoTargetLanguage = false,
        captionsEnabled = captionsEnabled,
    )

    override fun audioFrame(pcm16k: ByteArray): String =
        LiveSessionConfigFactory.buildAudioChunk(pcm16k)

    override fun parse(frame: String): List<LiveEvent> = LiveMessageParser.parse(frame)
}

private object OpenAiTranslationProtocol : TranslationLiveProtocol {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override fun headers(bootstrap: SessionBootstrap): Map<String, String> =
        mapOf("Authorization" to "Bearer ${bootstrap.credential}")

    override fun setupFrame(
        bootstrap: SessionBootstrap,
        targetLanguageCode: String,
        captionsEnabled: Boolean,
    ): String = buildJsonObject {
        put("type", "session.update")
        put("session", buildJsonObject {
            put("audio", buildJsonObject {
                put("output", buildJsonObject { put("language", targetLanguageCode) })
            })
        })
    }.toString()

    override fun audioFrame(pcm16k: ByteArray): String {
        val pcm24k = Pcm16Resampler.from16kTo24k(pcm16k)
        return buildJsonObject {
            put("type", "session.input_audio_buffer.append")
            put("audio", Base64.getEncoder().encodeToString(pcm24k))
        }.toString()
    }

    override fun parse(frame: String): List<LiveEvent> {
        val root = runCatching { json.parseToJsonElement(frame).jsonObject }.getOrNull()
            ?: return emptyList()
        fun string(name: String): String = root[name]?.jsonPrimitive?.contentOrNull.orEmpty()
        return when (string("type")) {
            "session.updated" -> listOf(LiveEvent.SetupComplete)
            "session.output_audio.delta" -> decodeAudio(string("delta"))
                ?.let { listOf(LiveEvent.AudioChunk(it, 24_000)) }
                ?: emptyList()
            "session.output_transcript.delta" -> string("delta")
                .takeIf { it.isNotBlank() }
                ?.let { listOf(LiveEvent.CaptionDelta(it)) }
                ?: emptyList()
            "session.output_audio.done", "session.output_transcript.done" ->
                listOf(LiveEvent.TurnComplete)
            "session.closed" -> listOf(LiveEvent.SocketClosed(1000, "session_closed"))
            "error" -> {
                val message = root["error"]?.jsonObject?.get("message")
                    ?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: "OpenAI translation session failed."
                listOf(LiveEvent.Error(message))
            }
            else -> emptyList()
        }
    }

    override fun gracefulCloseFrame(): String = buildJsonObject { put("type", "session.close") }.toString()

    private fun decodeAudio(value: String): ByteArray? = runCatching {
        Base64.getDecoder().decode(value)
    }.getOrNull()
}

/** Linear PCM16 resampling for OpenAI's required 24 kHz WebSocket input. */
object Pcm16Resampler {
    fun from16kTo24k(input: ByteArray): ByteArray {
        val inputSamples = input.size / 2
        if (inputSamples == 0) return ByteArray(0)
        val outputSamples = inputSamples * 3 / 2
        val output = ByteArray(outputSamples * 2)
        for (index in 0 until outputSamples) {
            val source = index * 2.0 / 3.0
            val lower = source.toInt().coerceAtMost(inputSamples - 1)
            val upper = (lower + 1).coerceAtMost(inputSamples - 1)
            val fraction = source - lower
            val a = sample(input, lower)
            val b = sample(input, upper)
            val value = (a + (b - a) * fraction).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            output[index * 2] = (value and 0xff).toByte()
            output[index * 2 + 1] = ((value ushr 8) and 0xff).toByte()
        }
        return output
    }

    private fun sample(bytes: ByteArray, index: Int): Int {
        val offset = index * 2
        val value = (bytes[offset].toInt() and 0xff) or (bytes[offset + 1].toInt() shl 8)
        return value.toShort().toInt()
    }
}
