package com.classeve.earslate.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JitterBufferTest {

    /**
     * Arms the buffer and forces exactly one underrun, using two chunks.
     *
     * Two, not one, because a single chunk deliberately can never arm the buffer
     * any more — see `arming requires more than one provider chunk`. Tests that
     * need a raised target have to go through a real arm-and-starve cycle.
     */
    private fun JitterBuffer.armThenUnderrun(chunkBytes: Int) {
        enqueue(ByteArray(chunkBytes) { 1 })
        enqueue(ByteArray(chunkBytes) { 1 })
        assertNotNull("two chunks should arm the buffer", drain())
        assertNotNull(drain())
        assertNull("third drain starves, raising the target", drain())
    }

    @Test
    fun `drain returns null before startup threshold reached`() {
        val b = JitterBuffer(startupBytes = 100)
        b.enqueue(ByteArray(50) { 1 })
        assertNull("still below startup", b.drain())
    }

    @Test
    fun `drain returns data once startup reached`() {
        val b = JitterBuffer(startupBytes = 100)
        val first = ByteArray(60) { 1 }
        val second = ByteArray(60) { 2 }
        b.enqueue(first)
        b.enqueue(second)
        assertArrayEquals(first, b.drain())
        assertArrayEquals(second, b.drain())
    }

    /**
     * The behaviour this replaces: an underrun used to disarm the buffer and
     * force a full re-accumulation of the startup target before any audio
     * played again. One late packet therefore cost a whole buffer's worth of
     * silence, which is what made the stream stutter. Audio that has already
     * arrived must play immediately.
     */
    @Test
    fun `audio waiting after an underrun plays without re-buffering`() {
        val b = JitterBuffer(startupBytes = 40, maxTargetBytes = 200, growthStepBytes = 10)
        b.armThenUnderrun(chunkBytes = 40)

        // Enough arrives to satisfy the widened cushion — it must play now,
        // rather than waiting out another full re-accumulation.
        b.enqueue(ByteArray(40) { 2 })
        b.enqueue(ByteArray(40) { 2 })
        assertArrayEquals(ByteArray(40) { 2 }, b.drain())
    }

    /**
     * The floor that actually fixed the choppy playback.
     *
     * A cushion smaller than one provider chunk can never keep audio in reserve:
     * it plays the chunk it just got, finds the queue empty and starves, once per
     * chunk, forever. Measured on-device, Gemini sends 250 ms chunks while the
     * buffer's configured ceiling was 240 ms — so even fully adapted it underran
     * on every chunk. The buffer must therefore refuse to start on a single chunk
     * however small its nominal target is.
     */
    @Test
    fun `arming requires more than one provider chunk however low the target`() {
        // Nominal target of 10 bytes, but chunks are 100 bytes.
        val b = JitterBuffer(
            startupBytes = 10,
            minTargetBytes = 10,
            maxTargetBytes = 1_000,
            maxBufferedBytes = 10_000,
        )

        b.enqueue(ByteArray(100) { 1 })
        assertNull(
            "one chunk is not a cushion — starting here starves on the next drain",
            b.drain(),
        )

        b.enqueue(ByteArray(100) { 2 })
        assertNotNull("two chunks clears the one-chunk floor", b.drain())
        // ...and crucially there is still audio in hand rather than an underrun.
        assertNotNull("a chunk must remain in reserve", b.drain())
        assertEquals("no underrun should have occurred", 0, b.underrunCount)
    }

    /** The floor tracks the provider, so it must never exceed the latency ceiling. */
    @Test
    fun `the one-chunk floor is capped by the latency ceiling`() {
        val b = JitterBuffer(
            startupBytes = 10,
            minTargetBytes = 10,
            maxTargetBytes = 50,
            maxBufferedBytes = 10_000,
        )
        b.enqueue(ByteArray(400) { 1 })
        assertEquals(
            "floor must clamp to the ceiling, not run past it",
            50,
            b.targetLatencyBytes,
        )
        assertNotNull("a chunk far above the ceiling must still play", b.drain())
    }

    /**
     * A gap counts once, however many times the playback loop polls during it.
     *
     * This matters more than it looks. The playback loop polls every few
     * milliseconds, and a conversation is mostly silence — between utterances
     * the provider sends nothing and the buffer legitimately empties. If each
     * empty poll counted as an underrun, the adaptive target would ratchet
     * straight to its ceiling within a fraction of a second of normal use, and
     * every reply would arrive a quarter of a second late for no reason.
     */
    @Test
    fun `one gap counts as one underrun no matter how often it is polled`() {
        val b = JitterBuffer(startupBytes = 20, growthStepBytes = 10, maxTargetBytes = 200)
        b.enqueue(ByteArray(20) { 1 })
        b.enqueue(ByteArray(20) { 1 })
        assertNotNull(b.drain())
        assertNotNull(b.drain())
        assertEquals("a clean drain is not an underrun", 0, b.underrunCount)

        // The loop keeps polling through the silence.
        repeat(50) { assertNull(b.drain()) }
        assertEquals("the whole gap is one underrun", 1, b.underrunCount)

        // A second, separate gap counts once more.
        b.enqueue(ByteArray(200) { 2 })
        assertNotNull(b.drain())
        repeat(20) { assertNull(b.drain()) }
        assertEquals(2, b.underrunCount)
    }

    /**
     * A conversation is mostly silence, and the buffer legitimately empties at the
     * end of every utterance. Charging those as underruns made the target ratchet
     * to its ceiling across a normal conversation — measured on-device climbing to
     * 600 ms within minutes, none of it earned by an actual stutter, and
     * unrecoverable because giving latency back needs sustained audio that a quiet
     * conversation never supplies.
     */
    @Test
    fun `an expected end of turn is not charged as an underrun`() {
        val b = JitterBuffer(
            startupBytes = 20,
            minTargetBytes = 20,
            maxTargetBytes = 200,
            growthStepBytes = 10,
            maxBufferedBytes = 1_000,
        )
        b.enqueue(ByteArray(20) { 1 })
        b.enqueue(ByteArray(20) { 1 })
        assertNotNull(b.drain())
        assertNotNull(b.drain())

        b.markTurnEnd()
        repeat(20) { assertNull(b.drain()) }

        assertEquals("end of turn is not a fault", 0, b.underrunCount)
        assertEquals("and must not buy latency", 20, b.adaptedTargetBytes)

        // The next real gap, mid-stream, still counts.
        b.enqueue(ByteArray(20) { 2 })
        b.enqueue(ByteArray(20) { 2 })
        assertNotNull(b.drain())
        assertNotNull(b.drain())
        assertNull(b.drain())
        assertEquals("a genuine stutter still counts", 1, b.underrunCount)
        assertTrue("and still buys cushion", b.adaptedTargetBytes > 20)
    }

    /**
     * A network that stutters must be given a bigger cushion, otherwise it just
     * stutters again. The target grows one step per underrun.
     */
    @Test
    fun `target latency grows on underrun and is capped`() {
        val b = JitterBuffer(
            startupBytes = 20,
            minTargetBytes = 20,
            maxTargetBytes = 50,
            growthStepBytes = 10,
        )
        assertEquals(20, b.targetLatencyBytes)

        repeat(10) {
            b.enqueue(ByteArray(100) { 1 })
            while (b.drain() != null) Unit // drain to empty, forcing an underrun
        }
        assertEquals("target must not exceed the ceiling", 50, b.targetLatencyBytes)
        assertTrue("target must have grown from the floor", b.targetLatencyBytes > 20)
    }

    /**
     * Latency bought during a bad patch has to be given back, or one hiccup
     * early in a conversation degrades the rest of it.
     */
    @Test
    fun `target latency recovers downward after enough clean audio`() {
        val b = JitterBuffer(
            startupBytes = 10,
            minTargetBytes = 10,
            maxTargetBytes = 100,
            growthStepBytes = 10,
            // Explicit so this test measures recovery, not the backlog cap.
            maxBufferedBytes = 1_000,
            recoveryBytes = 30,
        )
        b.armThenUnderrun(chunkBytes = 10)
        val raised = b.adaptedTargetBytes
        assertTrue("underrun should raise the target", raised > 10)

        repeat(20) { b.enqueue(ByteArray(10) { 2 }) }
        repeat(20) { assertNotNull("stream should stay fed", b.drain()) }

        assertTrue(
            "target should recover downward after a clean run",
            b.adaptedTargetBytes < raised,
        )
    }

    /**
     * Recovery is measured in bytes of audio, not in drain calls, because a drain
     * call is one provider chunk of unknown duration. Counting calls meant a
     * stream sending large chunks gave latency back many times sooner than
     * intended and oscillated straight back into underrunning.
     */
    @Test
    fun `recovery is measured in audio, not in drain calls`() {
        fun buffer() = JitterBuffer(
            startupBytes = 10,
            minTargetBytes = 10,
            maxTargetBytes = 100,
            growthStepBytes = 10,
            maxBufferedBytes = 1_000,
            recoveryBytes = 100,
        )

        // Raise the target, then feed the buffer MANY small chunks: 30 drain
        // calls carrying only 30 bytes. Any call-counting threshold at or below
        // 30 would have handed the latency back here; a byte threshold of 100
        // must not.
        val stingy = buffer()
        stingy.armThenUnderrun(chunkBytes = 10)
        val raised = stingy.adaptedTargetBytes
        assertTrue("underrun should raise the target", raised > 10)

        repeat(30) { stingy.enqueue(ByteArray(1) { 2 }) }
        repeat(30) { assertNotNull("stream should stay fed", stingy.drain()) }
        assertEquals(
            "30 drain calls carrying 30 bytes must not reach a 100-byte threshold",
            raised,
            stingy.adaptedTargetBytes,
        )

        // A third as many calls, but real audio behind them — that does recover.
        val generous = buffer()
        generous.armThenUnderrun(chunkBytes = 10)
        repeat(10) { generous.enqueue(ByteArray(20) { 2 }) }
        repeat(10) { assertNotNull("stream should stay fed", generous.drain()) }
        assertTrue(
            "enough drained audio must buy back latency",
            generous.adaptedTargetBytes < raised,
        )
    }

    /**
     * In a live conversation, audio that is a second late is worse than no
     * audio — the speaker has already moved on. A burst must not build an
     * unbounded backlog, and the oldest audio is what gets dropped.
     */
    @Test
    fun `backlog is capped and drops oldest audio first`() {
        val b = JitterBuffer(startupBytes = 10, maxBufferedBytes = 100)
        repeat(30) { i -> b.enqueue(ByteArray(10) { i.toByte() }) }

        assertTrue("backlog must stay bounded", b.pendingBytes <= 100)
        assertTrue("drops must be recorded", b.droppedChunks > 0)

        // What survives is the newest audio, so the first chunk out is not #0.
        val first = b.drain()
        assertNotNull(first)
        assertTrue("oldest chunk should have been dropped", first!![0].toInt() != 0)
    }

    /**
     * The playback engine can rebuild its track at a new sample rate mid-stream.
     * Thresholds are byte counts, so they must be re-expressed or every one of
     * them silently comes to mean a different number of milliseconds.
     */
    @Test
    fun `retarget rescales thresholds and preserves adaptation as a ratio`() {
        val b = JitterBuffer(
            startupBytes = 10,
            minTargetBytes = 10,
            maxTargetBytes = 100,
            growthStepBytes = 10,
        )
        // Raise the target to 2x the floor via an underrun.
        b.armThenUnderrun(chunkBytes = 10)
        assertEquals(20, b.adaptedTargetBytes)

        // Same durations at double the rate = double the bytes.
        b.retarget(startupBytes = 20, minBytes = 20, maxBytes = 200, stepBytes = 20)
        assertEquals("adaptation preserved as a duration, not a byte count", 40, b.adaptedTargetBytes)
    }

    @Test
    fun `clear empties pending state`() {
        val b = JitterBuffer(startupBytes = 10)
        b.enqueue(ByteArray(15) { 1 })
        b.clear()
        assertEquals(0, b.pendingBytes)
        assertNull(b.drain())
    }

    @Test
    fun `reset clears adaptation and counters`() {
        val b = JitterBuffer(startupBytes = 10, minTargetBytes = 10, growthStepBytes = 10)
        b.armThenUnderrun(chunkBytes = 10)
        assertTrue(b.underrunCount > 0)

        b.reset(startupBytes = 10)
        assertEquals(0, b.underrunCount)
        assertEquals(0, b.droppedChunks)
        assertEquals(10, b.targetLatencyBytes)
        assertEquals(0, b.pendingBytes)
    }

    @Test
    fun `empty enqueue is a no-op`() {
        val b = JitterBuffer(startupBytes = 10)
        b.enqueue(ByteArray(0))
        assertEquals(0, b.pendingBytes)
    }

    // ── Mid-speech starvation: the 500 ms freeze ───────────────────────

    /**
     * Real on-device numbers: Gemini sends 12000-byte chunks at 24 kHz mono
     * PCM16, which is 250 ms of audio each, roughly every 248 ms.
     */
    private fun geminiLikeBuffer(): JitterBuffer {
        fun ms(n: Int) = (24_000 * n / 1000) * 2
        return JitterBuffer(
            startupBytes = ms(180),
            maxTargetBytes = ms(600),
            growthStepBytes = ms(60),
            maxBufferedBytes = ms(1_200),
            recoveryBytes = ms(12_000),
        )
    }

    private val geminiChunk get() = ByteArray(12_000) { 7 }

    @Test
    fun `one late chunk mid-speech does not halt playback until two more arrive`() {
        // The failure this pins: a single late packet on mobile data used to
        // disarm the buffer. Re-arming needs armThresholdBytes, which is
        // 1.25x the largest chunk the provider has sent = 15000 bytes, and a
        // chunk is 12000 — so playback stayed silent until a SECOND chunk
        // landed. At a 248 ms cadence that is roughly half a second of dead
        // air in the middle of a sentence, for one late packet.
        //
        // The class KDoc already promised this could not happen ("Never fully
        // stall... the moment a packet lands it plays"). onUnderrun did it
        // anyway.
        val b = geminiLikeBuffer()
        b.enqueue(geminiChunk)
        b.enqueue(geminiChunk)
        assertNotNull("two chunks arm the buffer", b.drain())
        assertNotNull(b.drain())

        // The network hiccups: nothing to play this tick.
        assertNull("starved tick returns nothing", b.drain())

        // One chunk arrives. It must play immediately.
        b.enqueue(geminiChunk)
        assertNotNull(
            "a chunk in hand must play at once, not wait for a second chunk",
            b.drain(),
        )
    }

    @Test
    fun `sustained starvation is charged once, not once per polling tick`() {
        // The other half of the same fix. Staying armed means drain() keeps
        // reaching onUnderrun while the queue is empty, and the playback loop
        // polls every 5 ms. Charging an underrun per tick would add a growth
        // step per tick and peg the target at its ceiling within a few tens of
        // milliseconds — turning a smoothness fix into a latency bug.
        val b = geminiLikeBuffer()
        b.enqueue(geminiChunk)
        b.enqueue(geminiChunk)
        b.drain()
        b.drain()

        repeat(200) { assertNull(b.drain()) }

        assertEquals(
            "one continuous gap is one underrun, however often it is polled",
            1,
            b.underrunCount,
        )
        val afterOneGap = b.adaptedTargetBytes
        b.enqueue(geminiChunk)
        assertNotNull(b.drain())
        repeat(200) { assertNull(b.drain()) }
        assertEquals("a second distinct gap is a second underrun", 2, b.underrunCount)
        assertTrue(
            "each gap buys exactly one growth step",
            b.adaptedTargetBytes > afterOneGap,
        )
    }

    @Test
    fun `end of turn still disarms so the next utterance re-arms with the cushion`() {
        // Staying armed is for mid-speech only. When the provider says the turn
        // is over, the quiet is expected: disarm, charge nothing, and make the
        // next utterance wait for the full (possibly grown) cushion. That is
        // the one place added latency is free, because nobody is speaking.
        val b = geminiLikeBuffer()
        b.enqueue(geminiChunk)
        b.enqueue(geminiChunk)
        b.drain()
        b.drain()
        b.markTurnEnd()
        assertNull(b.drain())
        assertEquals("end of turn is not a fault", 0, b.underrunCount)

        // Disarmed: one chunk is below the 15000-byte arm threshold.
        b.enqueue(geminiChunk)
        assertNull("next utterance re-arms properly", b.drain())
        b.enqueue(geminiChunk)
        assertNotNull(b.drain())
    }
}
