package com.classeve.earslate.bootstrap

import android.content.Context
import com.classeve.earslate.BuildConfig
import com.classeve.earslate.auth.GeminiKeyStore
import com.classeve.earslate.session.SessionPolicy
import com.classeve.earslate.session.TargetLanguage

/**
 * Bring-your-own-key bootstrap. Reads the user's own Gemini API key from
 * on-device encrypted storage ([GeminiKeyStore], backed by
 * [com.classeve.earslate.auth.SecurePrefs]). There is no server, no account,
 * and no ClassEve-minted token involved — the key never leaves the device
 * except in the direct WebSocket connection to Google's Gemini Live endpoint.
 *
 * Throws [MissingApiKeyException] if no key is stored yet; the caller (UI)
 * must route the user to the key-setup screen instead of starting a session.
 */
class UserKeyBootstrapRepository(
    private val appContext: Context,
    private val settings: UserKeySettings = UserKeySettings(),
) : SessionBootstrapRepository {

    override suspend fun bootstrap(): SessionBootstrap {
        val key = GeminiKeyStore.load(appContext)
            ?: throw MissingApiKeyException()
        val model = BuildConfig.GEMINI_LIVE_MODEL.ifBlank { DEFAULT_MODEL }
        return SessionBootstrap(
            ephemeralToken = key,
            model = model,
            targetLanguage = settings.targetLanguage,
            voiceName = settings.voiceName,
            captionsEnabled = settings.captionsEnabled,
            sessionPolicy = settings.sessionPolicy,
            source = BootstrapSource.USER_KEY,
        )
    }

    companion object {
        const val DEFAULT_MODEL: String = "gemini-3.5-live-translate-preview"
    }
}

/** Settings surface the bootstrap repository needs. Later backed by DataStore. */
data class UserKeySettings(
    val targetLanguage: TargetLanguage = TargetLanguage.EnglishUS,
    val voiceName: String? = null,
    val captionsEnabled: Boolean = true,
    val sessionPolicy: SessionPolicy = SessionPolicy.Default,
)
