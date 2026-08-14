package com.classeve.earslate.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins both halves of the bargain: the diagnosis survives, the secret does not.
 *
 * A redactor is only worth having if it is tested against real key shapes, so
 * the fixtures below are the actual formats this app handles — a Gemini API
 * key, an OpenAI key, and a minted session credential — embedded in the kind of
 * sentence a provider actually returns.
 */
class ProviderMessageTest {

    private val geminiKey = "AIzaSyD-9tSrke72PouQMnMX-a7eZSW0jkFMBWY"
    private val openAiKey = "sk-proj-4eC39HqLyjWDarjtT1zdp7dcSomeMoreEntropyHere"

    @Test
    fun `the diagnosis survives untouched`() {
        assertEquals(
            "You exceeded your current quota, please check your plan and billing details.",
            ProviderMessage.sanitize(
                "You exceeded your current quota, please check your plan and billing details.",
            ),
        )
    }

    @Test
    fun `a gemini key echoed in the message is redacted`() {
        val out = ProviderMessage.sanitize("API key not valid. Please pass a valid API key. $geminiKey")
        assertFalse("the key must not survive", out!!.contains(geminiKey))
        assertFalse(out.contains("AIzaSyD"))
        assertTrue("the diagnosis must survive", out.contains("API key not valid"))
    }

    @Test
    fun `an openai key echoed in the message is redacted`() {
        val out = ProviderMessage.sanitize("Incorrect API key provided: $openAiKey. Check your key.")
        assertFalse(out!!.contains(openAiKey))
        assertFalse(out.contains("sk-proj"))
        assertTrue(out.contains("Incorrect API key provided"))
    }

    /**
     * The credential the socket actually carries. It is short-lived, but it is
     * live for the length of a session and must not be printed on screen.
     */
    @Test
    fun `a minted session credential is redacted`() {
        val out = ProviderMessage.sanitize(
            "Session rejected for auth_tokens/aXbYcZ012345678901234567890abcdefghij please retry",
        )
        assertFalse(out!!.contains("aXbYcZ012345678901234567890"))
        assertTrue(out.contains("Session rejected"))
    }

    /**
     * Query-string echoes are cut at the marker rather than redacted piecewise:
     * everything after `key=` is an argument dump, and nothing in it is a
     * sentence worth showing.
     */
    @Test
    fun `text after a credential marker is dropped entirely`() {
        val out = ProviderMessage.sanitize("Request failed for url https://host/v1/x?key=$geminiKey")
        assertFalse(out!!.contains(geminiKey))
        assertFalse(out.contains("key="))
        assertTrue(out.contains("Request failed"))
    }

    /**
     * The expectation here was originally "returns null", and the code was
     * right and the test was wrong: cutting at the marker leaves "Bad
     * credentials in", which is a real diagnosis and worth keeping. Only the
     * token has to go.
     */
    @Test
    fun `a bearer header echo is cut, and the diagnosis before it survives`() {
        val out = ProviderMessage.sanitize("Bad credentials in Bearer $openAiKey")
        assertFalse(out!!.contains(openAiKey))
        assertFalse(out.contains("Bearer"))
        assertTrue(out.contains("Bad credentials"))
    }

    @Test
    fun `a message that is only a token yields nothing rather than a redaction blob`() {
        assertNull(ProviderMessage.sanitize(geminiKey))
        assertNull(ProviderMessage.sanitize("   "))
        assertNull(ProviderMessage.sanitize(null))
    }

    /**
     * Ordinary long words must not be eaten. If this fails the threshold has
     * been tuned down too far and real error text is being mangled.
     */
    @Test
    fun `long ordinary words are not redacted`() {
        val text = "The internationalisation configuration is unsupported for this model."
        assertEquals(text, ProviderMessage.sanitize(text))
    }

    @Test
    fun `an overlong message is capped so it cannot swallow the screen`() {
        val out = ProviderMessage.sanitize("word ".repeat(200))!!
        assertTrue("capped", out.length <= 181)
        assertTrue(out.endsWith("…"))
    }
}
