package com.classeve.earslate.live

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The session configuration must be IDENTICAL in the ephemeral token and in the
 * setup frame, for every setting the user can change.
 *
 * This is the test that did not exist, and its absence cost a shipped defect.
 *
 * A Gemini ephemeral token can carry a `bidiGenerateContentSetup`, which LOCKS
 * the session — the socket is then opened against
 * `BidiGenerateContentConstrained`. The client still sends its own `setup`
 * frame. So the same configuration is stated twice, and the two statements have
 * to agree.
 *
 * They did not. The token was built by hand in `ProviderSessionMinter` and
 * always carried `inputAudioTranscription` / `outputAudioTranscription`; the
 * setup frame was built by [LiveSessionConfigFactory] and omitted them when the
 * user turned captions off. Turning captions off therefore produced a session
 * whose credential and setup frame described different sessions.
 *
 * Every one of those pieces had a test. `GeminiAuthTokenShapeTest` asserted the
 * token carried transcription. `GeminiProtocolContractTest` asserted the frame
 * omitted it with captions off. Both passed, both were individually right, and
 * the suite was green on a contradiction — because nothing compared them.
 *
 * The production fix is structural: one builder, two emitters, so the objects
 * cannot differ. This test is the guard on that structure. If someone
 * reintroduces a second copy of the setup object, this fails.
 */
class GeminiSessionSetupParityTest {

    private val model = "gemini-3.5-live-translate-preview"

    private fun tokenSetup(captionsEnabled: Boolean) = JSONObject(
        LiveSessionConfigFactory.buildTokenSessionSetup(
            model = model,
            targetLanguageCode = "es",
            echoTargetLanguage = false,
            captionsEnabled = captionsEnabled,
        ),
    )

    private fun frameSetup(captionsEnabled: Boolean) = JSONObject(
        LiveSessionConfigFactory.buildSetup(
            model = model,
            targetLanguageCode = "es",
            echoTargetLanguage = false,
            captionsEnabled = captionsEnabled,
        ),
    ).getJSONObject("setup")

    /**
     * `JSONObject.toString()` does not sort keys, so comparing the rendered
     * text would fail on ordering alone. Compare structurally instead.
     */
    private fun assertSameJson(expected: JSONObject, actual: JSONObject, path: String) {
        assertEquals(
            "key sets differ at $path",
            expected.keys().asSequence().toSortedSet(),
            actual.keys().asSequence().toSortedSet(),
        )
        for (key in expected.keys()) {
            val a = expected.get(key)
            val b = actual.get(key)
            if (a is JSONObject && b is JSONObject) {
                assertSameJson(a, b, "$path.$key")
            } else {
                assertEquals("value differs at $path.$key", a.toString(), b.toString())
            }
        }
    }

    @Test
    fun `token setup equals client setup with captions on`() {
        assertSameJson(tokenSetup(true), frameSetup(true), "setup")
    }

    @Test
    fun `token setup equals client setup with captions off`() {
        assertSameJson(tokenSetup(false), frameSetup(false), "setup")
    }

    /**
     * Parity alone would also be satisfied by both sides being wrong in the same
     * way — two identical objects that always carry transcription would pass the
     * tests above while the captions toggle did nothing. So assert that the flag
     * actually reaches the token.
     */
    @Test
    fun `captions off removes transcription from the token, not just from the frame`() {
        val off = tokenSetup(false)
        assertFalse("captions off must not mint a transcribing token", off.has("inputAudioTranscription"))
        assertFalse("captions off must not mint a transcribing token", off.has("outputAudioTranscription"))

        val on = tokenSetup(true)
        assertTrue(on.has("inputAudioTranscription"))
        assertTrue(on.has("outputAudioTranscription"))
    }

    /**
     * The token embeds the setup bare; the client wraps the same object in
     * `setup`. Confuse the two and the endpoint answers 400.
     */
    @Test
    fun `the token setup is unwrapped and the client frame is wrapped`() {
        assertFalse("the token embeds the setup bare", tokenSetup(true).has("setup"))
        assertTrue(
            "the client frame wraps it",
            JSONObject(
                LiveSessionConfigFactory.buildSetup(model, "es", false, true),
            ).has("setup"),
        )
    }
}
