package com.classeve.earslate.security

import com.classeve.earslate.session.TranslationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A [SecretStore] that records how it was asked, and can hold an entry that
 * exists but will not decrypt.
 *
 * That last state is not hypothetical: it is exactly what an AndroidKeyStore
 * looks like after the OS destroys the encryption key — the ciphertext is still
 * in the preferences file and can never be read again.
 */
private class RecordingStore(entries: Map<String, String?>) : SecretStore {

    private val entries = entries.toMutableMap()

    var decryptCalls = 0
        private set

    override fun contains(name: String): Boolean = entries.containsKey(name)

    override fun get(name: String): String? {
        decryptCalls++
        return entries[name]
    }

    override fun put(name: String, secret: String) {
        entries[name] = secret
    }

    override fun remove(name: String) {
        entries.remove(name)
    }

    override val wasResetByKeystore: Boolean = false

    override fun acknowledgeKeystoreReset() = Unit
}

private const val GEMINI_ENTRY = "api_key_gemini"
private const val OPENAI_ENTRY = "api_key_openai"

class ProviderKeyStoreTest {

    /**
     * The regression this file exists for.
     *
     * `has()` answered "is a key present?" by performing a full AES-GCM decrypt
     * through AndroidKeyStore — a TEE, or StrongBox, round trip per provider.
     * `MainActivity` calls `hasAnyKey()` during composition, so that ran inside
     * a frame, twice, to answer a question about which entries exist in a
     * preferences file. `KeyVault.contains()` was written for this and had zero
     * callers.
     */
    @Test
    fun `asking which keys exist never decrypts anything`() {
        val store = RecordingStore(
            mapOf(GEMINI_ENTRY to "AIza-real-key", OPENAI_ENTRY to "sk-real-key"),
        )
        val keys = ProviderKeyStore(store)

        keys.hasAnyKey()
        keys.has(KeyProvider.GEMINI)
        keys.has(KeyProvider.OPENAI)
        keys.configured()
        keys.resolve(TranslationProvider.AUTOMATIC)

        assertEquals("existence checks must not touch the keystore", 0, store.decryptCalls)
    }

    /**
     * The honest cost of the change above, written down as a test so nobody has
     * to rediscover it: after the keystore is invalidated the ciphertext still
     * exists, so the cheap check says the key is present when it can never be
     * used again.
     *
     * This is only acceptable because the path that USES the key reports it —
     * see `LocalKeyBootstrapRepositoryTest`.
     */
    @Test
    fun `a stored key that cannot be decrypted still counts as present`() {
        val keys = ProviderKeyStore(RecordingStore(mapOf(GEMINI_ENTRY to null)))

        assertTrue("the ciphertext is on the device", keys.has(KeyProvider.GEMINI))
        assertEquals(listOf(KeyProvider.GEMINI), keys.configured())
        assertNull("and it is unreadable", keys.key(KeyProvider.GEMINI))
    }

    @Test
    fun `no entry means no key`() {
        val keys = ProviderKeyStore(RecordingStore(emptyMap()))

        assertFalse(keys.has(KeyProvider.GEMINI))
        assertFalse(keys.has(KeyProvider.OPENAI))
        assertFalse(keys.hasAnyKey())
        assertEquals(emptyList<KeyProvider>(), keys.configured())
        assertNull(keys.resolve(TranslationProvider.AUTOMATIC))
    }

    @Test
    fun `reading a key returns what was stored`() {
        val keys = ProviderKeyStore(RecordingStore(mapOf(OPENAI_ENTRY to "sk-abcdefghijkl")))

        assertEquals("sk-abcdefghijkl", keys.key(KeyProvider.OPENAI))
    }

    @Test
    fun `configured lists only the providers with an entry`() {
        val keys = ProviderKeyStore(RecordingStore(mapOf(OPENAI_ENTRY to "sk-abcdefghijkl")))

        assertEquals(listOf(KeyProvider.OPENAI), keys.configured())
        assertTrue(keys.hasAnyKey())
    }

    /**
     * Automatic prefers Gemini because it is the only provider that runs both
     * translation directions. An explicit choice is never silently overridden.
     */
    @Test
    fun `resolve honours an explicit provider and falls back only for automatic`() {
        val both = ProviderKeyStore(
            RecordingStore(mapOf(GEMINI_ENTRY to "AIza-x", OPENAI_ENTRY to "sk-y")),
        )
        assertEquals(KeyProvider.OPENAI, both.resolve(TranslationProvider.OPENAI))
        assertEquals(KeyProvider.GEMINI, both.resolve(TranslationProvider.AUTOMATIC))

        val openAiOnly = ProviderKeyStore(RecordingStore(mapOf(OPENAI_ENTRY to "sk-y")))
        assertNull(
            "an explicit Gemini choice with no Gemini key must not fall through",
            openAiOnly.resolve(TranslationProvider.GEMINI),
        )
        assertEquals(KeyProvider.OPENAI, openAiOnly.resolve(TranslationProvider.AUTOMATIC))
    }
}
