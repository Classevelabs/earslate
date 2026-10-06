package com.classeve.earslate.audio

import kotlin.math.log10

/** Measurements on PCM16 little-endian mono audio. */
object Pcm {

    /** Two interleaved channels of PCM16 as one. */
    fun mono(stereo: ByteArray): ByteArray {
        val out = ByteArray(stereo.size / 4 * 2)
        for (i in 0 until out.size / 2) {
            val left = (stereo[i * 4 + 1].toInt() shl 8) or (stereo[i * 4].toInt() and 0xff)
            val right = (stereo[i * 4 + 3].toInt() shl 8) or (stereo[i * 4 + 2].toInt() and 0xff)
            val mixed = (left + right) / 2
            out[i * 2] = (mixed and 0xff).toByte()
            out[i * 2 + 1] = ((mixed shr 8) and 0xff).toByte()
        }
        return out
    }

    // The model's own silence is zero PCM with the odd stray bit; real speech
    // peaks in the thousands.
    private const val SILENCE_PEAK = 48

    private const val FULL_SCALE_SQUARED = 32768.0 * 32768.0

    private fun sample(pcm: ByteArray, at: Int): Int =
        (((pcm[at + 1].toInt() and 0xff) shl 8) or (pcm[at].toInt() and 0xff)).toShort().toInt()

    /** True when nothing in the block rises above the level of digital silence. */
    fun isSilent(pcm: ByteArray): Boolean {
        var i = 0
        while (i < pcm.size - 1) {
            val s = sample(pcm, i)
            if (s >= SILENCE_PEAK || s <= -SILENCE_PEAK) return false
            i += 2
        }
        return true
    }

    /** Mean power of `pcm[from, until)` in dB relative to full scale; never above 0. */
    fun levelDb(pcm: ByteArray, from: Int = 0, until: Int = pcm.size): Double {
        var sum = 0.0
        var n = 0
        var i = from
        while (i < until - 1) {
            val s = sample(pcm, i).toDouble()
            sum += s * s
            n++
            i += 2
        }
        if (n == 0 || sum == 0.0) return FLOOR_DB
        return maxOf(FLOOR_DB, 10 * log10(sum / n / FULL_SCALE_SQUARED))
    }

    const val FLOOR_DB = -96.0
}
