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
import com.classeve.earslate.session.TranslationProvider
import com.classeve.earslate.session.TranslatorPolicy
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
 * DataStore Preferences. earslate is a bidirectional conversation translator
 * that works both languages out by listening: the one language it cannot hear
 * is the device user's own, so that is the only translation setting.
 */
data class UserSettings(
    /** The device user's language. Seeded from the device locale on first run. */
    val myLanguageBcp47: String = "en-US",
    /**
     * The other person's language, or null for Automatic (learn it by listening).
     * Null is the default — the app works it out from the conversation. Set only
     * when the user wants "me → them" to work before the other person has spoken.
     */
    val otherLanguageBcp47: String? = null,
    val persistentNotification: Boolean = false,
    /** The provider the user chose, or null to use whichever one has a key. */
    val provider: TranslationProvider? = null,
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
        // A fresh key, not the old manual-mode "their_language_bcp47": absent
        // means Automatic, which is the default the product ships on.
        val OTHER_LANGUAGE = stringPreferencesKey("other_language_bcp47")
        val PERSISTENT_NOTIFICATION = booleanPreferencesKey("persistent_notification")
        // The key 0.5.3 wrote. Its "auto" reads as no choice, which means the same.
        val PROVIDER = stringPreferencesKey("translation_provider")
    }

    private val defaults = UserSettings()

    private fun read(prefs: Preferences) = UserSettings(
        myLanguageBcp47 = prefs[Keys.MY_LANGUAGE] ?: defaults.myLanguageBcp47,
        otherLanguageBcp47 = prefs[Keys.OTHER_LANGUAGE],
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

    /** [bcp47] null resets to Automatic (the session learns the other language). */
    suspend fun setOtherLanguage(bcp47: String?) {
        dataStore.edit { prefs ->
            if (bcp47 == null) prefs.remove(Keys.OTHER_LANGUAGE) else prefs[Keys.OTHER_LANGUAGE] = bcp47
        }
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
     * non-English users.
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
 * consumes. Always bidirectional. The outbound direction is Automatic by
 * default — it starts on English and follows whatever the other person is heard
 * speaking — unless the user has pinned an other-language, in which case it is
 * aimed there from the first frame.
 *
 * PRIVATE on purpose. This used to be public, and the service built a policy
 * out of `settings.value` — the eagerly-seeded StateFlow. On a cold process
 * (a Quick Settings tile tap, or the notification's Start action, after the
 * process had been killed) DataStore had not read from disk yet, so the policy
 * was built from the DEFAULT myLanguage "en-US" rather than the user's real
 * choice, and the session translated into the wrong language with nothing
 * anywhere reporting an error. The tile is the entry point the product is
 * documented around, which made this the most likely way to start a session and
 * the least likely to be noticed in testing from the app UI.
 *
 * Confining this to the file forces every policy through
 * [SettingsRepository.translatorPolicy], which cannot be called without
 * suspending for the real value.
 */
private fun UserSettings.toTranslatorPolicy(): TranslatorPolicy {
    val mine = SupportedLanguages.firstOrNull { it.bcp47 == myLanguageBcp47 }
        ?: TargetLanguage.EnglishUS
    // Null when unset OR when the saved tag is no longer a supported language:
    // either way, fall back to Automatic rather than a target that will not send.
    val other = otherLanguageBcp47?.let { tag -> SupportedLanguages.firstOrNull { it.bcp47 == tag } }
    return TranslatorPolicy(myLanguage = mine, otherLanguage = other, provider = provider)
}
