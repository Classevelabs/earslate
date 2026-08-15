package com.classeve.earslate.session

import com.classeve.earslate.session.HeardLanguageTracker.Heard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeardLanguageTrackerTest {

    private fun tracker(mine: String = "en-US") = HeardLanguageTracker(mine)

    private fun them(heard: Heard?): Heard.Them {
        assertTrue("expected the other person, got $heard", heard is Heard.Them)
        return heard as Heard.Them
    }

    @Test
    fun `starts on english so an unrecognised speaker still has a target`() {
        assertEquals("en-US", tracker().current)
    }

    @Test
    fun `the first foreign speaker switches it immediately`() {
        val t = tracker()
        val heard = them(t.observe("आप कैसे हैं आज"))
        assertEquals("hi-IN", heard.bcp47)
        assertTrue(heard.changed)
        assertEquals("hi-IN", t.current)
    }

    /**
     * The transcript arrives a word or two at a time. No single fragment of
     * "hola que tal estas hoy" names a language on its own — "que" is Spanish,
     * French and Portuguese — but the turn as a whole does. Detecting on each
     * fragment separately is why Latin-script languages were never recognised.
     */
    @Test
    fun `fragments accumulate across a turn until the language is clear`() {
        val t = tracker()
        assertFalse(t.turnStarted)
        assertNull(t.observe("hola"))
        assertTrue(t.turnStarted)
        assertNull(t.observe(" que"))
        assertEquals("es-ES", them(t.observe(" tal estas hoy")).bcp47)
    }

    @Test
    fun `a new turn starts from nothing`() {
        val t = tracker()
        t.observe("hola que")
        t.endTurn()
        assertFalse(t.turnStarted)
        // "tal" alone is not three Spanish stopwords; if the previous turn's
        // text had leaked, this would resolve.
        assertNull(t.observe(" tal"))
    }

    /**
     * The microphone hears the device owner too, and the coordinator MUTES the
     * leg that would echo them — so "this is me" is a real answer, not a
     * silence. Mistaking it for the other person would aim our own outbound leg
     * at our own language and collapse the session to one direction.
     */
    @Test
    fun `my own speech is reported as me, and moves nothing`() {
        val t = tracker()
        assertEquals(Heard.Me, t.observe("how are you doing today with that"))
        assertEquals("en-US", t.current)
    }

    @Test
    fun `an unrecognisable utterance says nothing and leaves the target where it was`() {
        val t = tracker()
        t.observe("आप कैसे हैं आज")
        t.endTurn()
        assertNull(t.observe("mm"))
        assertEquals("hi-IN", t.current)
    }

    /**
     * A second language entering the conversation is followed as soon as it is
     * clear — not confirmed twice, not remembered as the first one. Someone who
     * switches from Hindi to Spanish mid-conversation expects the reply to
     * switch with them.
     */
    @Test
    fun `follows a change of language on the next turn`() {
        val t = tracker()
        assertEquals("hi-IN", them(t.observe("आप कैसे हैं आज")).bcp47)
        t.endTurn()
        val es = them(t.observe("hola que tal estas hoy"))
        assertEquals("es-ES", es.bcp47)
        assertTrue(es.changed)
        t.endTurn()
        assertEquals("hi-IN", them(t.observe("मैं ठीक हूँ धन्यवाद आप")).bcp47)
    }

    /**
     * Staying in the same language is still "them speaking" — the coordinator
     * needs that to mute the right leg — but it is not a change, so nothing is
     * re-aimed and no socket is paid for.
     */
    @Test
    fun `the same language again is them, unchanged`() {
        val t = tracker()
        assertTrue(them(t.observe("आप कैसे हैं आज")).changed)
        t.endTurn()
        val again = them(t.observe("मैं ठीक हूँ धन्यवाद"))
        assertEquals("hi-IN", again.bcp47)
        assertFalse(again.changed)
    }

    @Test
    fun `regional variants of my own language are still me`() {
        val t = HeardLanguageTracker("en-GB")
        assertEquals(Heard.Me, t.observe("how are you doing today with that"))
    }
}
