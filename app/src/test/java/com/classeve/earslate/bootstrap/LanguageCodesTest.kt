package com.classeve.earslate.bootstrap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * This normalisation used to live in the Cloudflare Worker. It now runs on the
 * device, so it needs the coverage the Worker had — a wrong code here means the
 * provider silently translates into the wrong language.
 */
class LanguageCodesTest {

    @Test
    fun `region is dropped for languages where it only changes accent`() {
        assertEquals("en", LanguageCodes.normalize("en-US"))
        assertEquals("en", LanguageCodes.normalize("en-GB"))
        assertEquals("fr", LanguageCodes.normalize("fr-CA"))
        assertEquals("es", LanguageCodes.normalize("es-MX"))
    }

    @Test
    fun `chinese keeps its script because it changes the written output`() {
        assertEquals("zh-Hant", LanguageCodes.normalize("zh-TW"))
        assertEquals("zh-Hant", LanguageCodes.normalize("zh-Hant"))
        assertEquals("zh-Hans", LanguageCodes.normalize("zh-CN"))
        assertEquals("zh-Hans", LanguageCodes.normalize("zh-Hans"))
        assertEquals("zh-Hans", LanguageCodes.normalize("zh-SG"))
    }

    @Test
    fun `portuguese distinguishes european from brazilian`() {
        assertEquals("pt-PT", LanguageCodes.normalize("pt-PT"))
        assertEquals("pt-BR", LanguageCodes.normalize("pt-BR"))
        assertEquals("pt-BR", LanguageCodes.normalize("pt-AO"))
    }

    @Test
    fun `a bare language code passes through lowercased`() {
        assertEquals("de", LanguageCodes.normalize("de"))
        assertEquals("ja", LanguageCodes.normalize("JA"))
        assertEquals("zh", LanguageCodes.normalize("zh"))
        assertEquals("pt", LanguageCodes.normalize("pt"))
    }

    @Test
    fun `malformed input is rejected rather than guessed at`() {
        assertNull(LanguageCodes.normalize(null))
        assertNull(LanguageCodes.normalize(""))
        assertNull(LanguageCodes.normalize("   "))
        assertNull(LanguageCodes.normalize("english"))
        assertNull(LanguageCodes.normalize("en_US"))
        assertNull(LanguageCodes.normalize("e"))
        assertNull(LanguageCodes.normalize("en-US-extra"))
        assertNull(LanguageCodes.normalize("../../etc"))
    }

    @Test
    fun `surrounding whitespace is tolerated`() {
        assertEquals("en", LanguageCodes.normalize("  en-US  "))
    }
}
