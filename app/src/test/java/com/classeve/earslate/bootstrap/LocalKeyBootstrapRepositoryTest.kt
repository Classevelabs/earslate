package com.classeve.earslate.bootstrap

import com.classeve.earslate.security.ProviderKeyStore
import com.classeve.earslate.security.SecretStore
import com.classeve.earslate.session.TranslationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** A vault holding exactly what the entries say; null is ciphertext that will never decrypt. */
private class FakeVault(entries: Map<String, String?>) : SecretStore {
    val entries = entries.toMutableMap()
    override fun contains(name: String): Boolean = entries.containsKey(name)
    override fun get(name: String): String? = entries[name]
    override fun put(name: String, secret: String) { entries[name] = secret }
    override fun remove(name: String) { entries.remove(name) }
    override val wasResetByKeystore: Boolean = false
    override fun acknowledgeKeystoreReset() = Unit
}

/**
 * One credential serves a whole session, and what the user is told when the
 * key on their device cannot be used.
 */
class LocalKeyBootstrapRepositoryTest {

    private var clock = 1_790_000_000_000L
    private val mints = AtomicInteger()
    private val providersAsked = ArrayList<String>()

    private val http = OkHttpClient.Builder().addInterceptor(
        Interceptor { chain ->
            val n = mints.incrementAndGet()
            val openAi = chain.request().url.host.contains("openai")
            providersAsked += if (openAi) "openai" else "gemini"
            val body = if (openAi) """{"value":"ek_$n"}""" else """{"name":"auth_tokens/$n"}"""
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        },
    ).build()

    private fun repository(vault: FakeVault) = LocalKeyBootstrapRepository(
        keys = ProviderKeyStore(vault),
        minter = ProviderSessionMinter(http, installId = "i", now = { clock }),
        now = { clock },
    )

    private fun failure(entries: Map<String, String?>, preference: TranslationProvider?): BootstrapException = runBlocking {
        runCatching { repository(FakeVault(entries)).credential(preference) }.exceptionOrNull() as BootstrapException
    }

    @Test
    fun `both directions and every replacement socket share one credential`() = runBlocking {
        val repo = repository(FakeVault(mapOf("api_key_gemini" to "key-a")))
        val first = repo.credential(null)
        val second = repo.credential(null)
        clock += 10 * 60_000L
        val third = repo.credential(TranslationProvider.GEMINI)

        assertSame(first, second)
        assertSame(first, third)
        assertEquals("one request to the provider for all three", 1, mints.get())
    }

    // A Gemini socket lives just under ten minutes and stops taking audio when its token expires.
    @Test
    fun `a credential too close to expiring to outlast a socket is replaced`() = runBlocking {
        val repo = repository(FakeVault(mapOf("api_key_gemini" to "key-a")))
        val first = repo.credential(null)
        clock += 19 * 60_000L
        val second = repo.credential(null)

        assertNotSame(first, second)
        assertEquals(2, mints.get())
        assertTrue(second.expiresAtMs - clock >= LocalKeyBootstrapRepository.MIN_REMAINING_MS)
    }

    @Test
    fun `a credential the provider refused is not handed out again`() = runBlocking {
        val repo = repository(FakeVault(mapOf("api_key_gemini" to "key-a")))
        val first = repo.credential(null)
        repo.discard(first)
        assertNotSame(first, repo.credential(null))
    }

    @Test
    fun `replacing the key in Settings takes effect on the next socket`() = runBlocking {
        val vault = FakeVault(mapOf("api_key_gemini" to "key-a"))
        val repo = repository(vault)
        val first = repo.credential(null)
        vault.put("api_key_gemini", "key-b")
        assertNotSame(first, repo.credential(null))
        assertEquals(2, mints.get())
    }

    @Test
    fun `the chosen provider is used when its key is saved`() = runBlocking {
        val repo = repository(FakeVault(mapOf("api_key_gemini" to "g", "api_key_openai" to "o")))
        assertEquals(TranslationProvider.OPENAI, repo.credential(TranslationProvider.OPENAI).provider)
        assertEquals(TranslationProvider.GEMINI, repo.credential(TranslationProvider.GEMINI).provider)
        assertEquals(listOf("openai", "gemini"), providersAsked)
    }

    @Test
    fun `with no choice made, whichever provider has a key is used`() = runBlocking {
        assertEquals(
            TranslationProvider.OPENAI,
            repository(FakeVault(mapOf("api_key_openai" to "o"))).credential(null).provider,
        )
        assertEquals(
            TranslationProvider.GEMINI,
            repository(FakeVault(mapOf("api_key_gemini" to "g", "api_key_openai" to "o"))).credential(null).provider,
        )
    }

    // The choice may outlive the key it was made for; the session must still start.
    @Test
    fun `a choice whose key was removed falls back to the key that exists`() = runBlocking {
        val repo = repository(FakeVault(mapOf("api_key_gemini" to "g")))
        assertEquals(TranslationProvider.GEMINI, repo.credential(TranslationProvider.OPENAI).provider)
    }

    // Settings shows the key as saved, so "no key" here would be a visible untruth.
    @Test
    fun `a stored key that will not decrypt is reported as unreadable, not as missing`() {
        val message = failure(mapOf("api_key_gemini" to null), TranslationProvider.GEMINI).message.orEmpty()
        assertTrue(message, message.contains("Google Gemini"))
        assertTrue(message, message.contains("can't be read"))
        assertTrue(message, message.contains("Settings"))
        assertFalse(message, message.contains("No provider key"))
        assertEquals("nothing was sent to the provider", 0, mints.get())
    }

    @Test
    fun `no key at all says no key`() {
        val message = failure(emptyMap(), null).message.orEmpty()
        assertTrue(message, message.contains("No provider key is set up"))
    }
}
