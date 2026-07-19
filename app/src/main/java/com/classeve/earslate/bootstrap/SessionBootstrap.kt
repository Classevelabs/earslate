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
    suspend fun bootstrap(
        provider: TranslationProvider,
        targetLanguageCode: String,
    ): SessionBootstrap
}

open class BootstrapException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
