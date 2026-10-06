package com.classeve.earslate.testing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.util.Base64

/**
 * A real two-leg session against Gemini Live Translate, recorded frame by
 * frame with arrival times. The audio payloads were reduced to their length
 * and peak when recording; they are rebuilt here as a tone of that loudness,
 * so every frame goes through the app's own parser exactly as it arrived.
 */
class RecordedSession private constructor(
    val mine: String,
    val theirs: String,
    val frames: List<Frame>,
    val utterances: List<Utterance>,
) {
    /** One server frame: when it arrived, which leg it arrived on, and its JSON. */
    class Frame(val atMs: Long, val leg: String, val json: String)

    /** One stretch of speech into the microphone. [language] is what was spoken. */
    class Utterance(val clip: String, val fromMs: Long, val toMs: Long) {
        val language: String get() = clip.substringBefore('_').takeWhile { it.isLetter() }
    }

    val endMs: Long get() = frames.last().atMs

    companion object {
        private val json = Json

        fun load(name: String): RecordedSession {
            val lines = checkNotNull(RecordedSession::class.java.getResourceAsStream("/fixtures/$name")) {
                "missing fixture $name"
            }.bufferedReader().readLines().filter { it.isNotBlank() }
            val header = json.parseToJsonElement(lines.first()).jsonObject
            val frames = ArrayList<Frame>()
            val utterances = ArrayList<Utterance>()
            for (line in lines.drop(1)) {
                val record = json.parseToJsonElement(line).jsonObject
                val at = record.getValue("t").jsonPrimitive.long
                record["mic"]?.jsonObject?.let { mic ->
                    utterances += Utterance(
                        clip = mic.getValue("clip").jsonPrimitive.content,
                        fromMs = mic.getValue("from").jsonPrimitive.long,
                        toMs = mic.getValue("to").jsonPrimitive.long,
                    )
                }
                record["frame"]?.let { frame ->
                    frames += Frame(at, record.getValue("leg").jsonPrimitive.content, restoreAudio(frame).toString())
                }
            }
            return RecordedSession(
                mine = header.getValue("mine").jsonPrimitive.content,
                theirs = header.getValue("theirs").jsonPrimitive.content,
                frames = frames,
                utterances = utterances,
            )
        }

        private fun restoreAudio(element: JsonElement): JsonElement = when (element) {
            is JsonObject -> {
                val stub = element["inlineData"]?.jsonObject?.get("data") as? JsonObject
                if (stub != null) {
                    val inline = element.getValue("inlineData").jsonObject
                    val rate = Regex("rate=(\\d+)").find(inline.getValue("mimeType").jsonPrimitive.content)
                        ?.groupValues?.get(1)?.toInt() ?: 24_000
                    val pcm = TestAudio.toneOf(
                        bytes = stub.getValue("bytes").jsonPrimitive.int,
                        rateHz = rate,
                        peak = stub.getValue("peak").jsonPrimitive.int,
                    )
                    JsonObject(
                        element + ("inlineData" to JsonObject(inline + ("data" to JsonPrimitive(Base64.getEncoder().encodeToString(pcm))))),
                    )
                } else {
                    JsonObject(element.mapValues { restoreAudio(it.value) })
                }
            }
            is JsonArray -> JsonArray(element.map(::restoreAudio))
            else -> element
        }
    }
}
