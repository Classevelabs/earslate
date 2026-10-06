package com.classeve.earslate.live

/**
 * What a provider's socket said, in the app's own terms.
 *
 * There is no "turn finished" event: both translate models stream continuously
 * and neither marks the end of an utterance, so that is read from the audio.
 */
sealed interface LiveEvent {

    /** The provider has accepted the session configuration. Audio may flow. */
    data object SetupComplete : LiveEvent

    /** Translated speech, PCM16 mono at [sampleRateHz]. May be pure silence. */
    class AudioChunk(val pcm: ByteArray, val sampleRateHz: Int) : LiveEvent

    /** Text of the translated speech, in fragments to be joined as they come. */
    data class CaptionDelta(val text: String) : LiveEvent

    /**
     * What the microphone heard. [languageCode] is the provider's own verdict
     * on the language, when it gives one; [text] may be empty when it only
     * repeats that verdict.
     */
    data class SourceTranscript(val text: String, val languageCode: String?) : LiveEvent

    /** The provider will end this connection, in [timeLeftMs] if it said how long. */
    data class GoAway(val timeLeftMs: Long?) : LiveEvent

    /** The provider will end the session at this wall-clock second. */
    data class SessionExpiry(val epochSeconds: Long) : LiveEvent

    /** The provider has finished closing a session it was asked to close. */
    data object SessionClosed : LiveEvent

    /** A provider message that does not end the session by itself. */
    data class ProviderNotice(val message: String) : LiveEvent
}
