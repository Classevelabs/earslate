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
import kotlinx.coroutines.flow.first
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

    private fun read(prefs: Preferences) = UserSettings(
        myLanguageBcp47 = prefs[Keys.MY_LANGUAGE] ?: defaults.myLanguageBcp47,
        theirLanguageBcp47 = prefs[Keys.THEIR_LANGUAGE] ?: defaults.theirLanguageBcp47,
        externalOnly = prefs[Keys.EXTERNAL_ONLY] ?: defaults.externalOnly,
        captionsEnabled = prefs[Keys.CAPTIONS_ENABLED] ?: defaults.captionsEnabled,
        preferEarbuds = prefs[Keys.PREFER_EARBUDS] ?: defaults.preferEarbuds,
        diagnosticsEnabled = prefs[Keys.DIAGNOSTICS_ENABLED] ?: defaults.diagnosticsEnabled,
        persistentNotification = prefs[Keys.PERSISTENT_NOTIFICATION] ?: defaults.persistentNotification,
        provider = TranslationProvider.fromWireValue(prefs[Keys.PROVIDER]),
    )

    // ── observable state ───────────────────────────────────────────────
    /**
     * For DISPLAY. Seeded with [defaults] and updated when the disk read lands,
     * which means `settings.value` is the DEFAULTS until then — and a default is
     * indistinguishable from a real setting that happens to equal it.
     *
     * That is fine for a label that will recompose a frame later. It is not fine
     * for anything that acts on the value once and cannot be taken back. Use
     * [awaitSettings] for those. See [translatorPolicy] for what went wrong.
     */
    val settings: StateFlow<UserSettings> = dataStore.data
        .map(::read)
        .stateIn(scope, SharingStarted.Eagerly, defaults)

    /**
     * The settings as they actually are on disk. Suspends until the first real
     * read completes rather than handing back the seed.
     */
    suspend fun awaitSettings(): UserSettings = read(dataStore.data.first())

    /**
     * The policy for a NEW session, built from settings that are really loaded.
     *
     * This is the ONLY way to obtain a [TranslatorPolicy]: the mapping itself is
     * private to this file, so a caller cannot build one out of the display
     * StateFlow by accident. See the note on `toTranslatorPolicy`.
     */
    suspend fun translatorPolicy(): TranslatorPolicy = awaitSettings().toTranslatorPolicy()

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
        // awaitSettings, NOT settings.value. The seed reports "en-US" before the
        // disk read lands, so reading the StateFlow here could see a default,
        // decide the user had never chosen a language, and overwrite a real
        // saved choice with the device locale. This method WRITES, so a wrong
        // read is not a stale label — it is silent data loss.
        val current = awaitSettings()
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
 *
 * PRIVATE on purpose. This used to be public, and the service built a policy
 * out of `settings.value` — the eagerly-seeded StateFlow. On a cold process
 * (a Quick Settings tile tap, or the notification's Start action, after the
 * process had been killed) DataStore had not read from disk yet, so the policy
 * was built from the DEFAULTS: myLanguage and theirLanguage both "en-US".
 *
 * runSession collapses to a single leg when the two languages match, so the
 * session opened, connected, listened, and translated English into English.
 * The user's language pair, captions choice and provider choice were all
 * silently discarded, and nothing anywhere reported an error — the app looked
 * like it was working and produced nothing. The tile is the entry point the
 * product is documented around, which made this the most likely way to start
 * a session and the least likely to be noticed in testing from the app UI.
 *
 * Confining this to the file forces every policy through
 * [SettingsRepository.translatorPolicy], which cannot be called without
 * suspending for the real value.
 */
private fun UserSettings.toTranslatorPolicy(): TranslatorPolicy {
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
