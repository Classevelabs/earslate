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
    /** The device user's language. Seeded from the device locale on first run. */
    val myLanguageBcp47: String = "en-US",
    /** Honoured only when [manualLanguages] is on. Otherwise the session learns it. */
    val theirLanguageBcp47: String = "en-US",
    /**
     * Off by default, and off is the product: earslate listens, works out what
     * is being spoken, and speaks back in it. Both languages are then decided
     * by what the microphone hears, and there is nothing to set up.
     *
     * On, the two languages come from the pickers instead and the session stops
     * following the conversation. It exists for the case where someone knows
     * exactly which pair they want and does not want it moving — which is the
     * narrow case, which is why it is buried and why it is off.
     */
    val manualLanguages: Boolean = false,
    val externalOnly: Boolean = false,
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
        // A NEW key, not the old "conversation_mode". Anyone who had that on is
        // opted into automatic detection rather than into a manual pair they
        // chose for a feature that no longer works the way it did.
        val MANUAL_LANGUAGES = booleanPreferencesKey("manual_languages")
        val EXTERNAL_ONLY = booleanPreferencesKey("external_only")
        val PERSISTENT_NOTIFICATION = booleanPreferencesKey("persistent_notification")
        val PROVIDER = stringPreferencesKey("translation_provider")
    }

    private val defaults = UserSettings()

    private fun read(prefs: Preferences) = UserSettings(
        myLanguageBcp47 = prefs[Keys.MY_LANGUAGE] ?: defaults.myLanguageBcp47,
        theirLanguageBcp47 = prefs[Keys.THEIR_LANGUAGE] ?: defaults.theirLanguageBcp47,
        externalOnly = prefs[Keys.EXTERNAL_ONLY] ?: defaults.externalOnly,
        manualLanguages = prefs[Keys.MANUAL_LANGUAGES] ?: defaults.manualLanguages,
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

    suspend fun setManualLanguages(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[Keys.MANUAL_LANGUAGES] = enabled }
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
        // Automatic starts on English and moves: English is what an
        // unrecognised speaker is treated as, and it is also the pair that
        // collapses to a single leg for an English-speaking user, so the second
        // socket is not opened until there is actually a second language to
        // aim it at.
        theirLanguage = if (manualLanguages) theirs else TargetLanguage.EnglishUS,
        manualLanguages = manualLanguages,
        // Not a setting. The source transcript this turns on is what the
        // automatic language detection reads, so switching it off would switch
        // off the product's main behaviour to save nothing.
        captionsEnabled = true,
        externalOnly = externalOnly,
        provider = provider,
    )
}
