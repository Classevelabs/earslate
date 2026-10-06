package com.classeve.earslate.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.classeve.earslate.session.TranslationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * What a person saved in the version they have now is what the next version
 * reads. The key names here are the ones 0.5.3 wrote to the phone.
 */
class SettingsRepositoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun close() = scope.cancel()

    private var phones = 0

    private fun store(): DataStore<Preferences> {
        val file = java.io.File(folder.root, "settings-${phones++}.preferences_pb")
        return PreferenceDataStoreFactory.create(scope = scope) { file }
    }

    /** A phone that ran 0.5.3 and saved [strings] and [flags]. */
    private fun upgraded(strings: Map<String, String> = emptyMap(), flags: Map<String, Boolean> = emptyMap()): SettingsRepository {
        val store = store()
        runBlocking {
            store.edit { prefs ->
                strings.forEach { (name, value) -> prefs[stringPreferencesKey(name)] = value }
                flags.forEach { (name, value) -> prefs[booleanPreferencesKey(name)] = value }
            }
        }
        return SettingsRepository(store, scope)
    }

    @Test
    fun `the language chosen in 0_5_3 is still the language`() = runBlocking {
        val settings = upgraded(strings = mapOf("target_language_bcp47" to "hi-IN"))
        assertEquals("hi-IN", settings.awaitSettings().myLanguageBcp47)
        assertEquals("hi-IN", settings.translatorPolicy().myLanguage.bcp47)
    }

    @Test
    fun `the provider chosen in 0_5_3 is still the provider`() = runBlocking {
        assertEquals(TranslationProvider.OPENAI, upgraded(mapOf("translation_provider" to "openai")).awaitSettings().provider)
        assertEquals(TranslationProvider.GEMINI, upgraded(mapOf("translation_provider" to "gemini")).awaitSettings().provider)
    }

    @Test
    fun `Automatic in 0_5_3 means whichever provider has a key`() = runBlocking {
        assertNull(upgraded(mapOf("translation_provider" to "auto")).awaitSettings().provider)
        assertNull(upgraded().awaitSettings().provider)
    }

    @Test
    fun `a pair fixed by hand in 0_5_3 is still fixed`() = runBlocking {
        val settings = upgraded(
            strings = mapOf("target_language_bcp47" to "en-US", "their_language_bcp47" to "ja-JP"),
            flags = mapOf("manual_languages" to true),
        )
        assertEquals("ja-JP", settings.awaitSettings().otherLanguageBcp47)
        assertEquals("ja-JP", settings.translatorPolicy().otherLanguage?.bcp47)
    }

    // 0.5.3 kept a "their language" for everyone; it only counted with the switch on.
    @Test
    fun `a 0_5_3 phone that left the pair to the app stays on Automatic`() = runBlocking {
        val settings = upgraded(strings = mapOf("their_language_bcp47" to "ja-JP"), flags = mapOf("manual_languages" to false))
        assertNull(settings.awaitSettings().otherLanguageBcp47)
        assertNull(upgraded(strings = mapOf("their_language_bcp47" to "ja-JP")).awaitSettings().otherLanguageBcp47)
    }

    @Test
    fun `choosing Automatic afterwards is not undone by what 0_5_3 saved`() = runBlocking {
        val settings = upgraded(strings = mapOf("their_language_bcp47" to "ja-JP"), flags = mapOf("manual_languages" to true))
        settings.setOtherLanguage(null)
        assertNull(settings.awaitSettings().otherLanguageBcp47)
    }

    @Test
    fun `a language chosen afterwards replaces the one 0_5_3 saved`() = runBlocking {
        val settings = upgraded(strings = mapOf("their_language_bcp47" to "ja-JP"), flags = mapOf("manual_languages" to true))
        settings.setOtherLanguage("es-ES")
        assertEquals("es-ES", settings.awaitSettings().otherLanguageBcp47)
        settings.setOtherLanguage(null)
        assertNull(settings.awaitSettings().otherLanguageBcp47)
    }

    // ── the language a new install starts in ───────────────────────────────

    private fun startsIn(tag: String): String = runBlocking {
        val settings = upgraded()
        settings.initializeFromLocaleIfNeeded(java.util.Locale.forLanguageTag(tag))
        settings.awaitSettings().myLanguageBcp47
    }

    // "fil-PH" starts with "fi", and comes first in the list.
    @Test
    fun `a Finnish phone starts in Finnish, not Filipino`() {
        assertEquals("fi-FI", startsIn("fi-FI"))
        assertEquals("fil-PH", startsIn("fil-PH"))
    }

    @Test
    fun `a phone starts in its own variant of a language when the picker has it`() {
        assertEquals("en-GB", startsIn("en-GB"))
        assertEquals("zh-TW", startsIn("zh-TW"))
        assertEquals("zh-CN", startsIn("zh-CN"))
        assertEquals("pt-BR", startsIn("pt-PT"))
        assertEquals("es-ES", startsIn("es-MX"))
    }

    @Test
    fun `a phone set to Norwegian under either of its names starts in Norwegian`() {
        assertEquals("nb-NO", startsIn("nb-NO"))
        assertEquals("nb-NO", startsIn("no-NO"))
    }

    @Test
    fun `Hebrew and Indonesian phones start in their own languages`() {
        assertEquals("he-IL", startsIn("he-IL"))
        assertEquals("id-ID", startsIn("id-ID"))
    }

    @Test
    fun `a phone in a language the picker does not have starts in English`() {
        assertEquals("en-US", startsIn("sw-KE"))
        assertEquals("en-US", startsIn("en-US"))
    }

    @Test
    fun `a language already chosen is not replaced by the phone's`() = runBlocking {
        val settings = upgraded(strings = mapOf("target_language_bcp47" to "hi-IN"))
        settings.initializeFromLocaleIfNeeded(java.util.Locale.FRANCE)
        assertEquals("hi-IN", settings.awaitSettings().myLanguageBcp47)
    }

    @Test
    fun `a fresh install is English, Automatic, no provider chosen`() = runBlocking {
        val fresh = upgraded().awaitSettings()
        assertEquals("en-US", fresh.myLanguageBcp47)
        assertNull(fresh.otherLanguageBcp47)
        assertNull(fresh.provider)
    }
}
