package com.classeve.earslate.audio

import com.classeve.earslate.testing.TestAudio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Frames are the 100 ms the microphone delivers at 16 kHz. */
class VoiceActivityTest {

    private val rate = 16_000

    private fun room(peak: Int, seed: Int = 1) = TestAudio.noise(100, rate, peak, seed)
    private fun speech(peak: Int = 8_000, over: Int = 60, seed: Int = 1) = TestAudio.speech(100, rate, peak, over, seed)

    @Test
    fun `a quiet room is not speech`() {
        val voice = VoiceActivity(rate)
        repeat(30) { assertFalse(voice.isSpeech(room(60, it))) }
    }

    @Test
    fun `speech in a quiet room is heard from its first frame`() {
        val voice = VoiceActivity(rate)
        repeat(10) { voice.isSpeech(room(60, it)) }
        assertTrue(voice.isSpeech(speech()))
    }

    // Someone may already be talking when the session starts.
    @Test
    fun `speech is heard even when it is the first thing the microphone hears`() {
        val voice = VoiceActivity(rate)
        assertTrue(voice.isSpeech(speech(seed = 1)))
        assertTrue(voice.isSpeech(speech(seed = 2)))
    }

    @Test
    fun `the pause after speech is heard as a pause`() {
        val voice = VoiceActivity(rate)
        repeat(30) { voice.isSpeech(speech(seed = it)) }
        assertFalse(voice.isSpeech(room(60, 99)))
    }

    // A fixed threshold calls a busy café speech for ever.
    @Test
    fun `a steady noisy room is learned, and speech still stands out of it`() {
        val voice = VoiceActivity(rate)
        var falseAlarms = 0
        repeat(100) { if (voice.isSpeech(room(1_200, it))) falseAlarms++ }
        assertEquals("steady noise mistaken for speech", 0, falseAlarms)
        assertTrue(voice.isSpeech(speech(peak = 12_000, over = 1_200, seed = 7)))
        assertFalse(voice.isSpeech(room(1_200, 500)))
    }

    @Test
    fun `a room that gets louder is re-learned within a few seconds`() {
        val voice = VoiceActivity(rate)
        repeat(20) { voice.isSpeech(room(60, it)) }
        // An air conditioner comes on.
        val heardAsSpeech = (0 until 60).count { voice.isSpeech(room(1_500, 100 + it)) }
        assertTrue("mistaken for speech in $heardAsSpeech of 60 frames", heardAsSpeech <= 30)
        assertFalse("and not at all once it has settled", voice.isSpeech(room(1_500, 999)))
    }

    @Test
    fun `a long sentence is speech from its first word to its last`() {
        val voice = VoiceActivity(rate)
        repeat(10) { voice.isSpeech(room(60, it)) }
        // Thirty seconds without a pause longer than the gaps between syllables.
        repeat(300) { assertTrue("frame $it", voice.isSpeech(speech(seed = it))) }
    }

    @Test
    fun `a single knock is not speech`() {
        val voice = VoiceActivity(rate)
        repeat(10) { voice.isSpeech(room(60, it)) }
        val knock = room(60, 3).also { frame ->
            val spike = TestAudio.tone(5, rate, 20_000)
            System.arraycopy(spike, 0, frame, 800, spike.size)
        }
        assertFalse(voice.isSpeech(knock))
    }

    @Test
    fun `digital silence is never speech`() {
        val voice = VoiceActivity(rate)
        repeat(10) { assertFalse(voice.isSpeech(TestAudio.silence(100, rate))) }
        assertTrue(voice.isSpeech(speech()))
    }

    @Test
    fun `works on the 200 ms frames of a 24 kHz microphone too`() {
        val voice = VoiceActivity(24_000)
        repeat(10) { assertFalse(voice.isSpeech(TestAudio.noise(200, 24_000, 60, it))) }
        assertTrue(voice.isSpeech(TestAudio.speech(200, 24_000, 8_000, 60, 1)))
        assertFalse(voice.isSpeech(TestAudio.noise(200, 24_000, 60, 77)))
    }
}
