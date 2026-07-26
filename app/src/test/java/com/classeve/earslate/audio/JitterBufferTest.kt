package com.classeve.earslate.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JitterBufferTest {

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
        val b = JitterBuffer(startupBytes = 20, maxTargetBytes = 200, growthStepBytes = 10)
        b.enqueue(ByteArray(20) { 1 })
        assertNotNull(b.drain())
        assertNull("underrun: nothing queued", b.drain())

        // Enough arrives to satisfy the widened target — it must play now.
        b.enqueue(ByteArray(40) { 2 })
        assertArrayEquals(ByteArray(40) { 2 }, b.drain())
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
    fun `target latency recovers downward after sustained clean drains`() {
        val b = JitterBuffer(
            startupBytes = 10,
            minTargetBytes = 10,
            maxTargetBytes = 100,
            growthStepBytes = 10,
            recoveryRuns = 3,
        )
        // Force one underrun to raise the target.
        b.enqueue(ByteArray(10) { 1 })
        b.drain()
        b.drain()
        val raised = b.targetLatencyBytes
        assertTrue("underrun should raise the target", raised > 10)

        // A sustained clean run means many separate chunks arriving in time —
        // one big chunk is a single drain, not a healthy stream.
        repeat(20) { b.enqueue(ByteArray(10) { 2 }) }
        repeat(20) { assertNotNull("stream should stay fed", b.drain()) }

        assertTrue(
            "target should recover downward after a clean run",
            b.targetLatencyBytes < raised,
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
        b.enqueue(ByteArray(10) { 1 })
        b.drain()
        b.drain()
        assertEquals(20, b.targetLatencyBytes)

        // Same durations at double the rate = double the bytes.
        b.retarget(startupBytes = 20, minBytes = 20, maxBytes = 200, stepBytes = 20)
        assertEquals("adaptation preserved as a duration, not a byte count", 40, b.targetLatencyBytes)
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
        b.enqueue(ByteArray(10) { 1 })
        b.drain()
        b.drain()
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
}
