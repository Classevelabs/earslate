@file:Suppress("DEPRECATION")

package com.classeve.earslate.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * EncryptedSharedPreferences wrapper that fails closed — if the platform
 * keystore is unavailable, we never silently fall back to plaintext.
 * Mirrors Lven-Android/SecurePrefs; do not diverge without updating both.
 */
object SecurePrefs {
    private const val TAG = "SecurePrefs"

    fun preferences(context: Context, name: String): SharedPreferences {
        return try {
            buildEncryptedPrefs(context, name)
        } catch (firstError: Exception) {
            Log.w(TAG, "EncryptedSharedPreferences failed, attempting reset", firstError)
            try {
                context.deleteSharedPreferences(name)
            } catch (_: Exception) {
                // Older Android versions may not support deleteSharedPreferences
                // for encrypted prefs — ignore and retry building.
            }
            try {
                buildEncryptedPrefs(context, name)
            } catch (secondError: Exception) {
                Log.e(TAG, "EncryptedSharedPreferences fatal failure", secondError)
                throw IllegalStateException(
                    "Cannot access secure storage. Reinstall the app.",
                    secondError,
                )
            }
        }
    }

    private fun buildEncryptedPrefs(context: Context, name: String): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            name,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }
}
