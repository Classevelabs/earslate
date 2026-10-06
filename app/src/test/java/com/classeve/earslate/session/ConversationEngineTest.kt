package com.classeve.earslate.session

import com.classeve.earslate.live.LiveEvent
import com.classeve.earslate.testing.RecordingSink
import com.classeve.earslate.testing.TestAudio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * I speak English; they speak Spanish. The inbound leg speaks English for
 * them, the outbound leg speaks Spanish for me, and both hear everything.
 */
class ConversationEngineTest {

    private val inbound = 1
    private val outbound = 2

    private inner class Scene(follows: Boolean = true, theirs: String = "es") {
        val sink = RecordingSink()
        val engine = ConversationEngine("en", theirs, follows, sink)
        var now = 0L

        init {
            engine.legOpened(inbound, LegRole.INBOUND)
            engine.legOpened(outbound, LegRole.OUTBOUND)
        }

        /** Let [ms] pass, ticking as the session does. */
        fun pass(ms: Int) {
            repeat(ms / 50) {
                now += 50
                sink.nowMs = now
                engine.onTick(now)
            }
        }

        /** Both legs hear the same microphone, so by default both report. */
        fun heard(text: String, language: String?, leg: Int? = null) {
            for (id in listOfNotNull(leg).ifEmpty { listOf(inbound, outbound) }) {
                engine.onEvent(id, LiveEvent.SourceTranscript(text, language), now)
            }
        }

        /** [blocks] quarter-seconds of speech from [leg], arriving in real time. */
        fun speaks(leg: Int, blocks: Int = 4) = repeat(blocks) {
            engine.onEvent(leg, LiveEvent.AudioChunk(TestAudio.tone(250), 24_000), now)
            pass(250)
        }

        fun quiet(leg: Int, blocks: Int = 4) = repeat(blocks) {
            engine.onEvent(leg, LiveEvent.AudioChunk(TestAudio.silence(250), 24_000), now)
            pass(250)
        }

        fun caption(leg: Int, text: String) = engine.onEvent(leg, LiveEvent.CaptionDelta(text), now)
    }

    // ── never speak the language you just heard ─────────────────────────

    @Test
    fun `when they speak, their translation is heard and the echo in their own language is not`() {
        val s = Scene()
        s.heard("Hola, buenos días", "es")
        s.speaks(inbound, 6)
        s.speaks(outbound, 6)

        assertEquals(1_500, s.sink.heardMs(inbound))
        assertEquals("the model repeating Spanish back", 0, s.sink.heardMs(outbound))
        assertEquals("the echo still occupies its lane, as silence", 12, s.sink.played.size)
    }

    @Test
    fun `when I speak, my translation is heard and my own words are not repeated to me`() {
        val s = Scene()
        s.heard("Sure, it is about ten minutes", "en")
        s.speaks(outbound, 6)
        s.speaks(inbound, 6)

        assertEquals(1_500, s.sink.heardMs(outbound))
        assertEquals(0, s.sink.heardMs(inbound))
    }

    @Test
    fun `a third language is translated for me only, not also into the other person's language`() {
        val s = Scene()
        s.heard("नमस्ते", "hi")
        s.speaks(inbound, 4)
        s.speaks(outbound, 4)

        assertEquals(1_000, s.sink.heardMs(inbound))
        assertEquals(0, s.sink.heardMs(outbound))
    }

    @Test
    fun `the model repeating its last verdict with no words does not change who is speaking`() {
        val s = Scene()
        s.heard("Hola", "es")
        s.heard("", "en")
        s.heard("   ", "en")
        assertEquals(Speaker.THEM, s.engine.hears(inbound))
        assertEquals(Speaker.THEM, s.engine.hears(outbound))
    }

    // ── before anyone is identified ─────────────────────────────────────

