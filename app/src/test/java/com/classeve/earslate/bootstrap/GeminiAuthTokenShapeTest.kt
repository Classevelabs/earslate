package com.classeve.earslate.bootstrap

import com.classeve.earslate.live.LiveSessionConfigFactory
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the exact JSON shape Google's `v1alpha/auth_tokens` endpoint accepts.
 *
 * Verified against the live endpoint on 2026-07-26. Two mistakes here each
 * produced a flat HTTP 400 that read, to a user, as "my API key is broken" —
 * they could not get past setup and had no way to tell it was our request that
 * was malformed rather than their key:
 *
 *  1. **No `authToken` wrapper.** The AuthToken fields go at the top level of
 *     the body. Wrapping them returns
 *     `Unknown name "authToken" at 'auth_token': Cannot find field`.
 *  2. **`inputAudioTranscription` / `outputAudioTranscription` belong to
 *     `bidiGenerateContentSetup`, not `generationConfig`.** Putting them in
 *     `generationConfig` returns `Cannot find field`. `translationConfig` is
 *     the opposite — it must be inside `generationConfig`.
 *
 * The `bidiGenerateContentSetup` object below is the REAL one — the same call
 * [ProviderSessionMinter] makes. It used to be a hand-written copy of it, which
 * is precisely how the minter and the client setup frame were allowed to drift
 * apart on the captions flag while this file stayed green. A test that mirrors
 * the code it is testing proves only that someone typed the same thing twice.
 *
 * The AuthToken envelope around it (`uses` / `expireTime` /
 * `newSessionExpireTime`) is still mirrored, because it is built inside a
 * private method with a clock in it. That is a smaller and static surface, but
 * it IS still a mirror — if the envelope changes, change it here too.
 */
class GeminiAuthTokenShapeTest {

    private fun body(language: String = "es", captionsEnabled: Boolean = true): JSONObject {
        val setup = JSONObject(
            LiveSessionConfigFactory.buildTokenSessionSetup(
                model = ProviderSessionMinter.GEMINI_MODEL,
                targetLanguageCode = language,
                echoTargetLanguage = false,
                captionsEnabled = captionsEnabled,
            ),
        )

        return JSONObject()
            .put("uses", 1)
            .put("expireTime", "2026-07-27T00:00:00Z")
            .put("newSessionExpireTime", "2026-07-26T23:00:00Z")
            .put("bidiGenerateContentSetup", setup)
    }

    @Test
    fun `auth token fields are at the top level with no wrapper`() {
        val b = body()
        assertFalse(
            "an authToken wrapper is rejected by the endpoint",
            b.has("authToken"),
        )
        assertEquals(1, b.getInt("uses"))
        assertTrue(b.has("expireTime"))
        assertTrue(b.has("newSessionExpireTime"))
        assertTrue(b.has("bidiGenerateContentSetup"))
    }

    @Test
    fun `transcription config sits on the setup, not on generationConfig`() {
        val setup = body().getJSONObject("bidiGenerateContentSetup")
        val generation = setup.getJSONObject("generationConfig")

        assertTrue("input transcription must be on the setup", setup.has("inputAudioTranscription"))
        assertTrue("output transcription must be on the setup", setup.has("outputAudioTranscription"))
        assertFalse(
            "generationConfig rejects inputAudioTranscription",
            generation.has("inputAudioTranscription"),
        )
        assertFalse(
            "generationConfig rejects outputAudioTranscription",
            generation.has("outputAudioTranscription"),
        )
    }

    @Test
    fun `translation config sits inside generationConfig, not on the setup`() {
        val setup = body().getJSONObject("bidiGenerateContentSetup")
        assertFalse(
            "the setup rejects translationConfig",
            setup.has("translationConfig"),
        )
        val translation = setup.getJSONObject("generationConfig")
            .getJSONObject("translationConfig")
        assertEquals("es", translation.getString("targetLanguageCode"))
    }

    /**
     * Echo must stay off. With it on, both conversation legs speak at once and
     * each re-translates the other's output.
     */
    @Test
    fun `echoTargetLanguage is false`() {
        val translation = body().getJSONObject("bidiGenerateContentSetup")
            .getJSONObject("generationConfig")
            .getJSONObject("translationConfig")
        assertFalse(translation.getBoolean("echoTargetLanguage"))
    }

    @Test
    fun `model is fully qualified with the models prefix`() {
        val model = body().getJSONObject("bidiGenerateContentSetup").getString("model")
        assertTrue("endpoint requires the models/ prefix", model.startsWith("models/"))
        assertEquals("models/gemini-3.5-live-translate-preview", model)
    }

    @Test
    fun `single use so a leaked credential cannot be replayed`() {
        assertEquals(1, body().getInt("uses"))
    }
}
