package com.classeve.earslate.bootstrap

import com.classeve.earslate.security.ProviderKeyStore
import com.classeve.earslate.security.SecretStore
import com.classeve.earslate.session.TranslationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A vault holding exactly what the named entries say — a value that decrypts,
 * or `null` for ciphertext that exists and never will.
 */
private class FakeVault(entries: Map<String, String?>) : SecretStore {
    private val entries = entries.toMutableMap()
    override fun contains(name: String): Boolean = entries.containsKey(name)
    override fun get(name: String): String? = entries[name]
    override fun put(name: String, secret: String) { entries[name] = secret }
    override fun remove(name: String) { entries.remove(name) }
    override val wasResetByKeystore: Boolean = false
    override fun acknowledgeKeystoreReset() = Unit
}

private fun repositoryOver(entries: Map<String, String?>) = LocalKeyBootstrapRepository(
    keys = ProviderKeyStore(FakeVault(entries)),
    // Never reached in these tests: every one of them fails before minting.
    minter = ProviderSessionMinter(installId = "test-install"),
)

private fun failureFrom(entries: Map<String, String?>, provider: TranslationProvider): Throwable {
    val repo = repositoryOver(entries)
    val thrown = runBlocking {
        runCatching { repo.bootstrap(provider, "en-US", captionsEnabled = true) }.exceptionOrNull()
    }
    return requireNotNull(thrown) { "bootstrap was expected to fail" }
}

/**
 * What the user is told when the key on their device cannot be used.
 *
 * This matters because "is a key present?" is now answered from the
 * preferences file rather than by decrypting one. That is right — it took a
 * keystore round trip per provider out of a Compose frame — but it means a
 * device whose keystore has been invalidated reports a key that can never be
 * read. The whole safety of that trade rests on this path saying so out loud.
 *
 * The message reaches the user verbatim: `SessionCoordinator` catches this as
 * `RuntimeError.Kind.BOOTSTRAP_FAILED` carrying `t.message`, and `ErrorBanner`
 * renders `error.message` as its body text.
 */
class LocalKeyBootstrapRepositoryTest {

    /**
     * The regression this file exists for. Answering "no key is set up" here
     * is a dead end: Settings shows the provider as configured, so the user is
     * told something they can see is untrue and given nothing to act on.
     */
    @Test
    fun `a stored key that will not decrypt is reported as unreadable, not as missing`() {
        val failure = failureFrom(
            entries = mapOf("api_key_gemini" to null),
            provider = TranslationProvider.GEMINI,
        )

        assertTrue(failure is BootstrapException)
        val message = failure.message.orEmpty()
        assertTrue("names the provider: $message", message.contains("Google Gemini"))
        assertTrue("says the key could not be read: $message", message.contains("can't be read"))
        assertTrue("says what to do about it: $message", message.contains("Settings"))
        assertFalse(
            "must not claim the key was never added",
            message.contains("No Google Gemini key is set up"),
        )
    }

    /** Same on the automatic path, where the provider is chosen for the user. */
    @Test
    fun `automatic reports an unreadable key rather than falling through to missing`() {
        val failure = failureFrom(
            entries = mapOf("api_key_gemini" to null),
            provider = TranslationProvider.AUTOMATIC,
        )

        assertTrue("says the key could not be read: ${failure.message}", failure.message.orEmpty().contains("can't be read"))
    }

    /**
     * And the opposite case still reads correctly — a genuinely absent key must
     * not be dressed up as a storage fault, or the user goes looking for a
     * problem with their phone instead of adding a key.
     */
    @Test
    fun `no key at all still says no key`() {
        val named = failureFrom(emptyMap(), TranslationProvider.GEMINI)
        assertTrue(named.message.orEmpty().contains("No Google Gemini key is set up"))

        val unnamed = failureFrom(emptyMap(), TranslationProvider.AUTOMATIC)
        assertTrue(unnamed.message.orEmpty().contains("No API key is set up yet"))
    }
}
