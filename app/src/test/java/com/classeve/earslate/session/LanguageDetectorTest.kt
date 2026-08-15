package com.classeve.earslate.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageDetectorTest {

    @Test
    fun `non-latin scripts settle it outright`() {
        assertEquals("hi-IN", LanguageDetector.detect("आप कैसे हैं आज"))
        assertEquals("bn-IN", LanguageDetector.detect("আপনি কেমন আছেন"))
        assertEquals("ta-IN", LanguageDetector.detect("நீங்கள் எப்படி இருக்கிறீர்கள்"))
        assertEquals("te-IN", LanguageDetector.detect("మీరు ఎలా ఉన్నారు"))
        assertEquals("pa-IN", LanguageDetector.detect("ਤੁਸੀਂ ਕਿਵੇਂ ਹੋ"))
        assertEquals("ar-SA", LanguageDetector.detect("كيف حالك اليوم"))
        assertEquals("he-IL", LanguageDetector.detect("מה שלומך היום"))
        assertEquals("el-GR", LanguageDetector.detect("πώς είσαι σήμερα"))
        assertEquals("th-TH", LanguageDetector.detect("คุณเป็นอย่างไรบ้าง"))
        assertEquals("ko-KR", LanguageDetector.detect("오늘 기분이 어떠세요"))
    }

    /**
     * Japanese is mostly han with kana threaded through it. Testing han first
     * would call every Japanese sentence Chinese, so the order is load-bearing.
     */
    @Test
    fun `japanese is not mistaken for chinese`() {
        assertEquals("ja-JP", LanguageDetector.detect("今日はどうですか"))
        assertEquals("zh-CN", LanguageDetector.detect("你今天怎么样了"))
        assertEquals("zh-TW", LanguageDetector.detect("你今天怎麼樣說話"))
    }

    @Test
    fun `ukrainian is told apart from russian by its own letters`() {
        assertEquals("ru-RU", LanguageDetector.detect("как дела сегодня"))
        assertEquals("uk-UA", LanguageDetector.detect("як справи сьогодні"))
    }

    @Test
    fun `common latin languages resolve on function words`() {
        assertEquals("es-ES", LanguageDetector.detect("hola que tal estas hoy"))
        assertEquals("fr-FR", LanguageDetector.detect("bonjour comment vous allez aujourd hui"))
        assertEquals("de-DE", LanguageDetector.detect("wie geht es dir und wir haben nicht"))
        assertEquals("it-IT", LanguageDetector.detect("ciao come stai molto bene grazie"))
        assertEquals("pt-BR", LanguageDetector.detect("obrigado voce esta muito bem aqui"))
        assertEquals("id-ID", LanguageDetector.detect("terima kasih saya sudah bisa"))
        assertEquals("tr-TR", LanguageDetector.detect("merhaba nasil sen bir cok"))
    }

    /**
     * The transcript is machine-produced and does not always carry accents.
     * Spanish typed flat must still be Spanish.
     */
    @Test
    fun `stripped diacritics do not break detection`() {
        assertEquals("es-ES", LanguageDetector.detect("gracias por una de las cosas"))
    }

    @Test
    fun `english is english`() {
        assertEquals("en-US", LanguageDetector.detect("how are you doing today with that"))
    }

    @Test
    fun `too little to go on is reported as unknown, never guessed`() {
        assertNull(LanguageDetector.detect(""))
        assertNull(LanguageDetector.detect("ok"))
        assertNull(LanguageDetector.detect("mm hmm"))
        assertNull(LanguageDetector.detect("Berlin Madrid Toronto"))
    }

    @Test
    fun `every tag it can return is one the pickers know`() {
        val known = SupportedLanguages.map { it.bcp47 }.toSet()
        val samples = listOf(
            "आप कैसे हैं आज", "你今天怎么样了", "今日はどうですか", "как дела сегодня",
            "як справи сьогодні", "hola que tal estas hoy", "how are you doing today with that",
            "obrigado voce esta muito bem aqui", "terima kasih saya sudah bisa",
        )
        for (sample in samples) {
            val tag = LanguageDetector.detect(sample)
            assertTrue("$tag is not in SupportedLanguages", tag == null || tag in known)
        }
    }
}
