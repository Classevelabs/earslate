package com.classeve.earslate.live

import com.classeve.earslate.bootstrap.SessionBootstrap
import com.classeve.earslate.session.TranslationProvider

interface TranslationLiveProtocol {
    fun headers(bootstrap: SessionBootstrap): Map<String, String>
    fun setupFrame(bootstrap: SessionBootstrap, targetLanguageCode: String, captionsEnabled: Boolean): String
    fun audioFrame(pcm16k: ByteArray): String
    fun parse(frame: String): List<LiveEvent>
    fun gracefulCloseFrame(): String? = null
}

object TranslationLiveProtocols {
    fun forProvider(provider: TranslationProvider): TranslationLiveProtocol = when (provider) {
        TranslationProvider.GEMINI -> GeminiTranslationProtocol
    }
}

private object GeminiTranslationProtocol : TranslationLiveProtocol {
    override fun headers(bootstrap: SessionBootstrap): Map<String, String> =
        mapOf("Authorization" to "Token ${bootstrap.credential}")

    override fun setupFrame(
        bootstrap: SessionBootstrap,
        targetLanguageCode: String,
        captionsEnabled: Boolean,
    ): String = LiveSessionConfigFactory.buildSetup(
        model = bootstrap.model,
        targetLanguageCode = targetLanguageCode,
        echoTargetLanguage = false,
        captionsEnabled = captionsEnabled,
    )

    override fun audioFrame(pcm16k: ByteArray): String =
        LiveSessionConfigFactory.buildAudioChunk(pcm16k)

    override fun parse(frame: String): List<LiveEvent> = LiveMessageParser.parse(frame)
}
