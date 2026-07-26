package com.classeve.earslate.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.classeve.earslate.session.SupportedLanguages
import com.classeve.earslate.session.TargetLanguage
import com.classeve.earslate.session.TranslatorPolicy
import com.classeve.earslate.session.TranslationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Single app-wide DataStore instance — must be a top-level extension property. */
val Context.earslateDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "earslate_settings",
)

/**
 * All user-facing settings that survive app restarts, backed by Jetpack
 * DataStore Preferences. earslate is a bidirectional conversation translator:
 * the only two settings that shape translation are the two languages.
 */
data class UserSettings(
    /** The device user's language. */
    val myLanguageBcp47: String = "en-US",
    /** The other person's language. Defaults to English. */
    val theirLanguageBcp47: String = "en-US",
    val externalOnly: Boolean = false,
    val captionsEnabled: Boolean = true,
    val preferEarbuds: Boolean = true,
    val diagnosticsEnabled: Boolean = false,
    val persistentNotification: Boolean = false,
    val provider: TranslationProvider = TranslationProvider.AUTOMATIC,
)

class SettingsRepository(
    private val dataStore: DataStore<Preferences>,
    scope: CoroutineScope,
) {

    // ── preference keys ────────────────────────────────────────────────
    private object Keys {
        // NOTE: key string kept as the historical "target_language_bcp47" so an
        // existing install's chosen language survives the upgrade.
        val MY_LANGUAGE = stringPreferencesKey("target_language_bcp47")
        val THEIR_LANGUAGE = stringPreferencesKey("their_language_bcp47")
        val EXTERNAL_ONLY = booleanPreferencesKey("external_only")
        val CAPTIONS_ENABLED = booleanPreferencesKey("captions_enabled")
        val PREFER_EARBUDS = booleanPreferencesKey("prefer_earbuds")
        val DIAGNOSTICS_ENABLED = booleanPreferencesKey("diagnostics_enabled")
        val PERSISTENT_NOTIFICATION = booleanPreferencesKey("persistent_notification")
        val PROVIDER = stringPreferencesKey("translation_provider")
    }

    private val defaults = UserSettings()

    // ── observable state ───────────────────────────────────────────────
    val settings: StateFlow<UserSettings> = dataStore.data
        .map { prefs ->
            UserSettings(
                myLanguageBcp47 = prefs[Keys.MY_LANGUAGE] ?: defaults.myLanguageBcp47,
                theirLanguageBcp47 = prefs[Keys.THEIR_LANGUAGE] ?: defaults.theirLanguageBcp47,
                externalOnly = prefs[Keys.EXTERNAL_ONLY] ?: defaults.externalOnly,
                captionsEnabled = prefs[Keys.CAPTIONS_ENABLED] ?: defaults.captionsEnabled,
                preferEarbuds = prefs[Keys.PREFER_EARBUDS] ?: defaults.preferEarbuds,
                diagnosticsEnabled = prefs[Keys.DIAGNOSTICS_ENABLED] ?: defaults.diagnosticsEnabled,
                persistentNotification = prefs[Keys.PERSISTENT_NOTIFICATION] ?: defaults.persistentNotification,
                provider = TranslationProvider.fromWireValue(prefs[Keys.PROVIDER]),
            )
        }
        .stateIn(scope, SharingStarted.Eagerly, defaults)

    // ── setters ────────────────────────────────────────────────────────
    suspend fun setMyLanguage(bcp47: String) {
        dataStore.edit { prefs -> prefs[Keys.MY_LANGUAGE] = bcp47 }
    }

    suspend fun setTheirLanguage(bcp47: String) {
        dataStore.edit { prefs -> prefs[Keys.THEIR_LANGUAGE] = bcp47 }
    }

    suspend fun setCaptionsEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[Keys.CAPTIONS_ENABLED] = enabled }
    }

    suspend fun setPreferEarbuds(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[Keys.PREFER_EARBUDS] = enabled }
    }

    suspend fun setDiagnosticsEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[Keys.DIAGNOSTICS_ENABLED] = enabled }
    }

    suspend fun setExternalOnly(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[Keys.EXTERNAL_ONLY] = enabled }
    }

    suspend fun setPersistentNotification(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[Keys.PERSISTENT_NOTIFICATION] = enabled }
    }

    suspend fun setProvider(provider: TranslationProvider) {
        dataStore.edit { prefs -> prefs[Keys.PROVIDER] = provider.wireValue }
    }

    /**
     * On first launch, detect the device locale and set MY language if the
     * default is still en-US, so the picker starts on a sensible language for
     * non-English users. "Their" language stays English by default.
     */
    suspend fun initializeFromLocaleIfNeeded() {
        val current = settings.value
        if (current.myLanguageBcp47 == "en-US") {
            val deviceLang = java.util.Locale.getDefault().language // "hi", "es", ...
            val match = SupportedLanguages.firstOrNull { it.bcp47.startsWith(deviceLang) }
            if (match != null && match.bcp47 != "en-US") {
                setMyLanguage(match.bcp47)
            }
        }
    }
}

// ── policy mapping ─────────────────────────────────────────────────────
/**
 * Converts persisted [UserSettings] into the [TranslatorPolicy] the runtime
 * consumes. Always bidirectional — no modes.
 */
fun UserSettings.toTranslatorPolicy(): TranslatorPolicy {
    val mine = SupportedLanguages.firstOrNull { it.bcp47 == myLanguageBcp47 }
        ?: TargetLanguage.EnglishUS
    val theirs = SupportedLanguages.firstOrNull { it.bcp47 == theirLanguageBcp47 }
        ?: TargetLanguage.EnglishUS
    return TranslatorPolicy(
        myLanguage = mine,
        theirLanguage = theirs,
        captionsEnabled = captionsEnabled,
        externalOnly = externalOnly,
        provider = provider,
    )
}
