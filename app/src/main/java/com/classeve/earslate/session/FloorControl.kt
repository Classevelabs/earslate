package com.classeve.earslate.session

/**
 * Who has the floor when the translation comes out of a loudspeaker the
 * microphone can hear.
 *
 * The model starts translating a few seconds into a sentence, while the person
 * is still talking. Over a loudspeaker that cannot work: the phone would talk
 * over them, and the microphone would have to close mid-sentence to avoid
 * hearing itself. So the translation waits for the person to pause, is then
 * played in one piece, and the microphone is closed only while it plays.
 *
 * Pure; the owner calls [update] on a steady tick.
 */
class FloorControl {

    enum class Floor { LISTENING, SPEAKING, SETTLING }

    var floor: Floor = Floor.LISTENING
        private set

    private var waitingSinceMs = NEVER
    private var arrivedAtMs = NEVER
    private var idleSinceMs = NEVER
    private var settledAtMs = NEVER

    /** The microphone must send silence: what it hears now is the phone itself. */
    val micClosed: Boolean get() = floor != Floor.LISTENING

    /** Translated speech is being let out. */
    val playing: Boolean get() = floor == Floor.SPEAKING

    /**
     * @param lastMicSpeechAtMs when the microphone last heard a voice.
     * @param waitingMs translated speech queued and not yet played.
     * @param audible true while translated speech is still coming out of the loudspeaker.
     * @param arriving true while more of the translation is still arriving.
     */
    fun update(nowMs: Long, lastMicSpeechAtMs: Long, waitingMs: Int, audible: Boolean, arriving: Boolean) {
        when (floor) {
            Floor.LISTENING -> {
                if (waitingMs <= 0) {
                    waitingSinceMs = NEVER
                    arrivedAtMs = NEVER
                    return
                }
                if (waitingSinceMs == NEVER) waitingSinceMs = nowMs
                if (arriving) arrivedAtMs = NEVER else if (arrivedAtMs == NEVER) arrivedAtMs = nowMs
                val roomQuiet = nowMs - lastMicSpeechAtMs >= QUIET_MS
                // Music or a crowd never sounds quiet. The translation itself
                // then says when the person stopped: had they gone on, more of
                // it would have started arriving by now.
                val nothingMoreComing = arrivedAtMs != NEVER && nowMs - arrivedAtMs >= NOTHING_MORE_MS
                // A room that is never quiet must not hold a translation forever.
                val heldTooLong = nowMs - waitingSinceMs >= MAX_HOLD_MS
                if (roomQuiet || nothingMoreComing || heldTooLong) {
                    floor = Floor.SPEAKING
                    idleSinceMs = NEVER
                }
            }

            Floor.SPEAKING -> {
                if (waitingMs > 0 || audible) {
                    idleSinceMs = NEVER
                    return
                }
                if (idleSinceMs == NEVER) idleSinceMs = nowMs
                // The translation's last words arrive after a pause of their
                // own, so one empty moment is not the end of it.
                if (nowMs - idleSinceMs >= SPEECH_END_MS) {
                    floor = Floor.SETTLING
                    settledAtMs = nowMs + SETTLE_MS
                }
            }

            Floor.SETTLING -> if (nowMs >= settledAtMs) {
                floor = Floor.LISTENING
                waitingSinceMs = NEVER
                arrivedAtMs = NEVER
            }
        }
    }

    fun reset() {
        floor = Floor.LISTENING
        waitingSinceMs = NEVER
        arrivedAtMs = NEVER
        idleSinceMs = NEVER
        settledAtMs = NEVER
    }

    companion object {
        private const val NEVER = -1L

        /** A pause this long means the person has stopped, not drawn breath. */
        const val QUIET_MS = 700L
        const val MAX_HOLD_MS = 20_000L

        /** The model starts on new speech within about three seconds of hearing it. */
        const val NOTHING_MORE_MS = 4_000L
        const val SPEECH_END_MS = 500L

        /** The room, and the loudspeaker's own buffer, need this long to fall silent. */
        const val SETTLE_MS = 300L
    }
}
