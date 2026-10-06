package com.classeve.earslate.live

import com.classeve.earslate.bootstrap.SessionCredential
import com.classeve.earslate.session.TranslationProvider

/**
 * Everything that differs between providers, in one place: what the socket
 * wants to be sent, what it sends back, and the handful of facts about the
 * provider that the session has to plan around.
 *
 * The session never branches on [provider]. It asks this interface, so a third
 * provider is one more implementation and nothing else.
 */
interface TranslationLiveProtocol {

    val provider: TranslationProvider

    /** Sample rate of the PCM16 mono audio the socket takes. */
    val inputSampleRateHz: Int

    /** How much audio goes in one frame. The provider's own recommendation. */
    val inputFrameMs: Int

    /**
     * True when a running session can be pointed at another language by
     * message. False means the language is fixed for the life of the socket,
     * and changing it takes a new one.
     */
    val retargetsInPlace: Boolean

    /**
     * The form of [bcp47] this provider's wire takes, or null when the provider
     * cannot translate INTO that language at all.
     */
    fun wireLanguage(bcp47: String): String?

    /** Headers for the WebSocket upgrade. Carries the short-lived credential. */
    fun headers(credential: SessionCredential): Map<String, String>

    /** The first frame after the socket opens. */
    fun setupFrame(credential: SessionCredential, targetWireLanguage: String): String

    /** Re-aims a running session. Only called when [retargetsInPlace]. */
    fun retargetFrame(targetWireLanguage: String): String? = null

    /** One frame of microphone audio at [inputSampleRateHz]. */
    fun audioFrame(pcm: ByteArray): String

    /** Never throws: a frame that cannot be read costs that frame and nothing else. */
    fun parse(frame: String): List<LiveEvent>

    /** Asks the provider to finish and close, where it has such a message. */
    fun gracefulCloseFrame(): String? = null
}

object TranslationLiveProtocols {
    fun forProvider(provider: TranslationProvider): TranslationLiveProtocol = when (provider) {
        TranslationProvider.GEMINI -> GeminiTranslationProtocol
        TranslationProvider.OPENAI -> OpenAiTranslationProtocol
    }
}

/** The language part of a tag: "es" from "es-ES", "zh" from "zh-Hant". */
internal fun primarySubtag(tag: String): String = tag.trim().substringBefore('-').lowercase()
