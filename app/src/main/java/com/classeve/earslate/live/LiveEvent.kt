package com.classeve.earslate.live

/**
 * Normalized events emitted by the LiveSocketClient. UI-facing types — the raw
 * Gemini Live envelopes (setup, generation_complete, tool_call, etc.) are
 * translated into this sealed hierarchy by LiveMessageParser so the rest of the
 * app never parses JSON.
 *
 * Blueprint §11 / §23.
 */
sealed interface LiveEvent {

    /** Model has accepted the setup frame. Safe to start sending audio. */
    data object SetupComplete : LiveEvent

    /** Raw 24 kHz PCM16 mono chunk to feed into AudioPlaybackEngine. */
    data class AudioChunk(val pcm24k: ByteArray) : LiveEvent {
        override fun equals(other: Any?): Boolean =
            other is AudioChunk && pcm24k.contentEquals(other.pcm24k)

        override fun hashCode(): Int = pcm24k.contentHashCode()
    }

    /** Partial/interim caption text for the translated output. */
    data class CaptionDelta(val text: String) : LiveEvent

    /**
     * End-of-turn marker from the model. Used to flush jitter buffer, commit
     * final caption line, and reset VAD counters.
     */
    data object TurnComplete : LiveEvent

    /** Session resumption handle for the next reconnect attempt. */
    data class ResumptionHandle(val handle: String) : LiveEvent

    /** Graceful server shutdown hint — start reconnection immediately. */
    data object GoAway : LiveEvent

    /** Socket closed (intentional or unexpected). Reason is human-readable. */
    data class SocketClosed(val code: Int, val reason: String) : LiveEvent

    /** Terminal error from the server — not recoverable on the same session. */
    data class Error(val message: String, val cause: Throwable? = null) : LiveEvent
}
