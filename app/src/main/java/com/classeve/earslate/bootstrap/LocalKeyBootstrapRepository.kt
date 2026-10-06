package com.classeve.earslate.bootstrap

import android.content.Context
import com.classeve.earslate.live.LinkFailure
import com.classeve.earslate.live.LiveSocketClient
import com.classeve.earslate.live.ProviderLink
import com.classeve.earslate.live.TranslationLiveProtocols
import com.classeve.earslate.security.KeyProvider
import com.classeve.earslate.security.ProviderKeyStore
import com.classeve.earslate.session.TranslationProvider
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import java.util.UUID

/**
 * Session credentials from the key the user supplied, minted on the device
 * with no server of ours involved.
 */
class LocalKeyBootstrapRepository(
    private val keys: ProviderKeyStore,
    private val minter: ProviderSessionMinter,
    private val now: () -> Long = System::currentTimeMillis,
) : SessionCredentialSource {

    private class Held(val credential: SessionCredential, val keyFingerprint: String)

    private val lock = Mutex()
    @Volatile private var held: Held? = null

    override suspend fun credential(preference: TranslationProvider?): SessionCredential = lock.withLock {
        val chosen = keys.resolve(preference) ?: throw BootstrapException(
            "No provider key is set up. Add one in Settings to start translating.",
        )
        // has() only says a ciphertext exists, so a null here means the saved
        // key can no longer be decrypted — not that none was ever saved.
        val apiKey = keys.key(chosen) ?: throw BootstrapException(
            "Your saved ${chosen.displayName} key can't be read on this device any more — " +
                "secure storage changed since it was saved. Open Settings and enter the key again.",
        )
        // Tied to the key it was minted from, so replacing the key in Settings
        // takes effect on the next socket rather than when the credential expires.
        val fingerprint = fingerprint(apiKey)
        held?.takeIf {
            it.credential.provider == chosen.provider &&
                it.keyFingerprint == fingerprint &&
                it.credential.expiresAtMs - now() >= MIN_REMAINING_MS
        }?.let { return@withLock it.credential }

        minter.mint(chosen, apiKey).also { held = Held(it, fingerprint) }
    }

    override fun discard(credential: SessionCredential) {
        if (held?.credential === credential) held = null
    }

    private fun fingerprint(key: String): String =
        MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    companion object {
        // A Gemini socket lives just under ten minutes and stops accepting
        // audio when its credential expires, so a credential must outlast one.
        const val MIN_REMAINING_MS = 12 * 60_000L
    }
}

/**
 * Proves a key works before it is saved, by opening a real translation
 * session with it and closing it again. That shows the key is accepted, the
 * account is in good standing, and the translate model is reachable on it.
 */
class ProviderKeyVerifier(
    private val minter: ProviderSessionMinter,
    private val socketFactory: () -> LiveSocketClient,
) {

    sealed interface Result {
        data object Valid : Result
        data class Rejected(val message: String) : Result
    }

    /** @param languageBcp47 the language the user wants to hear, which the provider must be able to speak. */
    suspend fun verify(provider: KeyProvider, apiKey: String, languageBcp47: String): Result {
        val protocol = TranslationLiveProtocols.forProvider(provider.provider)
        val wireLanguage = protocol.wireLanguage(languageBcp47)
            ?: return Result.Rejected(
                "${provider.displayName} can't translate into the language you chose. " +
                    "Pick another language, or use a different provider.",
            )
        val link = try {
            ProviderLink(minter.mint(provider, apiKey), protocol, socketFactory())
        } catch (failure: BootstrapException) {
            return Result.Rejected(failure.message ?: "That key could not be verified.")
        }
        return try {
            link.open(wireLanguage)
            Result.Valid
        } catch (failure: LinkFailure) {
            Result.Rejected(failure.message ?: "That key could not be verified.")
        } finally {
            link.close()
        }
    }
}

/**
 * Per-installation identifier. Random, generated locally, sent only as a
 * SHA-256 hash in OpenAI's safety-identifier header. It identifies an
 * installation, never a person.
 */
object InstallationId {
    private const val PREFS = "earslate_installation"
    private const val KEY = "anonymous_install_id"

    fun loadOrCreate(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY, null)
        if (existing != null && runCatching { UUID.fromString(existing) }.isSuccess) return existing
        return UUID.randomUUID().toString().also { prefs.edit().putString(KEY, it).apply() }
    }
}
