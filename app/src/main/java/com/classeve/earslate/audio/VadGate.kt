package com.classeve.earslate.audio

/**
 * Voice activity detection with pre-roll and hangover. Blueprint §9.
 *
 * The capture loop feeds every frame to [process] and dispatches based on the
 * returned [VadResult]. The gate maintains a rolling pre-roll buffer so the
 * opening of the gate also emits the 200–300 ms of audio that came immediately
 * before — avoiding clipped consonants on short utterances like "hi" / "yes".
 *
 * Implementation is simple RMS-energy-based. Good enough to gate pure silence
 * without clipping speech. Replaceable (WebRTC VAD, Silero, on-device ML) behind
 * this interface later.
 */
interface VadGate {
    fun process(frame: ByteArray): VadResult
    fun reset()
}

sealed interface VadResult {
    /** Still quiet — don't send. */
    data object Silence : VadResult

    /** Gate just opened. Caller sends pre-roll then the current frame. */
    data class Opening(val preRoll: List<ByteArray>, val current: ByteArray) : VadResult

    /** Gate is open and this frame should be sent. */
    data class Speaking(val frame: ByteArray) : VadResult

    /** Gate just closed — utterance end. Caller may flush any pending send. */
    data object Closing : VadResult
}

class EnergyVadGate(
    private val openFrames: Int = 2,
    private val hangoverFrames: Int = 8,    // ~160 ms at 20 ms frames
    private val minUtteranceFrames: Int = 3, // ~60 ms
    // earslate translates *other people's* speech across a table/room — inherently
    // far-field (~300-800 RMS). A 600 floor gated much of that out. 350 captures
    // conversational far-field while still rejecting a quiet room; Gemini's own VAD
    // + the "ignore background noise" prompt handle any residual non-speech, and
    // billing is by session time (not audio volume) so sending more is free.
    private val energyThreshold: Double = 350.0,
    private val preRollFrames: Int = 6,      // ~120 ms
) : VadGate {

    private val preRoll: ArrayDeque<ByteArray> = ArrayDeque()
    private var consecutivePositive = 0
    private var hangoverRemaining = 0
    private var framesInCurrentUtterance = 0
    private var gateOpen = false

    override fun process(frame: ByteArray): VadResult {
        val positive = rmsEnergy(frame) > energyThreshold

        if (positive) {
            consecutivePositive++
            hangoverRemaining = hangoverFrames
        } else {
            consecutivePositive = 0
        }

        if (!gateOpen) {
            if (consecutivePositive >= openFrames) {
                gateOpen = true
                framesInCurrentUtterance = preRoll.size + 1
                val drained = preRoll.toList()
                preRoll.clear()
                return VadResult.Opening(preRoll = drained, current = frame)
            }
            // Only add to preRoll if gate didn't open (must come AFTER the gate-open check)
            if (preRoll.size >= preRollFrames) preRoll.removeFirst()
            preRoll.addLast(frame)
            return VadResult.Silence
        }

        framesInCurrentUtterance++

        if (!positive) {
            hangoverRemaining--
            if (hangoverRemaining <= 0 && framesInCurrentUtterance >= minUtteranceFrames) {
                gateOpen = false
                framesInCurrentUtterance = 0
                return VadResult.Closing
            }
        }
        return VadResult.Speaking(frame)
    }

    override fun reset() {
        preRoll.clear()
        consecutivePositive = 0
        hangoverRemaining = 0
        framesInCurrentUtterance = 0
        gateOpen = false
    }

    private fun rmsEnergy(frame: ByteArray): Double {
        if (frame.size < 2) return 0.0
        var sumSquares = 0.0
        var i = 0
        while (i < frame.size - 1) {
            val lo = frame[i].toInt() and 0xff
            val hi = frame[i + 1].toInt()
            val sample = (hi shl 8) or lo
            val signed = if (sample and 0x8000 != 0) sample - 0x10000 else sample
            sumSquares += (signed * signed).toDouble()
            i += 2
        }
        val samples = frame.size / 2
        return kotlin.math.sqrt(sumSquares / samples)
    }
}