    @Test
    fun `speech that starts before the speaker is known may be heard, and is cut off the moment it turns out to be an echo`() {
        val s = Scene()
        s.caption(outbound, "Hola")
        s.speaks(outbound, 2)
        assertEquals("allowed on sufferance", 500, s.sink.heardMs(outbound))
        assertEquals("Hola", s.sink.pending(outbound))

        s.heard("Hola, ¿cómo estás?", "es")
        assertEquals("what it had queued is silenced", listOf(outbound), s.sink.mutedQueued)
        assertEquals("and its caption is thrown away", listOf(outbound), s.sink.discarded)

        s.speaks(outbound, 4)
        assertEquals("and nothing more of it is heard", 500, s.sink.heardMs(outbound))
    }

    @Test
    fun `a guess that turns out right simply carries on`() {
        val s = Scene()
        s.speaks(inbound, 2)
        s.heard("Hola", "es")
        s.speaks(inbound, 4)

        assertEquals(1_500, s.sink.heardMs(inbound))
        assertTrue(s.sink.mutedQueued.isEmpty())
    }

    // ── a change of speaker ─────────────────────────────────────────────

    // In the recording, the reply's echo began 260 ms after the translation before it ended.
    @Test
    fun `when I answer at once, the end of their translation still plays, and my echo after it does not`() {
        val s = Scene()
        s.heard("Necesito llegar antes de las cinco", "es")
        s.speaks(inbound, 4)
        // I start talking; the tail of their translation is still coming.
        s.heard("Sure, it is", "en")
        s.speaks(inbound, 3)
        val tail = s.sink.heardMs(inbound)
        assertEquals("their last words are not cut off by my first", 1_750, tail)

        s.quiet(inbound, 1)
        s.speaks(inbound, 6)
        assertEquals("what follows the pause is my own English, and is silenced", tail, s.sink.heardMs(inbound))
    }

    @Test
    fun `their echo ends at the first pause, and my translation after it is heard`() {
        val s = Scene()
        s.heard("Muchas gracias", "es")
        s.speaks(outbound, 3)
        assertEquals(0, s.sink.heardMs(outbound))

        s.heard("You are welcome", "en")
        s.speaks(outbound, 1)
        s.quiet(outbound, 1)
        s.speaks(outbound, 4)
        assertEquals("the translation of what I said", 1_000, s.sink.heardMs(outbound))
    }

    // Without a pause to mark the boundary, a silenced leg must not stay silenced for a whole sentence.
    @Test
    fun `with no pause at all, the old decision still lapses`() {
        val s = Scene()
        s.heard("Muchas gracias", "es")
        s.speaks(outbound, 2)
        s.heard("You are welcome", "en")
        val flippedAt = s.now
        s.speaks(outbound, 12)

        val firstHeard = s.sink.played.first { it.leg == outbound && it.voiced }.atMs
        assertTrue(
            "heard again ${firstHeard - flippedAt} ms after I began",
            firstHeard - flippedAt in 1_250..ConversationEngine.STALE_RUN_MAX_MS,
        )
    }

    @Test
    fun `a pause inside one person's sentence changes nothing`() {
        val s = Scene()
        s.heard("Hola, buenos días", "es")
        s.speaks(inbound, 3)
        s.quiet(inbound, 2)
        s.speaks(inbound, 3)
        assertEquals(1_500, s.sink.heardMs(inbound))
    }

    // ── captions ────────────────────────────────────────────────────────

    @Test
    fun `text that arrives just ahead of its audio is shown when the audio starts`() {
        val s = Scene()
        s.heard("Hola", "es")
        s.caption(inbound, "Hello,")
        assertEquals("", s.sink.pending(inbound))
        s.speaks(inbound, 1)
        assertEquals("Hello,", s.sink.pending(inbound))
        s.caption(inbound, " good morning.")
        assertEquals("Hello, good morning.", s.sink.pending(inbound))
    }

