package com.classeve.earslate.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The silence gate on the playback path.
 *
 * Both translate legs hear the same microphone, and the leg that must stay
 * quiet answers with filler silence rather than nothing at all. Dropping those
 * frames is what stops the silent leg from claiming the shared playback
 * stream, closing the half-duplex mic gate and flipping the pill to PLAYING
 * for audio nobody can hear. [SessionCoordinator.isSilent] is that gate.
 *
 * The failure this pins: the frame decoder read the high byte as a SIGNED
 * `Byte`, so `hi shl 8` was already sign-extended, and the manual
 * "if the sign bit is set, subtract 0x10000" correction then fired a second
 * time. Byte pair `FE FF` — the sample -2 — decoded as -65538 instead of -2,
 * so |s| = 65538 cleared a threshold of 48 and the frame was declared audible.
 * Every NEGATIVE sample did this, however small, which meant the gate only
 * ever answered "silent" for a frame whose samples were all non-negative.
 *
 * That defeated the guard in precisely the case it was written for: the
 * threshold's own comment records that the model's silence arrives with
 * peak ≈ 1, i.e. samples of ±1 — and a single -1 sample was enough to call
 * the frame audible. Only exactly-zero PCM still passed.
 */
class SilenceDetectionTest {

    /** Little-endian 16-bit PCM, the wire format of every provider frame. */
    private fun pcm(vararg samples: Int): ByteArray {
        val out = ByteArray(samples.size * 2)
        samples.forEachIndexed { index, sample ->
            out[index * 2] = (sample and 0xff).toByte()
            out[index * 2 + 1] = ((sample shr 8) and 0xff).toByte()
        }
        return out
    }

    @Test
    fun `exactly zero PCM is silent`() {
        assertTrue(SessionCoordinator.isSilent(pcm(0, 0, 0, 0, 0, 0, 0, 0)))
    }

    @Test
    fun `the model's own near-silence — peak one — is silent in both signs`() {
        // The threshold comment says the model emits silence with peak ~= 1.
        // A frame of -1 samples is that frame, and it must be dropped.
        assertTrue(SessionCoordinator.isSilent(pcm(-1, -1, -1, -1, -1, -1)))
        assertTrue(SessionCoordinator.isSilent(pcm(1, -1, 1, -1, 1, -1)))
    }

    @Test
    fun `one small negative sample among zeros does not make a frame audible`() {
        assertTrue(SessionCoordinator.isSilent(pcm(0, 0, -2, 0, 0, 0)))
    }

    @Test
    fun `negative and positive samples are judged by the same threshold`() {
        // 47 is under the 48 threshold; -47 is the same distance from zero and
        // must be answered the same way. Before the fix the sign decided it.
        assertTrue(SessionCoordinator.isSilent(pcm(47, 47, 47)))
        assertTrue(SessionCoordinator.isSilent(pcm(-47, -47, -47)))
        assertFalse(SessionCoordinator.isSilent(pcm(48, 0, 0)))
        assertFalse(SessionCoordinator.isSilent(pcm(-48, 0, 0)))
    }

    @Test
    fun `real speech is audible`() {
        assertFalse(SessionCoordinator.isSilent(pcm(0, 12_000, -18_000, 400)))
        assertFalse(SessionCoordinator.isSilent(pcm(Short.MIN_VALUE.toInt())))
        assertFalse(SessionCoordinator.isSilent(pcm(Short.MAX_VALUE.toInt())))
    }

    @Test
    fun `an empty or truncated frame is silent rather than a crash`() {
        assertTrue(SessionCoordinator.isSilent(ByteArray(0)))
        // A frame that ends mid-sample: the trailing odd byte carries no
        // complete sample and must not be read past the end of the array.
        assertTrue(SessionCoordinator.isSilent(byteArrayOf(0, 0, 0)))
    }
}
