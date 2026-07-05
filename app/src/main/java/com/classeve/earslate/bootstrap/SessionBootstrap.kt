package com.classeve.earslate.bootstrap

import com.classeve.earslate.session.SessionPolicy
import com.classeve.earslate.session.TargetLanguage

/**
 * What the SessionCoordinator needs to open a Live session. Produced by a
 * [SessionBootstrapRepository].
 *
 * earslate is bring-your-own-key: [ephemeralToken] is always the user's own
 * Gemini API key, read from on-device encrypted storage (see
 * [com.classeve.earslate.auth.SecurePrefs]). It is never sent anywhere except
 * Google's Gemini Live endpoint.
 */
data class SessionBootstrap(
    val ephemeralToken: String,
    val model: String,
    val targetLanguage: TargetLanguage,
    val voiceName: String?,
    val captionsEnabled: Boolean,
    val sessionPolicy: SessionPolicy,
    val source: BootstrapSource = BootstrapSource.USER_KEY,
)

enum class BootstrapSource {
    /** The user's own Gemini API key, stored on-device via SecurePrefs. */
    USER_KEY,
}

interface SessionBootstrapRepository {
    /**
     * Fetches a fresh bootstrap using the stored user key. Throws
     * [BootstrapException] (specifically [MissingApiKeyException]) if no key
     * is stored yet — the caller must route the user to the key-setup screen
     * instead of starting a session.
     */
    suspend fun bootstrap(): SessionBootstrap
}

/**
 * Generic bootstrap failure.
 */
open class BootstrapException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * No Gemini API key is stored on-device yet. UI must route the user to the
 * key-setup screen instead of starting a session.
 */
class MissingApiKeyException(
    message: String = "No Gemini API key is set. Add your API key to start translating.",
) : BootstrapException(message)
