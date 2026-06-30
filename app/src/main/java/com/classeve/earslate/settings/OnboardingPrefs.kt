package com.classeve.earslate.settings

import android.content.Context

/**
 * Persistent first-launch marker. Tiny SharedPreferences-backed boolean; does
 * not justify pulling in DataStore.
 */
object OnboardingPrefs {

    private const val PREFS_NAME = "earslate_onboarding"
    private const val KEY_COMPLETED = "onboarding_completed"
    // Play "Prominent Disclosure & Consent": the user must affirmatively
    // acknowledge that captured audio is sent to a third party (Google
    // Gemini) BEFORE the first microphone capture. This boolean records that
    // one-time consent; requestStart() refuses to launch the mic until it's true.
    private const val KEY_AUDIO_DISCLOSURE = "audio_egress_disclosure_accepted"

    fun isCompleted(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_COMPLETED, false)

    fun markCompleted(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_COMPLETED, true)
            .apply()
    }

    fun isAudioDisclosureAccepted(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUDIO_DISCLOSURE, false)

    fun markAudioDisclosureAccepted(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUDIO_DISCLOSURE, true)
            .apply()
    }
}
