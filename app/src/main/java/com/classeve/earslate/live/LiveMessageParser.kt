package com.classeve.earslate.live

import java.util.Base64
import android.util.Log
import kotlinx.serialization.json.Json

/**
 * Translates raw JSON frames from the Gemini Live socket into [LiveEvent]s.
 *
 * Rule: **never throw**. If a frame is malformed or carries an unknown shape,
 * return null and log it. The socket stays alive; the session stays alive; the
 * one bad frame is all that is lost.
 */
object LiveMessageParser {

    private const val TAG = "LiveParser"

    /** Extracts the sample rate from an inlineData mimeType, e.g. "audio/pcm;rate=24000". */
    private val RATE_REGEX = Regex("rate=(\\d+)")

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    fun parse(frame: String): List<LiveEvent> {
        val server = try {
            json.decodeFromString(ServerFrame.serializer(), frame)
        } catch (t: Throwable) {
            Log.w(TAG, "failed to parse frame: ${t.message}")
            return emptyList()
        }

        val events = mutableListOf<LiveEvent>()

        if (server.setupComplete != null) {
            events += LiveEvent.SetupComplete
        }

        server.serverContent?.let { content ->
            content.modelTurn?.parts?.forEach { part ->
                part.inlineData?.let { inline ->
                    if (inline.mimeType.startsWith("audio/")) {
                        val pcm = decodeBase64(inline.data)
                        if (pcm != null) {
                            val rate = RATE_REGEX.find(inline.mimeType)
                                ?.groupValues?.get(1)?.toIntOrNull() ?: 24000
                            events += LiveEvent.AudioChunk(pcm, rate)
                        }
                    }
                }
                part.text?.takeIf { it.isNotBlank() }?.let { text ->
                    events += LiveEvent.CaptionDelta(text)
                }
            }
            content.outputTranscription?.let { t ->
                if (t.text.isNotBlank()) events += LiveEvent.CaptionDelta(t.text)
            }
            content.inputTranscription?.let { t ->
                if (t.text.isNotBlank()) events += LiveEvent.SourceTranscript(t.text)
            }
            if (content.turnComplete == true) {
                events += LiveEvent.TurnComplete
            }
        }

        if (server.goAway != null) {
            events += LiveEvent.GoAway
        }

        return events
    }

    private fun decodeBase64(data: String): ByteArray? = try {
        Base64.getDecoder().decode(data)
    } catch (t: Throwable) {
        Log.w(TAG, "audio base64 decode failed: ${t.message}")
        null
    }
}