    // Neither model says when a sentence ends, so nothing was ever committed.
    @Test
    fun `a line is committed when its speaker has been silent for long enough`() {
        val s = Scene()
        s.heard("Hola", "es")
        s.speaks(inbound, 2)
        s.caption(inbound, "Hello, good morning.")
        s.quiet(inbound, 2)
        assertTrue("a short pause does not end the line", s.sink.lines.isEmpty())
        s.quiet(inbound, 1)
        assertEquals(listOf(inbound to "Hello, good morning."), s.sink.lines)
    }

    @Test
    fun `an echo's text is never shown`() {
        val s = Scene()
        s.heard("Hola", "es")
        s.caption(outbound, "Hola")
        s.speaks(outbound, 2)
        s.caption(outbound, ", buenos días")
        s.quiet(outbound, 4)

        assertEquals("", s.sink.pending(outbound))
        assertTrue(s.sink.lines.isEmpty())
    }

    @Test
    fun `the two directions build separate lines`() {
        val s = Scene()
        s.heard("Hola", "es")
        s.speaks(inbound, 1)
        s.caption(inbound, "Hello.")
        s.heard("Hi there", "en")
        s.caption(outbound, "Hola.")
        s.quiet(inbound, 1)
        s.speaks(outbound, 1)
        s.quiet(inbound, 3)
        s.quiet(outbound, 3)

        assertEquals(listOf(inbound to "Hello.", outbound to "Hola."), s.sink.lines)
    }

    @Test
    fun `after the speaker changes, a leg's late text is for what it says next, not the line it is finishing`() {
        val s = Scene()
        s.heard("Gracias", "es")
        s.speaks(inbound, 2)
        s.caption(inbound, "Thank you.")
        s.heard("You are welcome", "en")
        // The inbound leg now transcribes my English; that is echo text.
        s.caption(inbound, " You are welcome.")
        s.quiet(inbound, 1)

        assertEquals(listOf(inbound to "Thank you."), s.sink.lines)
        assertEquals("", s.sink.pending(inbound))
    }

    @Test
    fun `text that no audio ever follows is dropped`() {
        val s = Scene()
        s.caption(inbound, "Hello")
        s.pass(3_000)
        s.heard("Hola", "es")
        s.speaks(inbound, 1)
        assertEquals("", s.sink.pending(inbound))
    }

    // ── a provider that sends nothing while silent ──────────────────────

    @Test
    fun `an utterance ends by the clock when no silence is sent`() {
        val s = Scene()
        s.heard("Hola", "es")
        s.speaks(inbound, 2)
        s.caption(inbound, "Hello.")
        // The last block arrived 250 ms ago; the utterance ends a second after it.
        s.pass(700)
        assertTrue(s.sink.lines.isEmpty())
        s.pass(100)
        assertEquals(listOf(inbound to "Hello."), s.sink.lines)
    }

    // Recorded live: nothing at all arrived for 3.4 s in the middle of a sentence.
    @Test
    fun `a stall in the arrivals of a provider that sends its own silence does not end the utterance`() {
        val s = Scene()
        s.heard("Hola", "es")
        s.quiet(inbound, 1)
        s.speaks(inbound, 2)
        s.caption(inbound, "Can you tell me where")
        s.pass(3_400)
        s.caption(inbound, " the station is?")
        s.speaks(inbound, 2)
        assertTrue("the sentence was not cut in two", s.sink.lines.isEmpty())
        s.quiet(inbound, 3)
        assertEquals(listOf(inbound to "Can you tell me where the station is?"), s.sink.lines)
    }

    @Test
    fun `with words but no language code, each leg works out the speaker from the words it heard`() {
        val s = Scene()
        s.heard("que", null)
        assertEquals("one word is not enough yet", Speaker.UNKNOWN, s.engine.hears(inbound))
        s.heard(" tal como está usted", null)
        assertEquals(Speaker.THEM, s.engine.hears(inbound))
        assertEquals(Speaker.THEM, s.engine.hears(outbound))
        s.pass(2_000)
        s.heard("yes that is what they have and there was just about", null)
        assertEquals(Speaker.ME, s.engine.hears(inbound))
    }

