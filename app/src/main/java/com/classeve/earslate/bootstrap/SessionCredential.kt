package com.classeve.earslate.bootstrap

import com.classeve.earslate.session.TranslationProvider

/**
 * A short-lived secret that opens provider sockets until [expiresAtMs]. The
 * user's own key is traded for one of these, so the key itself never goes on a
 * socket.
 */
class SessionCredential(
    val provider: TranslationProvider,
    val secret: String,
    val webSocketUrl: String,
    val model: String,
    val expiresAtMs: Long,
    val safetyIdentifier: String? = null,
) {
    // Not a data class: a generated toString would print the secret.
    override fun toString(): String = "SessionCredential(${provider.wireValue})"
}

interface SessionCredentialSource {

    /**
     * A credential that can open sockets now. The same one is handed out until
     * it is too close to expiring, so a session mints once however many sockets
     * it opens.
     *
     * @param preference the provider the user chose, or null to use whichever
     *   one has a key.
     */
    suspend fun credential(preference: TranslationProvider?): SessionCredential

    /** The provider refused [credential]; the next request mints a new one. */
    fun discard(credential: SessionCredential)
}

open class BootstrapException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
