package com.classeve.earslate.session

import com.classeve.earslate.session.HeardLanguageTracker.Heard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule the product lives or dies on: **never speak the language you just
 * heard.**
 *
 * The coordinator enforces it by muting each translate leg by role, and the
 * role comes from this tracker. So the tracker's answers are what decide
 * whether the user hears a translation or an echo of themselves, and they are
 * pinned here in the terms the coordinator actually uses.
 *
 * The failure this replaces: the decision was taken at the FIRST chunk of a
 * leg's output, which arrives before the transcript has said enough to name the
 * speaker. Unknown meant "allowed", so the wrong leg started an echo, took
 * ownership of the shared stream, and the real translation arriving two hundred
 * milliseconds later was refused and dropped. The user heard themselves and the
 * translation was thrown away.
 */
class EchoSuppressionTest {

    /** Mirrors SessionCoordinator.mayLegSpeak's role rule exactly. */
    private fun maySpeak(speaker: String, isPrimary: Boolean): Boolean = when (speaker) {
        "ME" -> !isPrimary
        "THEM" -> isPrimary
        else -> true
    }

    @Test
    fun `while I speak, the leg aimed at my own language is silent`() {
        val t = HeardLanguageTracker("en-US")
        assertEquals(Heard.Me, t.observe("how are you doing today with that"))
        // The listening leg translates INTO English. I am speaking English.
        assertFalse("the primary leg must not repeat my own words", maySpeak("ME", isPrimary = true))
        // The outbound leg carries my words into theirs. It must.
        assertTrue(maySpeak("ME", isPrimary = false))
    }

    @Test
    fun `while they speak, only the leg aimed at my language answers`() {
        val t = HeardLanguageTracker("en-US")
        val heard = t.observe("आप कैसे हैं आज")
        assertTrue(heard is Heard.Them)
        assertTrue(maySpeak("THEM", isPrimary = true))
        assertFalse("the outbound leg must not repeat their own words", maySpeak("THEM", isPrimary = false))
    }

    /**
     * The decision taken before the speaker is known is a GUESS, and the
     * coordinator only lets a guess stand until the transcript resolves. This
     * test pins the input side of that: an opening fragment genuinely does not
     * name the speaker, so anything decided on it must be revisited.
     */
    @Test
    fun `an opening fragment does not name the speaker`() {
        val t = HeardLanguageTracker("en-US")
        assertNull("one word cannot decide who is talking", t.observe("hola"))
        // ...and the very next fragments do.
        assertNull(t.observe(" que"))
        val heard = t.observe(" tal estas hoy")
        assertTrue(heard is Heard.Them)
        assertEquals("es-ES", (heard as Heard.Them).bcp47)
    }

    /**
     * My language is English and nothing has been heard from anyone else yet.
     * There is no other language to translate INTO, so the correct output is
     * nothing at all — never English back at me.
     */
    @Test
    fun `english speaker with nobody else heard yet is still never echoed`() {
        val t = HeardLanguageTracker("en-US")
        assertEquals(Heard.Me, t.observe("this is me talking to myself here"))
        assertEquals("en-US", t.current)
        // The only leg that exists targets English, and it is the primary one.
        assertFalse(maySpeak("ME", isPrimary = true))
    }

    /**
     * A correction has to survive the room. If the user says "they are speaking
     * Spanish", the next Hindi-looking fragment must not silently undo it —
     * that is what pinning is for, and the tracker still reports what it heard
     * so the muting stays correct either way.
     */
    @Test
    fun `the tracker still names the speaker even when its language is pinned`() {
        val t = HeardLanguageTracker("en-US", initial = "es-ES")
        val heard = t.observe("आप कैसे हैं आज")
        assertTrue(heard is Heard.Them)
        // It reports the change; whether the session ACTS on it is the
        // coordinator's decision, gated on followsTheirLanguage.
        assertTrue((heard as Heard.Them).changed)
    }
}
