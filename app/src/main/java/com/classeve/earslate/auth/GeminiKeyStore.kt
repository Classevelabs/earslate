package com.classeve.earslate.auth

import android.content.Context

/**
 * On-device store for the user's own Gemini API key. earslate is
 * bring-your-own-key: this key is supplied by the user, stored only in
 * [SecurePrefs] (EncryptedSharedPreferences), and never sent anywhere except
 * directly to Google's Gemini Live endpoint. ClassEve servers never see it.
 */
object GeminiKeyStore {
    private const val PREFS = "classeve_earslate_gemini_key"
    private const val KEY_API_KEY = "gemini_api_key"

    fun save(context: Context, apiKey: String) {
        SecurePrefs.preferences(context, PREFS).edit()
            .putString(KEY_API_KEY, apiKey)
            .apply()
    }

    fun load(context: Context): String? {
        val key = SecurePrefs.preferences(context, PREFS).getString(KEY_API_KEY, null)
        return key?.takeIf { it.isNotBlank() }
    }

    fun hasKey(context: Context): Boolean = load(context) != null

    fun clear(context: Context) {
        SecurePrefs.preferences(context, PREFS).edit().clear().apply()
    }
}
