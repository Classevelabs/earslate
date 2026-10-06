package com.classeve.earslate.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether a session is saying back what it heard is read from its words. The
 * pairs here are what Gemini Live Translate wrote down for one utterance: on
 * the left what it said it heard, on the right what it then said.
 */
class SpokenWordsTest {

    // Punjabi taken for Hindi, and "translated" into Punjabi.
    @Test
    fun `the same words in Devanagari and in Gurmukhi are the same words`() {
        assertEquals(SpokenWords.of("हां जी। ठीक है।"), SpokenWords.of("ਹਾਂ ਜੀ। ਠੀਕ ਹੈ।"))
        assertEquals(SpokenWords.of("मेरा फोन चार्ज नहीं है।"), SpokenWords.of("ਮੇਰਾ ਫੋਨ ਚਾਰਜ ਨਹੀਂ ਹੈ।"))
    }

    @Test
    fun `and so they are in Gujarati`() {
        assertEquals(SpokenWords.of("नहीं"), SpokenWords.of("નહીં"))
    }

    @Test
    fun `different words in the same two scripts are still different`() {
        // "from here" in Hindi and in Punjabi.
        assertNotEquals(SpokenWords.of("यहां से"), SpokenWords.of("ਇੱਥੋਂ"))
        val hindi = SpokenWords.of("ये यहां से लगभग 10 मिनट की दूरी पर है").toSet()
        val punjabi = SpokenWords.of("ਇਹ ਇੱਥੋਂ ਲਗਭਗ 10 ਮਿੰਟ ਦੀ ਦੂਰੀ 'ਤੇ ਹੈ")
        val shared = punjabi.count { it in hindi }
        assertTrue("a translation between them shares some words and not most: $shared of ${punjabi.size}", shared in 2..5)
    }

    @Test
    fun `capitals, accents and punctuation do not make a different word`() {
        assertEquals(listOf("donde", "esta", "la", "estacion"), SpokenWords.of("¿Dónde está la ESTACIÓN?"))
        assertEquals(SpokenWords.of("Selamat pagi. Bolehkah anda"), SpokenWords.of("selamat pagi, bolehkah anda"))
    }

    @Test
    fun `numbers are words`() {
        assertEquals(listOf("about", "10", "minutes"), SpokenWords.of("about 10 minutes"))
    }

    // Text arrives in pieces that end wherever the provider cut them.
    @Test
    fun `a last word still being written is kept apart from the finished ones`() {
        val arriving = SpokenWords.split("Where is the sta")
        assertEquals(listOf("where", "is", "the"), arriving.whole)
        assertEquals("sta", arriving.unfinished)
        assertEquals(listOf("where", "is", "the", "sta"), SpokenWords.of("Where is the sta"))

        val finished = SpokenWords.split("Where is the station?")
        assertEquals(listOf("where", "is", "the", "station"), finished.whole)
        assertNull(finished.unfinished)

        val marked = SpokenWords.split("ਮੇ")
        assertTrue("a mark after the last letter does not finish the word", marked.whole.isEmpty())
        assertEquals(SpokenWords.of("म"), listOfNotNull(marked.unfinished))
    }

    @Test
    fun `a script written without spaces is read phrase by phrase`() {
        assertEquals(listOf("你好", "请问最近的地铁站在哪里"), SpokenWords.of("你好,请问最近的地铁站在哪里?"))
    }

    @Test
    fun `nothing said is no words`() {
        assertTrue(SpokenWords.of("").isEmpty())
        assertTrue(SpokenWords.of(" … ?! ").isEmpty())
    }

    @Test
    fun `what was heard is counted from a moment on`() {
        val speech = HeardSpeech()
        speech.add("Muchas gracias.", 1_000)
        speech.add(" Sure, it is", 5_000)
        speech.add(" about ten", 6_000)

        assertEquals(setOf("sure", "it", "is", "about", "ten"), speech.wordsSince(4_000))
        assertEquals(setOf("muchas", "gracias", "sure", "it", "is", "about", "ten"), speech.wordsSince(0))
        assertTrue(speech.wordsSince(7_000).isEmpty())
    }

    // "Clients should not insert unconditional spaces between deltas."
    @Test
    fun `a word cut across two pieces of what was heard is one word`() {
        val speech = HeardSpeech()
        speech.add("Where is the sta", 0)
        speech.add("tion?", 300)
        assertEquals(setOf("where", "is", "the", "station"), speech.wordsSince(0))
    }

    @Test
    fun `what was heard long ago is let go`() {
        val speech = HeardSpeech()
        speech.add("Hola", 0)
        speech.add(" adiós", 61_000)
        assertEquals(setOf("adios"), speech.wordsSince(0))
    }
}
