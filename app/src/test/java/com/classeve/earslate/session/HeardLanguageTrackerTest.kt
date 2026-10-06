package com.classeve.earslate.session

import com.classeve.earslate.session.HeardLanguageTracker.Heard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeardLanguageTrackerTest {

    private fun tracker(mine: String = "en-US", theirs: String = "en-US") = HeardLanguageTracker(mine, theirs)

    @Test
    fun `my own language is me, whatever the region`() {
        val t = HeardLanguageTracker("en-GB", "es")
        assertEquals(Heard.Me, t.report("en"))
        assertEquals(Heard.Me, t.report("en-US"))
        assertEquals("es", t.current)
    }

    @Test
    fun `the first language heard replaces the starting guess at once`() {
        val t = tracker()
        assertEquals(Heard.Them("es", changed = true), t.report("es"))
        assertEquals("es", t.current)
    }

    @Test
    fun `the same language again is them, unchanged`() {
        val t = tracker()
        t.report("es")
        assertEquals(Heard.Them("es", changed = false), t.report("es"))
        assertEquals(Heard.Them("es", changed = false), t.report("es-MX"))
    }

    @Test
    fun `hearing what was already expected is no change`() {
        val t = tracker(mine = "en-US", theirs = "es-ES")
        assertEquals(Heard.Them("es", changed = false), t.report("es"))
    }

    // Spanish heard as Portuguese for one fragment must not send my reply out in Portuguese.
    @Test
    fun `one stray fragment in another language does not move a language that was heard`() {
        val t = tracker()
        t.report("es")
        assertEquals(Heard.Them("es", changed = false), t.report("pt"))
        assertEquals(Heard.Them("es", changed = false), t.report("es"))
        assertEquals(Heard.Them("es", changed = false), t.report("pt"))
        assertEquals("es", t.current)
    }

    @Test
    fun `a second person's language is followed once it is said twice`() {
        val t = tracker()
        t.report("es")
        assertEquals(Heard.Them("es", changed = false), t.report("hi"))
        assertEquals(Heard.Them("hi", changed = true), t.report("hi"))
        assertEquals("hi", t.current)
    }

    @Test
    fun `my own speech in between does not count towards a change`() {
        val t = tracker()
        t.report("es")
        t.report("hi")
        assertEquals(Heard.Me, t.report("en"))
        assertEquals(Heard.Them("es", changed = false), t.report("hi"))
        assertEquals(Heard.Them("hi", changed = true), t.report("hi"))
    }

    // What the translate model reported on 2026-10-06 for speech in each of these.
    @Test
    fun `a language is itself under the name the model gives it`() {
        assertTrue("Norwegian", HeardLanguageTracker.sameLanguage("no", "nb-NO"))
        assertTrue("Filipino", HeardLanguageTracker.sameLanguage("tl", "fil-PH"))
        assertTrue("Malay, which the model hears as Indonesian", HeardLanguageTracker.sameLanguage("id", "ms-MY"))
        assertTrue(HeardLanguageTracker.sameLanguage("nb-NO", "no"))
        assertFalse("Swedish is not Norwegian", HeardLanguageTracker.sameLanguage("sv", "nb-NO"))
        assertFalse("Danish is not Norwegian", HeardLanguageTracker.sameLanguage("da", "nb-NO"))
        assertFalse(HeardLanguageTracker.sameLanguage("tl", "id-ID"))
    }

    @Test
    fun `a Norwegian speaker is not taken for the other person`() {
        val tracker = HeardLanguageTracker(myLanguage = "nb-NO", initialTheirs = "en-US")
        assertEquals(HeardLanguageTracker.Heard.Me, tracker.report("no"))
        assertEquals("en-US", tracker.current)
    }

    @Test
    fun `language comparison ignores region and case, and nothing matches an empty tag`() {
        assertTrue(HeardLanguageTracker.sameLanguage("es", "es-ES"))
        assertTrue(HeardLanguageTracker.sameLanguage("ZH-Hant", "zh-CN"))
        assertFalse(HeardLanguageTracker.sameLanguage("es", "pt"))
        assertFalse(HeardLanguageTracker.sameLanguage("", ""))
    }
}

class RecentWordsTest {

    @Test
    fun `words are judged once there are enough of them`() {
        val words = RecentWords()
        assertNull(words.observe("que"))
        assertEquals("es-ES", words.observe(" no está con nosotros porque"))
    }

    // "Clients should not insert unconditional spaces between deltas."
    @Test
    fun `a word cut across two fragments is one word`() {
        val words = RecentWords()
        words.observe("que no est")
        // "es" and "ta" on their own would be two stray words, and "es" is Spanish for "is".
        assertEquals("es-ES", words.observe("á con noso"))
        assertEquals("es-ES", words.observe("tros porque"))

        val english = RecentWords()
        english.observe("yes th")
        english.observe("at is wh")
        assertEquals("en-US", english.observe("at they have and there was"))

        // With a space put between the pieces this is "th e an d": no words at all.
        val pieces = RecentWords()
        pieces.observe("th")
        pieces.observe("e an")
        assertEquals("en-US", pieces.observe("d"))
    }

    @Test
    fun `non-Latin scripts are recognised from a couple of characters`() {
        assertEquals("hi-IN", RecentWords().observe("नमस्ते क्या आप"))
    }

    // The whole conversation used to be judged as one text, so the first language won for ever.
    @Test
    fun `a change of speaker is heard within a few words, not outvoted by what came before`() {
        val words = RecentWords()
        assertEquals("es-ES", words.observe("hola que tal como está usted muy bien gracias por todo"))
        assertEquals("en-US", words.observe(" yes that is what they have and there was just about"))
    }

    @Test
    fun `after a silence the next words are judged on their own`() {
        val words = RecentWords()
        words.observe("hola que tal como está usted")
        words.clear()
        assertNull("one word is not enough again", words.observe("the"))
    }

    @Test
    fun `an unrecognisable fragment says nothing`() {
        val words = RecentWords()
        assertNull(words.observe("hm"))
        assertNull(words.observe(" ok"))
    }
}
