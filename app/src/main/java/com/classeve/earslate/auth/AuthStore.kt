package com.classeve.earslate.auth

import android.content.Context

/** Session payload persisted between app launches. */
data class AuthSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochMs: Long,
    val email: String? = null,
)

/**
 * SharedPreferences-backed session store. Uses EncryptedSharedPreferences via
 * [SecurePrefs] so the refresh token is never on disk in plaintext — the
 * same discipline Lven-Android applies.
 */
object AuthStore {
    private const val PREFS = "classeve_earslate_session"
    private const val KEY_ACCESS = "access_token"
    private const val KEY_REFRESH = "refresh_token"
    private const val KEY_EXPIRES_AT = "expires_at"
    private const val KEY_EMAIL = "email"

    fun save(context: Context, session: AuthSession) {
        SecurePrefs.preferences(context, PREFS).edit()
            .putString(KEY_ACCESS, session.accessToken)
            .putString(KEY_REFRESH, session.refreshToken)
            .putLong(KEY_EXPIRES_AT, session.expiresAtEpochMs)
            .putString(KEY_EMAIL, session.email)
            .apply()
    }

    fun load(context: Context): AuthSession? {
        val prefs = SecurePrefs.preferences(context, PREFS)
        val access = prefs.getString(KEY_ACCESS, null) ?: return null
        val refresh = prefs.getString(KEY_REFRESH, null) ?: return null
        return AuthSession(
            accessToken = access,
            refreshToken = refresh,
            expiresAtEpochMs = prefs.getLong(KEY_EXPIRES_AT, 0),
            email = prefs.getString(KEY_EMAIL, null),
        )
    }

    fun clear(context: Context) {
        SecurePrefs.preferences(context, PREFS).edit().clear().apply()
    }

    fun isSessionExpired(context: Context): Boolean {
        val session = load(context) ?: return true
        // Refresh 5 min before actual expiry so downstream calls don't race
        // with the Worker clock.
        return System.currentTimeMillis() >= session.expiresAtEpochMs - 5 * 60_000
    }

    /**
     * Forces [isSessionExpired] to return true on the next read by zeroing the
     * stored expiry timestamp. Called when the worker rejects a token the
     * local clock thought was valid (server-side revocation, key rotation,
     * clock skew) so the next [accessTokenOrNull] check triggers a refresh.
     */
    fun markAccessTokenExpired(context: Context) {
        SecurePrefs.preferences(context, PREFS).edit()
            .putLong(KEY_EXPIRES_AT, 0L)
            .apply()
    }

    fun accessTokenOrNull(context: Context): String? = load(context)?.accessToken
    fun refreshTokenOrNull(context: Context): String? = load(context)?.refreshToken
}
