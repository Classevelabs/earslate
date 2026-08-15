package com.classeve.earslate.live

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The source-side transcript is the ONLY place the detected input language
 * exists — the translate model never reports what it decided. Everything that
 * chooses which language to speak back in hangs off these two facts, and both
 * were previously true by accident: the parser dropped `inputTranscription`
 * entirely, and the transcription config was optional.
 */
class SourceTranscriptTest {

    @Test
    fun `the parser surfaces what was heard, not only what was said back`() {
        val events = LiveMessageParser.parse(
            """{"serverContent":{"inputTranscription":{"text":"आप कैसे हैं"},
               "outputTranscription":{"text":"how are you"}}}""",
        )
        val heard = events.filterIsInstance<LiveEvent.SourceTranscript>()
        assertEquals(1, heard.size)
        assertEquals("आप कैसे हैं", heard.single().text)
        // And it must not be mistaken for a caption, or the transcript pane
        // would show both languages spliced together.
        assertEquals(
            listOf("how are you"),
            events.filterIsInstance<LiveEvent.CaptionDelta>().map { it.text },
        )
    }

    @Test
    fun `a textless input transcription still produces nothing`() {
        val events = LiveMessageParser.parse(
            """{"serverContent":{"inputTranscription":{"finished":true},"turnComplete":true}}""",
        )
        assertTrue(events.none { it is LiveEvent.SourceTranscript })
        assertTrue(events.any { it is LiveEvent.TurnComplete })
    }

    @Test
    fun `the session asks for the input transcript that detection reads`() {
        val setup = JSONObject(
            LiveSessionConfigFactory.buildSetup(
                model = "gemini-3.5-live-translate-preview",
                targetLanguageCode = "en",
                echoTargetLanguage = false,
                captionsEnabled = true,
            ),
        ).getJSONObject("setup")
        assertTrue(
            "without inputAudioTranscription nothing can tell which language was spoken",
            setup.has("inputAudioTranscription"),
        )
    }
}
