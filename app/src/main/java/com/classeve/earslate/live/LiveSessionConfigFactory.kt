package com.classeve.earslate.live

import android.util.Base64
import com.classeve.earslate.session.SessionPolicy
import com.classeve.earslate.session.TranslatorPolicy
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

/**
 * Builds the client-side wire messages for the Gemini Live API session.
 *
 * Two outputs:
 * - [buildSetup] — the very first frame after the socket opens. Bundles model,
 *   system instruction, speech/voice config, session resumption + compression
 *   toggles, and optional transcription settings.
 * - [buildAudioChunk] — per-frame realtime input. Base64-encoded 16 kHz PCM16
 *   mono audio wrapped in a `realtimeInput.mediaChunks` envelope.
 *
 * Isolated on purpose: this is the piece most likely to need revision when we
 * test against the actual preview model. Nothing outside this file should
 * assemble Gemini Live JSON by hand.
 */
object LiveSessionConfigFactory {

    private val json = Json {
        encodeDefaults = false
        explicitNulls = false
    }

    fun buildSetup(
        policy: TranslatorPolicy,
        model: String,
        systemInstruction: String,
        resumptionHandle: String?,
    ): String {
        val setup = ClientSetupPayload(
            model = normalizeModel(model),
            generationConfig = GenerationConfig(
                responseModalities = listOf("AUDIO"),
                speechConfig = policy.voiceName?.let { voice ->
                    SpeechConfig(
                        voiceConfig = VoiceConfig(
                            prebuiltVoiceConfig = PrebuiltVoiceConfig(voiceName = voice),
                        ),
                    )
                },
            ),
            systemInstruction = Content(
                parts = listOf(Part(text = systemInstruction)),
                role = "system",
            ),
            sessionResumption = if (policy.sessionPolicy.enableResumption) {
                SessionResumptionHandle(handle = resumptionHandle)
            } else null,
            contextWindowCompression = if (policy.sessionPolicy.enableCompression) {
                ContextWindowCompression(slidingWindow = JsonObject(emptyMap()))
            } else null,
            outputAudioTranscription = if (policy.captionsEnabled) {
                JsonObject(emptyMap())
            } else null,
        )
        return json.encodeToString(ClientSetupFrame(setup = setup))
    }

    fun buildAudioChunk(pcm16k: ByteArray): String {
        val base64 = Base64.encodeToString(pcm16k, Base64.NO_WRAP)
        val frame = ClientRealtimeFrame(
            realtimeInput = RealtimeInput(
                audio = AudioBlob(
                    data = base64,
                    mimeType = "audio/pcm;rate=16000",
                ),
            ),
        )
        return json.encodeToString(frame)
    }

    /**
     * Gemini Live expects the model prefixed with `models/`. Callers may pass the
     * bare name (`gemini-3.1-flash-live-preview`) or the fully qualified form;
     * normalize both.
     */
    private fun normalizeModel(raw: String): String =
        if (raw.startsWith("models/")) raw else "models/$raw"
}
