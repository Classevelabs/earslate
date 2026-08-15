package com.classeve.earslate.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeardLanguageTrackerTest {

    private fun tracker(mine: String = "en-US") = HeardLanguageTracker(mine)

    @Test
    fun `starts on english so an unrecognised speaker still has a target`() {
        assertEquals("en-US", tracker().current)
    }

    @Test
    fun `the first foreign speaker switches it immediately`() {
        val t = tracker()
        assertEquals("hi-IN", t.observe("आप कैसे हैं आज"))
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
        assertNull(t.observe("hola"))
        assertNull(t.observe(" que"))
        assertEquals("es-ES", t.observe(" tal estas hoy"))
    }

    @Test
    fun `a new turn starts from nothing`() {
        val t = tracker()
        t.observe("hola que")
        t.endTurn()
        // "tal estas" alone is not three Spanish stopwords; if the previous
        // turn's text had leaked, this would resolve.
        assertNull(t.observe(" tal"))
    }

    /**
     * The microphone hears the device owner too. Treating that as "the other
     * person" would aim our own outbound leg at our own language, which
     * collapses the session to one direction and silences the reply.
     */
    @Test
    fun `my own speech is never mistaken for theirs`() {
        val t = tracker()
        assertNull(t.observe("how are you doing today with that"))
        assertEquals("en-US", t.current)
    }

    @Test
    fun `an unrecognisable utterance leaves the target where it was`() {
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
        assertEquals("hi-IN", t.observe("आप कैसे हैं आज"))
        t.endTurn()
        assertEquals("es-ES", t.observe("hola que tal estas hoy"))
        t.endTurn()
        assertEquals("hi-IN", t.observe("मैं ठीक हूँ धन्यवाद आप"))
    }

    @Test
    fun `staying in the same language reports no change`() {
        val t = tracker()
        assertEquals("hi-IN", t.observe("आप कैसे हैं आज"))
        t.endTurn()
        assertNull(t.observe("मैं ठीक हूँ धन्यवाद"))
    }

    @Test
    fun `regional variants of my own language are still me`() {
        val t = HeardLanguageTracker("en-GB")
        assertNull(t.observe("how are you doing today with that"))
    }
}
