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

    /**
     * The regression this file exists for.
     *
     * An earlier version refused any Gemini key that did not start with "AIza".
     * Google changed its key format, and the app then rejected valid keys with a
     * confident, wrong message — setup became impossible. Format allowlists turn
     * a provider's routine change into a broken app, so there is no longer a
     * prefix gate anywhere. These cases lock that in.
     */
    @Test
    fun `an unfamiliar key format is accepted rather than guessed at`() {
        // Whatever Google or OpenAI issue next must pass straight through.
        assertTrue(KeyProvider.GEMINI.isPlausible("gk_live_" + "x".repeat(40)))
        assertTrue(KeyProvider.GEMINI.isPlausible("AQ.Ab8RN6" + "y".repeat(50)))
        assertTrue(KeyProvider.OPENAI.isPlausible("oai_" + "z".repeat(40)))
        assertTrue(KeyProvider.GEMINI.isPlausible("totally-new-scheme-2027"))
    }

    @Test
    fun `historical prefixes still pass`() {
        assertTrue(KeyProvider.GEMINI.isPlausible("AIza" + "b".repeat(35)))
        assertTrue(KeyProvider.OPENAI.isPlausible("sk-" + "c".repeat(40)))
    }

    @Test
    fun `a key of unusual length is not rejected on length alone`() {
        assertTrue("short but credible", KeyProvider.GEMINI.isPlausible("abcd1234"))
        assertTrue("very long", KeyProvider.OPENAI.isPlausible("k".repeat(400)))
    }

    @Test
    fun `pasting the wrong provider's key is a hint, not a rejection`() {
        val openAiKey = "sk-" + "f".repeat(40)
        assertNull("must not block", KeyProvider.GEMINI.rejectionReason(openAiKey))
        assertEquals(
            KeyProvider.OPENAI,
            KeyProvider.GEMINI.looksLikeAnotherProvider(openAiKey),
        )
    }

    @Test
    fun `no hint when the key matches the selected provider`() {
        assertNull(KeyProvider.GEMINI.looksLikeAnotherProvider("AIza" + "g".repeat(35)))
        assertNull(KeyProvider.GEMINI.looksLikeAnotherProvider("some-unrecognised-key"))
    }

    @Test
    fun `pasting the console URL is named as such`() {
        val reason = KeyProvider.GEMINI.rejectionReason("https://aistudio.google.com/apikey")
        assertNotNull(reason)
        assertTrue(reason!!.contains("web address"))
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
        assertTrue(KeyProvider.GEMINI.isPlausible("  AIza${"i".repeat(35)}  "))
        assertFalse("empty is still rejected", KeyProvider.GEMINI.isPlausible("     "))
    }
}
