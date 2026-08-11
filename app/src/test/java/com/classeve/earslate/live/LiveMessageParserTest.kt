package com.classeve.earslate.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parser's contract is: **never lose a frame over one unexpected field.**
 *
 * That is not a theoretical nicety. Every event in a frame shares one
 * `decodeFromString` call, so a single required-but-absent field throws and the
 * catch-all in [LiveMessageParser.parse] then drops the entire frame — audio,
 * captions and turn markers together. Google really does send transcription
 * objects with no `text`, several times a second, so this was measured on-device
 * as a continuous stream of discarded audio.
 */
class LiveMessageParserTest {

    private companion object {
        /** 8 bytes of PCM: 00 01 02 03 04 05 06 07. */
        const val AUDIO_B64 = "AAECAwQFBgc="
    }

    @Test
    fun `a transcription with no text does not discard the frame's audio`() {
        val frame = """
            {"serverContent":{
              "modelTurn":{"parts":[{"inlineData":{
                "mimeType":"audio/pcm;rate=24000","data":"$AUDIO_B64"}}]},
              "outputTranscription":{"finished":true},
              "turnComplete":true}}
        """.trimIndent()

        val events = LiveMessageParser.parse(frame)

        val audio = events.filterIsInstance<LiveEvent.AudioChunk>()
        assertEquals("the audio in this frame must survive", 1, audio.size)
        assertEquals(8, audio.single().pcm24k.size)
        assertEquals(24_000, audio.single().sampleRateHz)
        assertTrue(
            "the turn marker must survive too",
            events.any { it is LiveEvent.TurnComplete },
        )
        assertTrue(
            "a textless transcription must not become a blank caption",
            events.none { it is LiveEvent.CaptionDelta },
        )
    }

    @Test
    fun `an input transcription with no text is tolerated`() {
        val events = LiveMessageParser.parse(
            """{"serverContent":{"inputTranscription":{"finished":true},"turnComplete":true}}""",
        )
        assertTrue(events.any { it is LiveEvent.TurnComplete })
    }

    @Test
    fun `a modelTurn with no parts is tolerated`() {
        val events = LiveMessageParser.parse(
            """{"serverContent":{"modelTurn":{},"turnComplete":true}}""",
        )
        assertTrue(events.any { it is LiveEvent.TurnComplete })
    }

    @Test
    fun `inlineData missing its mimeType does not discard the frame`() {
        val events = LiveMessageParser.parse(
            """{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"data":"$AUDIO_B64"}}]},
               "turnComplete":true}}""",
        )
        // A blank mimeType is not audio/*, so no chunk — but the frame survives.
        assertTrue(events.any { it is LiveEvent.TurnComplete })
    }

    @Test
    fun `transcription text becomes a caption delta`() {
        val events = LiveMessageParser.parse(
            """{"serverContent":{"outputTranscription":{"text":"hola"}}}""",
        )
        assertEquals(
            listOf("hola"),
            events.filterIsInstance<LiveEvent.CaptionDelta>().map { it.text },
        )
    }

    @Test
    fun `setupComplete is recognised`() {
        assertTrue(
            LiveMessageParser.parse("""{"setupComplete":{}}""")
                .any { it is LiveEvent.SetupComplete },
        )
    }

    @Test
    fun `unknown top-level fields are ignored rather than fatal`() {
        val events = LiveMessageParser.parse(
            """{"somethingGoogleAddedLater":{"a":1},"serverContent":{"turnComplete":true}}""",
        )
        assertTrue(events.any { it is LiveEvent.TurnComplete })
    }

    @Test
    fun `malformed json yields no events and does not throw`() {
        assertEquals(emptyList<LiveEvent>(), LiveMessageParser.parse("{not json"))
    }

    @Test
    fun `sample rate falls back to 24k when the mimeType omits it`() {
        val events = LiveMessageParser.parse(
            """{"serverContent":{"modelTurn":{"parts":[{"inlineData":{
               "mimeType":"audio/pcm","data":"$AUDIO_B64"}}]}}}""",
        )
        assertEquals(24_000, events.filterIsInstance<LiveEvent.AudioChunk>().single().sampleRateHz)
    }
}
