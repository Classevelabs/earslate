package com.classeve.earslate.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeardLanguageTrackerTest {

    private fun tracker(mine: String = "pa-IN", theirs: String? = null) = HeardLanguageTracker(mine, theirs)

    // What I say goes out in their language and no other. Before anyone has
    // been heard there is no such language, and English is not a fair guess.
    @Test
    fun `nobody has a language until somebody has been heard`() {
        val t = tracker()
        assertNull(t.current)
        assertNull(t.offer)
    }

    @Test
    fun `my own language is never offered as theirs, whatever the region`() {
        val t = HeardLanguageTracker("en-GB", null)
        t.heard("en", 0)
        t.heard("en-US", 100)
        assertNull(t.offer)
        assertTrue(t.isMine("en-AU"))
    }

    @Test
    fun `the first language heard is on offer at once, and theirs only when it is taken`() {
        val t = tracker()
        t.heard("en", 1_000)
        assertEquals("en", t.offer?.language)
        assertEquals("since its first word", 1_000L, t.offer?.sinceMs)
        assertNull("hearing a name is not yet knowing who speaks it", t.current)

        assertEquals("en", t.accept())
        assertEquals("en", t.current)
        assertNull(t.offer)
        assertTrue(t.isTheirs("en-US"))
    }

    @Test
    fun `the language they already speak is not offered again`() {
        val t = tracker(theirs = "es-ES")
        t.heard("es", 0)
        t.heard("es-MX", 100)
        assertNull(t.offer)
        assertEquals("es-ES", t.current)
    }

    // Spanish heard as Portuguese for one fragment must not send my reply out in Portuguese.
    @Test
    fun `one stray fragment in another language is not an offer to leave the one in use`() {
        val t = tracker(theirs = "es")
        t.heard("pt", 0)
        assertNull(t.offer)
        t.heard("es", 100)
        t.heard("pt", 200)
        assertNull("nor are two strays with their own language between them", t.offer)
        assertEquals("es", t.current)
    }

    @Test
    fun `a second person's language is on offer once it has been said twice running`() {
        val t = tracker(theirs = "en")
        t.heard("zh", 5_000)
        assertNull(t.offer)
        t.heard("zh", 6_000)
        assertEquals("zh", t.offer?.language)
        assertEquals("from the first of the two", 5_000L, t.offer?.sinceMs)
        assertEquals("zh", t.accept())
        assertEquals("zh", t.current)
    }

    // Somebody new starting to talk is not a misheard fragment in the middle of a sentence.
    @Test
    fun `a language somebody begins to speak in is on offer from its first words`() {
        val t = tracker(theirs = "en")
        t.heard("zh", 5_000, beginsSpeech = true)
        assertEquals("zh", t.offer?.language)

        t.withdraw()
        t.heard("zh", 6_000)
        assertNull("the rest of that speech is the middle of it", t.offer)
    }

    @Test
    fun `my own speech in between does not count towards a change`() {
        val t = tracker(mine = "en", theirs = "es")
        t.heard("hi", 0)
        t.heard("en", 100)
        t.heard("hi", 200)
        assertNull(t.offer)
        t.heard("hi", 300)
        assertEquals("hi", t.offer?.language)
    }

    // Recorded: a Punjabi speaker's own sentence, heard as Hindi and said back in Punjabi.
    @Test
    fun `an offer that is withdrawn has to be heard all over again`() {
        val t = tracker(theirs = "en")
        t.heard("hi", 0)
        t.heard("hi", 1_000)
        t.withdraw()
        assertNull(t.offer)
        assertNull("nothing was taken", t.accept())
        assertEquals("en", t.current)

        t.heard("hi", 2_000)
        assertNull("once is not enough again", t.offer)
    }

    @Test
    fun `an offer nobody took up lapses`() {
        val t = tracker()
        t.heard("hi", 0)
        t.expire(HeardLanguageTracker.OFFER_TTL_MS)
        assertEquals("still fresh", "hi", t.offer?.language)
        t.expire(HeardLanguageTracker.OFFER_TTL_MS + 1)
        assertNull(t.offer)
    }

    @Test
    fun `an offer that keeps being heard does not lapse`() {
        val t = tracker()
        t.heard("hi", 0)
        t.heard("hi", 5_000)
        t.expire(9_000)
        assertEquals("hi", t.offer?.language)
        assertEquals("and is still dated from its first word", 0L, t.offer?.sinceMs)
    }

    // What the translate model reported on 2026-10-06 for speech in each of these.
    @Test
    fun `a language is itself under the name the model gives it`() {
        assertTrue("Norwegian", HeardLanguageTracker.sameLanguage("no", "nb-NO"))
        assertTrue("Filipino", HeardLanguageTracker.sameLanguage("tl", "fil-PH"))
        assertTrue(HeardLanguageTracker.sameLanguage("nb-NO", "no"))
        assertFalse("Swedish is not Norwegian", HeardLanguageTracker.sameLanguage("sv", "nb-NO"))
        assertFalse("Danish is not Norwegian", HeardLanguageTracker.sameLanguage("da", "nb-NO"))
        assertFalse(HeardLanguageTracker.sameLanguage("tl", "id-ID"))
    }

    // The model hears Malay as Indonesian. That is a mishearing, caught by what
    // is said back, and not another name for Malay: an Indonesian speaker is not me.
    @Test
    fun `Indonesian is not Malay`() {
        assertFalse(HeardLanguageTracker.sameLanguage("id", "ms-MY"))
    }

    @Test
    fun `a Norwegian speaker is not taken for the other person`() {
        val t = HeardLanguageTracker(myLanguage = "nb-NO", initialTheirs = "en-US")
        t.heard("no", 0)
        t.heard("no", 100)
        assertNull(t.offer)
        assertEquals("en-US", t.current)
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
