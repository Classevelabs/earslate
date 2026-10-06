package com.classeve.earslate.session

import org.junit.Assert.assertEquals
import org.junit.Test

/** The name shown for a language the other person was heard speaking. */
class TargetLanguageTest {

    @Test
    fun `a language in the picker is shown by the picker's name`() {
        assertEquals("es-ES", TargetLanguage.forCode("es").bcp47)
        assertEquals("zh-CN", TargetLanguage.forCode("zh").bcp47)
        assertEquals("id-ID", TargetLanguage.forCode("id").bcp47)
        assertEquals("ms-MY", TargetLanguage.forCode("ms").bcp47)
    }

    // The model says "no" and "tl" where the picker says nb and fil.
    @Test
    fun `the model's own name for a picker language finds that language`() {
        assertEquals("nb-NO", TargetLanguage.forCode("no").bcp47)
        assertEquals("fil-PH", TargetLanguage.forCode("tl").bcp47)
    }

    @Test
    fun `a language the picker does not list keeps its own code and gets a name`() {
        val swahili = TargetLanguage.forCode("sw")
        assertEquals("sw", swahili.bcp47)
        assertEquals(true, swahili.displayName.isNotBlank())
    }
}
