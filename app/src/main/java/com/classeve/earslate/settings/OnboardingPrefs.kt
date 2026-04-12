package com.classeve.earslate.settings

import android.content.Context

/**
 * Persistent first-launch marker. Tiny SharedPreferences-backed boolean; does
 * not justify pulling in DataStore.
 */
object OnboardingPrefs {

    private const val PREFS_NAME = "earslate_onboarding"
    private const val KEY_COMPLETED = "onboarding_completed"

    fun isCompleted(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_COMPLETED, false)

    fun markCompleted(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_COMPLETED, true)
            .apply()
    }
}
