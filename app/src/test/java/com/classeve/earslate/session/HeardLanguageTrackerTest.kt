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
        assertNull(t.observe("mm"))
        assertEquals("hi-IN", t.current)
    }

    /**
     * Changing costs a socket teardown and a fresh credential on the user's own
     * key, so after the first switch a second agreeing utterance is required.
     */
    @Test
    fun `a later change needs confirming before it is paid for`() {
        val t = tracker()
        t.observe("आप कैसे हैं आज")
        assertNull(t.observe("hola que tal estas hoy"))
        assertEquals("hi-IN", t.current)
        assertEquals("es-ES", t.observe("gracias por una de las cosas"))
        assertEquals("es-ES", t.current)
    }

    @Test
    fun `a one-off misread does not drag the conversation with it`() {
        val t = tracker()
        t.observe("आप कैसे हैं आज")
        assertNull(t.observe("hola que tal estas hoy"))
        assertNull(t.observe("आप कैसे हैं आज"))
        assertNull(t.observe("bonjour comment vous allez aujourd hui"))
        assertEquals("hi-IN", t.current)
    }

    @Test
    fun `staying in the same language reports no change`() {
        val t = tracker()
        assertEquals("hi-IN", t.observe("आप कैसे हैं आज"))
        assertNull(t.observe("मैं ठीक हूँ धन्यवाद"))
    }

    @Test
    fun `regional variants of my own language are still me`() {
        val t = HeardLanguageTracker("en-GB")
        assertNull(t.observe("how are you doing today with that"))
    }
}
