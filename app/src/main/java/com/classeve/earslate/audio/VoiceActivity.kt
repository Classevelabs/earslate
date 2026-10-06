package com.classeve.earslate.audio

/**
 * Says whether a microphone frame holds speech, by how far it stands above the
 * room's own noise.
 *
 * The room's noise is taken to be the quietest moment of the last few seconds:
 * speech always has gaps in it, a room's hum does not. So the same rule holds
 * in a quiet kitchen and a busy café, and it re-learns within seconds when the
 * room changes.
 */
class VoiceActivity(private val sampleRateHz: Int) {

    private val quietest = ArrayDeque<Double>()

    fun isSpeech(frame: ByteArray): Boolean {
        val windowBytes = sampleRateHz * WINDOW_MS / 1000 * 2
        val windows = if (windowBytes > 0) frame.size / windowBytes else 0
        if (windows == 0) return false

        val levels = DoubleArray(windows) { Pcm.levelDb(frame, it * windowBytes, (it + 1) * windowBytes) }
        quietest.addLast(levels.min())
        while (quietest.size > MEMORY_MS / (windows * WINDOW_MS)) quietest.removeFirst()

        val threshold = maxOf(ABSOLUTE_MIN_DB, quietest.min() + MARGIN_DB)
        // One loud window is a click or a knock; speech fills several.
        return levels.count { it > threshold } >= MIN_LOUD_WINDOWS
    }

    companion object {
        private const val WINDOW_MS = 20
        private const val MEMORY_MS = 3_000
        private const val MIN_LOUD_WINDOWS = 2
        private const val MARGIN_DB = 10.0
        private const val ABSOLUTE_MIN_DB = -55.0
    }
}
