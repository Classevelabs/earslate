package com.classeve.earslate.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        b.enqueue(second) // now at 120 bytes, started
        assertArrayEquals(first, b.drain())
        assertArrayEquals(second, b.drain())
    }

    @Test
    fun `underrun resets and requires refill`() {
        val b = JitterBuffer(startupBytes = 20)
        b.enqueue(ByteArray(20) { 1 })
        assertEquals(20, b.pendingBytes)

        // drain all
        b.drain()
        // queue is empty → next drain returns null (underrun resets draining)
        assertNull(b.drain())

        // small enqueue doesn't immediately start because we underran
        b.enqueue(ByteArray(10) { 2 })
        assertNull("not yet at startup after reset", b.drain())

        b.enqueue(ByteArray(10) { 3 })
        assertArrayEquals(ByteArray(10) { 2 }, b.drain())
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
    fun `empty enqueue is a no-op`() {
        val b = JitterBuffer(startupBytes = 10)
        b.enqueue(ByteArray(0))
        assertEquals(0, b.pendingBytes)
    }
}
