package com.classeve.earslate.bootstrap

import com.classeve.earslate.session.TranslationProvider

data class SessionBootstrap(
    val credential: String,
    val provider: TranslationProvider,
    val webSocketUrl: String,
    val model: String,
    val expiresAt: String? = null,
)

interface SessionBootstrapRepository {
    /**
     * @param captionsEnabled the session's caption setting. It belongs here
     *   rather than only at the setup frame because a Gemini ephemeral token
     *   LOCKS the session configuration, and transcription is part of that
     *   configuration. A credential minted for a different config than the one
     *   the client then asks for describes a session neither side agreed to.
     */
    suspend fun bootstrap(
        provider: TranslationProvider,
        targetLanguageCode: String,
        captionsEnabled: Boolean,
    ): SessionBootstrap
}

open class BootstrapException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
