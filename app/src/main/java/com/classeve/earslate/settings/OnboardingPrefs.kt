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
    // acknowledge that captured audio is sent to a third party (the selected
    // provider — Gemini or OpenAI) BEFORE the first microphone capture. This
    // boolean records that one-time consent; requestStart() refuses to launch
    // the mic until it's true.
    private const val KEY_AUDIO_DISCLOSURE = "audio_egress_disclosure_accepted"

    /**
     * Whether the user has confirmed which language they speak, as opposed to
     * having had it guessed from the device locale.
     *
     * The two are not the same fact and the app had no way to tell them apart:
     * an English-locale phone is seeded with English and looks identical to
     * someone who chose English. That was survivable while the main screen still
     * had pickers on it. It is not now — the only route left is Settings →
     * Advanced — so setup asks, and this records that it was answered.
     */
    private const val KEY_LANGUAGE_CHOSEN = "language_chosen"

    /** Cleared once a session has actually been started, so the hint stops. */
    private const val KEY_EVER_STARTED = "ever_started_a_session"

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

    fun isLanguageChosen(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_LANGUAGE_CHOSEN, false)

    fun markLanguageChosen(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_LANGUAGE_CHOSEN, true)
            .apply()
    }

    fun hasEverStarted(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_EVER_STARTED, false)

    fun markStarted(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_EVER_STARTED, true)
            .apply()
    }
}
