package com.classeve.earslate.session

import com.classeve.earslate.session.FloorControl.Floor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Turn-taking over a loudspeaker the microphone can hear, on a 50 ms tick. */
class FloorControlTest {

    private val floor = FloorControl()
    private var now = 10_000L
    private var lastSpeech = 0L

    /**
     * Advance [ms], with the room and the playback queue as described. While
     * someone talks, more of the translation keeps arriving.
     */
    private fun pass(
        ms: Int,
        talking: Boolean = false,
        waitingMs: Int = 0,
        audible: Boolean = false,
        arriving: Boolean = talking,
    ) {
        repeat(ms / 50) {
            now += 50
            if (talking) lastSpeech = now
            floor.update(now, lastSpeech, waitingMs, audible, arriving)
        }
    }

    @Test
    fun `nothing to say means the microphone stays open`() {
        pass(5_000, talking = true)
        assertEquals(Floor.LISTENING, floor.floor)
        assertFalse(floor.micClosed)
    }

    // The gate this replaces closed the microphone here, and lost the rest of the sentence.
    @Test
    fun `a translation that arrives while the person is still talking waits`() {
        pass(3_000, talking = true)
        pass(6_000, talking = true, waitingMs = 4_000)

        assertEquals(Floor.LISTENING, floor.floor)
        assertFalse("every word of the sentence is still being heard", floor.micClosed)
        assertFalse(floor.playing)
    }

    @Test
    fun `it is spoken once the person has paused`() {
        pass(4_000, talking = true, waitingMs = 3_000)
        pass(FloorControl.QUIET_MS.toInt() - 50, waitingMs = 3_000)
        assertEquals("a breath is not a pause", Floor.LISTENING, floor.floor)
        pass(100, waitingMs = 3_000)

        assertEquals(Floor.SPEAKING, floor.floor)
        assertTrue(floor.playing)
        assertTrue("the microphone must not hear the phone", floor.micClosed)
    }

    @Test
    fun `the microphone stays closed until the phone has finished and the room has settled`() {
        pass(1_000, waitingMs = 2_000)
        assertEquals(Floor.SPEAKING, floor.floor)
        pass(2_000, waitingMs = 500, audible = true)
        pass(300, audible = true)
        assertTrue("still coming out of the loudspeaker", floor.micClosed)

        // Counted from the first tick that found nothing left to play.
        pass(FloorControl.SPEECH_END_MS.toInt() + 50)
        assertEquals(Floor.SETTLING, floor.floor)
        assertTrue(floor.micClosed)
        pass(FloorControl.SETTLE_MS.toInt())
        assertEquals(Floor.LISTENING, floor.floor)
        assertFalse(floor.micClosed)
    }

    @Test
    fun `a pause between two sentences of one translation does not end the turn`() {
        pass(1_000, waitingMs = 2_000)
        pass(1_000, audible = true)
        pass(300)
        assertEquals("the second sentence is about to arrive", Floor.SPEAKING, floor.floor)
        pass(1_000, waitingMs = 1_000, audible = true)
        assertEquals(Floor.SPEAKING, floor.floor)
    }

    // Music, a television, a crowd: the microphone never hears a pause, and
    // every translation used to wait the full twenty seconds.
    @Test
    fun `in a room that never sounds quiet, the translation is spoken once no more of it is arriving`() {
        pass(5_000, talking = true, waitingMs = 4_000)
        // The person has stopped; the room has not. The translation finishes arriving.
        pass(FloorControl.NOTHING_MORE_MS.toInt() - 100, talking = true, waitingMs = 4_000, arriving = false)
        assertEquals("they may only be drawing breath", Floor.LISTENING, floor.floor)
        pass(200, talking = true, waitingMs = 4_000, arriving = false)

        assertEquals(Floor.SPEAKING, floor.floor)
    }

    @Test
    fun `while more of the translation keeps arriving, a noisy room goes on waiting`() {
        pass(5_000, talking = true, waitingMs = 4_000)
        pass(3_000, talking = true, waitingMs = 4_000, arriving = false)
        // They carry on, and so does the translation.
        pass(6_000, talking = true, waitingMs = 6_000, arriving = true)

        assertEquals(Floor.LISTENING, floor.floor)
    }

    // A café is never quiet. The translation must still come out.
    @Test
    fun `a room that never goes quiet cannot hold a translation for ever`() {
        pass(FloorControl.MAX_HOLD_MS.toInt() - 100, talking = true, waitingMs = 5_000)
        assertEquals(Floor.LISTENING, floor.floor)
        pass(200, talking = true, waitingMs = 5_000)
        assertEquals(Floor.SPEAKING, floor.floor)
    }

    @Test
    fun `the next translation waits its turn again after the first`() {
        pass(1_000, waitingMs = 1_000)
        pass(1_500)
        assertEquals(Floor.LISTENING, floor.floor)

        pass(2_000, talking = true, waitingMs = 2_000)
        assertEquals(Floor.LISTENING, floor.floor)
        pass(800, waitingMs = 2_000)
        assertEquals(Floor.SPEAKING, floor.floor)
    }

    @Test
    fun `reset returns the floor to the listener`() {
        pass(1_000, waitingMs = 1_000)
        assertTrue(floor.micClosed)
        floor.reset()
        assertEquals(Floor.LISTENING, floor.floor)
        assertFalse(floor.micClosed)
    }
}
