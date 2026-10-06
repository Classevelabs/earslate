package com.classeve.earslate.audio

import com.classeve.earslate.testing.TestAudio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lanes of one session, played the way the audio threads play them: every
 * lane pulled 20 ms at a time, on one clock.
 */
class PlayoutDeckTest {

    private var now = 0L
    private val deck = PlayoutDeck(now = { now }, audibleForMs = 120)
    private val slots = ArrayList<PlayoutDeck.Slot>()
    private val heard = HashMap<Int, Int>()

    private fun arrive(lane: Int, ms: Int = 1_000, voiced: Boolean = true, rateHz: Int = 24_000) {
        repeat(ms / 250) {
            val pcm = if (voiced) TestAudio.tone(250, rateHz) else TestAudio.silence(250, rateHz)
            deck.write(lane, pcm, rateHz, voiced)?.let { slots += it }
        }
    }

    private fun play(ms: Int) {
        repeat(ms / 20) {
            for (slot in slots.toList()) {
                val frame = ByteArray(TestAudio.bytesFor(20, slot.sampleRateHz))
                if (slot.pull(frame)) heard[slot.id] = (heard[slot.id] ?: 0) + 20
                if (slot.finished) {
                    slots -= slot
                    deck.remove(slot)
                }
            }
            now += 20
        }
    }

    // The loudspeaker was already speaking when the second direction's first
    // audio arrived. Its lane began by waiting for a floor that had been given,
    // and the session went deaf.
    @Test
    fun `a lane that joins while the floor is open is heard`() {
        deck.setConsecutive(true)
        arrive(lane = 1)
        deck.release()
        play(200)

        arrive(lane = 2)
        play(3_000)

        assertEquals(1_000, heard[1])
        assertEquals(1_000, heard[2])
        assertEquals("nothing is left waiting", 0, deck.snapshot(running = true).waitingMs)
    }

    @Test
    fun `a lane that joins while the others are waiting waits with them`() {
        deck.setConsecutive(true)
        arrive(lane = 1)
        arrive(lane = 2)
        play(1_000)

        assertTrue(heard.isEmpty())
        assertEquals(2_000, deck.snapshot(running = true).waitingMs)

        deck.release()
        play(2_000)
        assertEquals(1_000, heard[1])
        assertEquals(1_000, heard[2])
    }

    @Test
    fun `going back to waiting holds a lane that joins afterwards`() {
        deck.setConsecutive(true)
        deck.release()
        deck.hold()
        arrive(lane = 1)
        play(1_000)

        assertNull(heard[1])
    }

    // A replaced session's last words can be waiting for the floor for longer
    // than any fixed allowance.
    @Test
    fun `a retired lane says everything it holds before it is finished`() {
        deck.setConsecutive(true)
        arrive(lane = 1, ms = 8_000)
        deck.retire(1)
        play(12_000)
        assertFalse("still waiting for the floor", slots.single().finished)

        deck.release()
        play(9_000)
        assertEquals(8_000, heard[1])
        assertTrue("and then it is gone", slots.isEmpty())
        assertEquals(0, deck.snapshot(running = true).lanes)
    }

    @Test
    fun `a retired lane with nothing left to say is finished at once`() {
        arrive(lane = 1, voiced = false)
        deck.retire(1)
        assertTrue(slots.single().finished)
    }

    @Test
    fun `a change of rate starts a new lane and lets the old one finish`() {
        arrive(lane = 1, ms = 500, rateHz = 24_000)
        arrive(lane = 1, ms = 500, rateHz = 16_000)

        assertEquals(2, slots.size)
        play(2_000)
        assertEquals("both were heard", 1_000, heard[1])
        assertEquals("only the new one is left", listOf(16_000), slots.map { it.sampleRateHz })
    }

    @Test
    fun `earbuds going in lets everything out, and what joins afterwards plays as it is fed`() {
        deck.setConsecutive(true)
        arrive(lane = 1)
        deck.setConsecutive(false)
        arrive(lane = 2)
        play(2_000)

        assertEquals(1_000, heard[1])
        assertEquals(1_000, heard[2])
    }

    @Test
    fun `only the lane that is asked for is made`() {
        assertNotNull(deck.write(1, TestAudio.tone(250, 24_000), 24_000, voiced = true))
        assertNull("a second block does not make a second lane", deck.write(1, TestAudio.tone(250, 24_000), 24_000, voiced = true))
    }

    @Test
    fun `what is waiting and what is audible are counted across every lane`() {
        deck.setConsecutive(true)
        arrive(lane = 1, ms = 500)
        arrive(lane = 2, ms = 750)
        assertEquals(1_250, deck.snapshot(running = true).waitingMs)
        assertFalse(deck.snapshot(running = true).audible)

        deck.release()
        play(100)
        assertTrue(deck.snapshot(running = true).audible)

        play(2_000)
        assertFalse("silent again once the speech has left the room", deck.snapshot(running = true).audible)
    }

    // A translation waiting for its turn, and after it on the same lane the
    // start of something found to be a repeat.
    @Test
    fun `silencing what is queued of an utterance leaves the one waiting ahead of it`() {
        deck.setConsecutive(true)
        fun utterance(ms: Int) = repeat(ms / 250) { block ->
            deck.write(1, TestAudio.tone(250), 24_000, voiced = true, begins = block == 0)?.let { slots += it }
        }
        utterance(1_000)
        utterance(500)
        deck.muteQueued(1)
        deck.release()
        play(3_000)

        assertEquals("the translation is heard whole, and the repeat not at all", 1_000, heard[1])
    }

    @Test
    fun `speech that turns out to be unwanted is silenced on its own lane only`() {
        arrive(lane = 1)
        arrive(lane = 2)
        deck.muteQueued(2)
        play(2_000)

        assertEquals(1_000, heard[1])
        assertNull(heard[2])
    }
}
