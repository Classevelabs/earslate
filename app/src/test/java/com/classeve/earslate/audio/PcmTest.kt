package com.classeve.earslate.audio

import com.classeve.earslate.testing.TestAudio
import com.classeve.earslate.testing.TestAudio.samples
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmTest {

    @Test
    fun `exactly zero PCM is silent`() {
        assertTrue(Pcm.isSilent(samples(0, 0, 0, 0, 0, 0, 0, 0)))
    }

    // Reading the high byte as signed once made every negative sample count as loud.
    @Test
    fun `the model's own near-silence is silent in both signs`() {
        assertTrue(Pcm.isSilent(samples(-1, -1, -1, -1, -1, -1)))
        assertTrue(Pcm.isSilent(samples(1, -1, 1, -1, 1, -1)))
        assertTrue(Pcm.isSilent(samples(0, 0, -2, 0, 0, 0)))
    }

    @Test
    fun `negative and positive samples are judged by the same threshold`() {
        assertTrue(Pcm.isSilent(samples(47, 47, 47)))
        assertTrue(Pcm.isSilent(samples(-47, -47, -47)))
        assertFalse(Pcm.isSilent(samples(48, 0, 0)))
        assertFalse(Pcm.isSilent(samples(-48, 0, 0)))
    }

    @Test
    fun `real speech is audible`() {
        assertFalse(Pcm.isSilent(samples(0, 12_000, -18_000, 400)))
        assertFalse(Pcm.isSilent(samples(Short.MIN_VALUE.toInt())))
        assertFalse(Pcm.isSilent(samples(Short.MAX_VALUE.toInt())))
    }

    @Test
    fun `an empty or truncated block is silent rather than a crash`() {
        assertTrue(Pcm.isSilent(ByteArray(0)))
        assertTrue(Pcm.isSilent(byteArrayOf(0, 0, 0)))
    }

    @Test
    fun `level is measured in decibels below full scale`() {
        assertEquals(Pcm.FLOOR_DB, Pcm.levelDb(TestAudio.silence(20)), 0.0)
        // A full-scale sine sits 3 dB under full scale.
        assertEquals(-3.0, Pcm.levelDb(TestAudio.tone(100, peak = 32_767)), 0.2)
        // Ten times quieter is 20 dB lower.
        assertEquals(-23.0, Pcm.levelDb(TestAudio.tone(100, peak = 3_277)), 0.2)
    }
}
