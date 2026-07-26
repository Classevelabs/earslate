package com.classeve.earslate.security

import com.classeve.earslate.session.TranslationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The format checks exist to answer the common paste mistakes instantly and
 * specifically, before a network round trip. Each case here is a mistake a real
 * person makes at the key-setup screen.
 */
class KeyProviderTest {

    @Test
    fun `a well-formed gemini key is accepted`() {
        assertTrue(KeyProvider.GEMINI.isPlausible("AIza" + "b".repeat(35)))
    }

    @Test
    fun `a well-formed openai key is accepted`() {
        assertTrue(KeyProvider.OPENAI.isPlausible("sk-" + "c".repeat(40)))
    }

    @Test
    fun `pasting the console URL is named as such`() {
        val reason = KeyProvider.GEMINI.rejectionReason("https://aistudio.google.com/apikey")
        assertNotNull(reason)
        assertTrue("should say it is a web address", reason!!.contains("web address"))
    }

    @Test
    fun `a Bearer prefix is called out specifically`() {
        val reason = KeyProvider.OPENAI.rejectionReason("Bearer sk-${"d".repeat(40)}")
        assertNotNull(reason)
        assertTrue(reason!!.contains("Bearer"))
    }

    @Test
    fun `whitespace inside a key is reported rather than silently trimmed`() {
        val reason = KeyProvider.GEMINI.rejectionReason("AIza abc${"e".repeat(35)}")
        assertNotNull(reason)
        assertTrue(reason!!.contains("space"))
    }

    @Test
    fun `pasting the wrong provider's key says which provider it belongs to`() {
        val reason = KeyProvider.GEMINI.rejectionReason("sk-${"f".repeat(40)}")
        assertNotNull(reason)
        assertTrue("should name OpenAI", reason!!.contains("OpenAI"))
    }

    @Test
    fun `a truncated key is reported as cut off rather than malformed`() {
        val reason = KeyProvider.GEMINI.rejectionReason("AIzaShort")
        assertNotNull(reason)
        assertTrue(reason!!.contains("cut off"))
    }

    @Test
    fun `an empty field asks for the key rather than complaining`() {
        val reason = KeyProvider.GEMINI.rejectionReason("   ")
        assertNotNull(reason)
        assertTrue(reason!!.contains("Paste"))
    }

    @Test
    fun `detect identifies the provider from the prefix`() {
        assertEquals(KeyProvider.GEMINI, KeyProvider.detect("AIza${"g".repeat(35)}"))
        assertEquals(KeyProvider.OPENAI, KeyProvider.detect("sk-${"h".repeat(40)}"))
        assertNull(KeyProvider.detect("not-a-key"))
    }

    @Test
    fun `provider mapping is complete and automatic maps to nothing`() {
        assertEquals(KeyProvider.GEMINI, KeyProvider.forProvider(TranslationProvider.GEMINI))
        assertEquals(KeyProvider.OPENAI, KeyProvider.forProvider(TranslationProvider.OPENAI))
        assertNull(KeyProvider.forProvider(TranslationProvider.AUTOMATIC))
    }

    @Test
    fun `vault entry names are stable and distinct`() {
        // Changing these strands the user's saved key on upgrade.
        assertEquals("api_key_gemini", KeyProvider.GEMINI.vaultEntry)
        assertEquals("api_key_openai", KeyProvider.OPENAI.vaultEntry)
    }

    @Test
    fun `surrounding whitespace alone does not reject a key`() {
        assertFalse(KeyProvider.GEMINI.isPlausible("AIzaShort"))
        assertTrue(KeyProvider.GEMINI.isPlausible("  AIza${"i".repeat(35)}  "))
    }
}