    // Recorded: one leg heard "नमस्ते" as Hindi, the other wrote it "Namaste" and called it English.
    @Test
    fun `when the two legs disagree about a word, each is judged by what it heard itself`() {
        val s = Scene()
        s.heard("Sí, hay uno muy bueno", "es")
        s.speaks(inbound, 2)
        // I begin to answer. The outbound leg hears English; the inbound leg mishears it as Spanish.
        s.heard("Okay", "en", leg = outbound)
        s.heard("Oké", "es", leg = inbound)
        s.speaks(outbound, 4)

        assertEquals("the leg that heard me translates me, whatever the other one thought", 1_000, s.sink.heardMs(outbound))
        assertEquals(Speaker.ME, s.engine.hears(outbound))
        assertEquals(Speaker.THEM, s.engine.hears(inbound))
    }

    // ── following their language ────────────────────────────────────────

    @Test
    fun `the first language heard from them is reported at once`() {
        val s = Scene(theirs = "en")
        s.heard("Hola", "es")
        assertEquals(listOf("es"), s.sink.heard)
    }

    @Test
    fun `a new language is reported once it has been said twice`() {
        val s = Scene()
        s.heard("Hola", "es")
        // Both legs report the fragment; that is one hearing of it, not two.
        s.heard("नमस्ते", "hi")
        assertTrue(s.sink.heard.isEmpty())
        s.heard("क्या आप", "hi")
        assertEquals(listOf("hi"), s.sink.heard)
        assertEquals("hi", s.engine.theirLanguage)
    }

    @Test
    fun `a pinned language is never moved by what is heard`() {
        val s = Scene(follows = false)
        s.heard("नमस्ते", "hi")
        s.heard("क्या आप", "hi")
        s.heard("बता सकते हैं", "hi")
        assertTrue(s.sink.heard.isEmpty())
        assertEquals("but it is still known to be them", Speaker.THEM, s.engine.hears(inbound))
    }

    // ── state ───────────────────────────────────────────────────────────

    @Test
    fun `speaking is announced when a translation that may be heard starts, and when it ends`() {
        val s = Scene()
        s.heard("Hola", "es")
        s.speaks(outbound, 2)
        assertTrue("an echo is not speaking", s.sink.speaking.isEmpty())
        s.speaks(inbound, 2)
        assertEquals(listOf(true), s.sink.speaking)
        s.quiet(inbound, 3)
        assertEquals(listOf(true, false), s.sink.speaking)
    }

    @Test
    fun `a leg that closes mid-sentence commits what it had`() {
        val s = Scene()
        s.heard("Hola", "es")
        s.speaks(inbound, 1)
        s.caption(inbound, "Hello")
        assertTrue(s.engine.isMidRun(inbound))
        s.engine.legClosed(inbound)

        assertEquals(listOf(inbound to "Hello"), s.sink.lines)
        assertFalse(s.engine.isMidRun(inbound))
        assertEquals(listOf(true, false), s.sink.speaking)
    }

    @Test
    fun `the rule itself`() {
        assertTrue(ConversationEngine.mayRoleSpeak(LegRole.INBOUND, Speaker.THEM))
        assertFalse(ConversationEngine.mayRoleSpeak(LegRole.INBOUND, Speaker.ME))
        assertTrue(ConversationEngine.mayRoleSpeak(LegRole.OUTBOUND, Speaker.ME))
        assertFalse(ConversationEngine.mayRoleSpeak(LegRole.OUTBOUND, Speaker.THEM))
        assertTrue(ConversationEngine.mayRoleSpeak(LegRole.INBOUND, Speaker.UNKNOWN))
        assertTrue(ConversationEngine.mayRoleSpeak(LegRole.OUTBOUND, Speaker.UNKNOWN))
    }
}
