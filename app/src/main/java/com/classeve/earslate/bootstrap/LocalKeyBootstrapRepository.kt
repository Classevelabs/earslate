package com.classeve.earslate.bootstrap

import android.content.Context
import com.classeve.earslate.security.KeyProvider
import com.classeve.earslate.security.ProviderKeyStore
import com.classeve.earslate.session.TranslationProvider
import java.util.UUID

/**
 * Starts sessions from the key the user supplied, on the device, with no
 * server of ours involved at any point.
 *
 * This replaces the hosted broker earslate used through 0.3.x. It keeps the
 * same [SessionBootstrapRepository] shape on purpose: everything downstream —
 * the session coordinator, the socket client, reconnect — already speaks this
 * language, and swapping where a credential comes from should not ripple
 * through the runtime.
 */
class LocalKeyBootstrapRepository(
    private val keys: ProviderKeyStore,
    private val minter: ProviderSessionMinter,
) : SessionBootstrapRepository {

    override suspend fun bootstrap(
        provider: TranslationProvider,
        targetLanguageCode: String,
        captionsEnabled: Boolean,
    ): SessionBootstrap {
        val chosen = keys.resolve(provider)
            ?: throw missingKey(provider)
        val apiKey = keys.key(chosen) ?: throw missingKey(provider)

        return try {
            minter.mint(chosen, apiKey, targetLanguageCode, captionsEnabled)
        } catch (primaryFailure: BootstrapException) {
            // "Automatic" is a reliability promise, not a label. If the user
            // has a second key and the first provider is refusing sessions,
            // use it rather than failing the session in the user's face.
            if (provider != TranslationProvider.AUTOMATIC) throw primaryFailure
            val fallback = KeyProvider.entries
                .firstOrNull { it != chosen && keys.has(it) }
                ?: throw primaryFailure
            val fallbackKey = keys.key(fallback) ?: throw primaryFailure
            try {
                minter.mint(fallback, fallbackKey, targetLanguageCode, captionsEnabled)
            } catch (_: BootstrapException) {
                // Report the provider the user would have expected to be used.
                throw primaryFailure
            }
        }
    }

    private fun missingKey(requested: TranslationProvider): BootstrapException {
        val named = KeyProvider.forProvider(requested)
        return if (named != null) {
            BootstrapException(
                "No ${named.displayName} key is set up. Add one in Settings, or switch provider.",
            )
        } else {
            BootstrapException("No API key is set up yet. Add one in Settings to start translating.")
        }
    }
}

/**
 * Proves a key works before it is saved, by minting a real session with it and
 * throwing the session away.
 *
 * A format check can only say a string is shaped like a key. This says the key
 * is accepted, the account is in good standing, and the live translation model
 * is actually reachable on it — the three things that otherwise fail later, in
 * the middle of a conversation, when the user is least able to do anything
 * about it.
 */
class ProviderKeyVerifier(private val minter: ProviderSessionMinter) {

    sealed interface Result {
        data object Valid : Result
        data class Rejected(val message: String) : Result
    }

    suspend fun verify(
        provider: KeyProvider,
        apiKey: String,
        targetLanguageCode: String,
    ): Result = try {
        // Verified with transcription ON — the product default, and the richer
        // of the two configurations. A key that can mint this can mint the
        // captions-off variant; checking the other way round would pass a key
        // that then fails the first time the user leaves captions on.
        minter.mint(provider, apiKey, targetLanguageCode, captionsEnabled = true)
        Result.Valid
    } catch (failure: BootstrapException) {
        Result.Rejected(failure.message ?: "That key could not be verified.")
    }
}

/**
 * Per-installation identifier. Random, generated locally, never sent anywhere
 * except as a salted hash in OpenAI's safety-identifier header. It is not an
 * account, carries no entitlement, and identifies a device rather than a person.
 */
object InstallationId {
    private const val PREFS = "earslate_installation"
    private const val KEY = "anonymous_install_id"

    fun loadOrCreate(context: Context): String {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY, null)
        if (existing != null && runCatching { UUID.fromString(existing) }.isSuccess) return existing
        return UUID.randomUUID().toString().also { prefs.edit().putString(KEY, it).apply() }
    }
}
