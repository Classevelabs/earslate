package com.classeve.earslate.testing

import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** PCM16 little-endian mono test signals. */
object TestAudio {

    fun bytesFor(ms: Int, rateHz: Int): Int = rateHz * ms / 1000 * 2

    fun silence(ms: Int, rateHz: Int = 24_000): ByteArray = ByteArray(bytesFor(ms, rateHz))

    /** A steady tone peaking at [peak]: stands in for speech of that loudness. */
    fun tone(ms: Int, rateHz: Int = 24_000, peak: Int = 12_000, hz: Double = 220.0): ByteArray =
        toneOf(bytesFor(ms, rateHz), rateHz, peak, hz)

    fun toneOf(bytes: Int, rateHz: Int, peak: Int, hz: Double = 220.0): ByteArray {
        val out = ByteArray(bytes)
        for (i in 0 until bytes / 2) {
            // A quarter-period phase offset, so even a very short block reaches the peak.
            val s = (peak * sin(2 * PI * hz * i / rateHz + PI / 2)).toInt()
            out[i * 2] = (s and 0xff).toByte()
            out[i * 2 + 1] = ((s shr 8) and 0xff).toByte()
        }
        return out
    }

    /** The hiss of a room, peaking at [peak]. */
    fun noise(ms: Int, rateHz: Int, peak: Int, seed: Int = 1): ByteArray {
        val random = Random(seed)
        return samples(*IntArray(rateHz * ms / 1000) { random.nextInt(-peak, peak + 1) })
    }

    /**
     * Something shaped like talking: syllables of tone with the short gaps
     * real speech has between them, over a room hissing at [over].
     */
    fun speech(ms: Int, rateHz: Int, peak: Int = 8_000, over: Int = 60, seed: Int = 1): ByteArray {
        val random = Random(seed)
        val syllable = rateHz / 10
        val voiced = syllable * 7 / 10
        return samples(
            *IntArray(rateHz * ms / 1000) { i ->
                val room = random.nextInt(-over, over + 1)
                val voice = if (i % syllable < voiced) (peak * sin(2 * PI * 220.0 * i / rateHz)).toInt() else 0
                (room + voice).coerceIn(-32768, 32767)
            },
        )
    }

    fun samples(vararg values: Int): ByteArray {
        val out = ByteArray(values.size * 2)
        values.forEachIndexed { i, s ->
            out[i * 2] = (s and 0xff).toByte()
            out[i * 2 + 1] = ((s shr 8) and 0xff).toByte()
        }
        return out
    }
}
