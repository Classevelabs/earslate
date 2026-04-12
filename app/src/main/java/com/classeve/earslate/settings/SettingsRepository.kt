package com.classeve.earslate.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.classeve.earslate.session.OutputStyle
import com.classeve.earslate.session.RuntimeMode
import com.classeve.earslate.session.SessionPolicy
import com.classeve.earslate.session.SupportedLanguages
import com.classeve.earslate.session.TargetLanguage
import com.classeve.earslate.session.TranslatorPolicy
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
 * DataStore Preferences. Replaces the hardcoded values in [AppSettings].
 */
data class UserSettings(
    val targetLanguageBcp47: String = "en-US",
    val secondaryLanguageBcp47: String? = null,
    val conversationMode: Boolean = false,
    val externalOnly: Boolean = false,
    val captionsEnabled: Boolean = true,
    val preferEarbuds: Boolean = true,
    val diagnosticsEnabled: Boolean = false,
    val persistentNotification: Boolean = false,
)

class SettingsRepository(
    private val dataStore: DataStore<Preferences>,
    scope: CoroutineScope,
) {

    // ── preference keys ────────────────────────────────────────────────
    private object Keys {
        val TARGET_LANGUAGE = stringPreferencesKey("target_language_bcp47")
        val SECONDARY_LANGUAGE = stringPreferencesKey("secondary_language_bcp47")
        val CONVERSATION_MODE = booleanPreferencesKey("conversation_mode")
        val EXTERNAL_ONLY = booleanPreferencesKey("external_only")
        val CAPTIONS_ENABLED = booleanPreferencesKey("captions_enabled")
        val PREFER_EARBUDS = booleanPreferencesKey("prefer_earbuds")
        val DIAGNOSTICS_ENABLED = booleanPreferencesKey("diagnostics_enabled")
        val PERSISTENT_NOTIFICATION = booleanPreferencesKey("persistent_notification")
    }

    private val defaults = UserSettings()

    // ── observable state ───────────────────────────────────────────────
    val settings: StateFlow<UserSettings> = dataStore.data
        .map { prefs ->
            UserSettings(
                targetLanguageBcp47 = prefs[Keys.TARGET_LANGUAGE] ?: defaults.targetLanguageBcp47,
                secondaryLanguageBcp47 = prefs[Keys.SECONDARY_LANGUAGE],
                conversationMode = prefs[Keys.CONVERSATION_MODE] ?: defaults.conversationMode,
                externalOnly = prefs[Keys.EXTERNAL_ONLY] ?: defaults.externalOnly,
                captionsEnabled = prefs[Keys.CAPTIONS_ENABLED] ?: defaults.captionsEnabled,
                preferEarbuds = prefs[Keys.PREFER_EARBUDS] ?: defaults.preferEarbuds,
                diagnosticsEnabled = prefs[Keys.DIAGNOSTICS_ENABLED] ?: defaults.diagnosticsEnabled,
                persistentNotification = prefs[Keys.PERSISTENT_NOTIFICATION] ?: defaults.persistentNotification,
            )
        }
        .stateIn(scope, SharingStarted.Eagerly, defaults)

    // ── setters ────────────────────────────────────────────────────────
    suspend fun setTargetLanguage(bcp47: String) {
        dataStore.edit { prefs -> prefs[Keys.TARGET_LANGUAGE] = bcp47 }
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

    suspend fun setSecondaryLanguage(bcp47: String?) {
        dataStore.edit { prefs ->
            if (bcp47 != null) prefs[Keys.SECONDARY_LANGUAGE] = bcp47
            else prefs.remove(Keys.SECONDARY_LANGUAGE)
        }
    }

    suspend fun setConversationMode(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[Keys.CONVERSATION_MODE] = enabled }
    }

    suspend fun setExternalOnly(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[Keys.EXTERNAL_ONLY] = enabled }
    }

    suspend fun setPersistentNotification(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[Keys.PERSISTENT_NOTIFICATION] = enabled }
    }

    /**
     * On first launch, detect the device locale and set the target language if
     * the default is still en-US. This way the onboarding picker starts with
     * a sensible pre-selection for non-English users.
     */
    suspend fun initializeFromLocaleIfNeeded() {
        val current = settings.value
        if (current.targetLanguageBcp47 == "en-US") {
            val deviceLocale = java.util.Locale.getDefault()
            val deviceLang = deviceLocale.language // "hi", "es", "fr", etc.
            val match = SupportedLanguages.firstOrNull {
                it.bcp47.startsWith(deviceLang)
            }
            if (match != null && match.bcp47 != "en-US") {
                setTargetLanguage(match.bcp47)
            }
        }
    }
}

// ── policy mapping ─────────────────────────────────────────────────────
/**
 * Converts persisted [UserSettings] into the [TranslatorPolicy] the runtime
 * consumes. Resolves the BCP-47 string back to a [TargetLanguage] via the
 * [SupportedLanguages] list, falling back to [TargetLanguage.EnglishUS].
 */
fun UserSettings.toTranslatorPolicy(): TranslatorPolicy {
    val language = SupportedLanguages.firstOrNull { it.bcp47 == targetLanguageBcp47 }
        ?: TargetLanguage.EnglishUS
    val secondary = secondaryLanguageBcp47?.let { bcp ->
        SupportedLanguages.firstOrNull { it.bcp47 == bcp }
    }
    return TranslatorPolicy(
        targetLanguage = language,
        secondaryLanguage = secondary,
        mode = if (conversationMode) RuntimeMode.CONVERSATION else RuntimeMode.LISTEN,
        captionsEnabled = captionsEnabled,
        voiceName = null,
        outputStyle = OutputStyle.NEUTRAL,
        externalOnly = externalOnly,
        sessionPolicy = SessionPolicy.Default,
    )
}
