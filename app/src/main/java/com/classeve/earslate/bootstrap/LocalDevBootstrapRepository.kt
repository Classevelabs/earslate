package com.classeve.earslate.bootstrap

import com.classeve.earslate.BuildConfig
import com.classeve.earslate.session.SessionPolicy
import com.classeve.earslate.session.TargetLanguage

/**
 * Reads the Gemini Live API key from local.properties via BuildConfig. **Dev-only**
 * — the key must never ship in a production APK. Task 8 will add
 * `RemoteBootstrapRepository` that mints ephemeral credentials through the
 * ClassEve Worker instead, and the app will bind that in release builds.
 */
class LocalDevBootstrapRepository(
    private val settings: LocalDevSettings = LocalDevSettings(),
) : SessionBootstrapRepository {

    override suspend fun bootstrap(): SessionBootstrap {
        val key = BuildConfig.GEMINI_API_KEY
        if (key.isBlank()) {
            throw BootstrapException(
                "GEMINI_API_KEY is not set in local.properties. " +
                    "Either add it for local dev or bind RemoteBootstrapRepository.",
            )
        }
        val model = BuildConfig.GEMINI_LIVE_MODEL.ifBlank { DEFAULT_MODEL }
        return SessionBootstrap(
            ephemeralToken = key,
            model = model,
            targetLanguage = settings.targetLanguage,
            voiceName = settings.voiceName,
            captionsEnabled = settings.captionsEnabled,
            sessionPolicy = settings.sessionPolicy,
            source = BootstrapSource.LOCAL_DEV,
        )
    }

    companion object {
        const val DEFAULT_MODEL: String = "gemini-3.1-flash-live-preview"
    }
}

/** Settings surface the local dev repository needs. Later backed by DataStore. */
data class LocalDevSettings(
    val targetLanguage: TargetLanguage = TargetLanguage.EnglishUS,
    val voiceName: String? = null,
    val captionsEnabled: Boolean = true,
    val sessionPolicy: SessionPolicy = SessionPolicy.Default,
)
