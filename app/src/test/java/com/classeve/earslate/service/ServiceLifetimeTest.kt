package com.classeve.earslate.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The orders in which a start, a stop and a session's end can reach the service. */
class ServiceLifetimeTest {

    private val lifetime = ServiceLifetime()

    @Test
    fun `a session that ends by itself ends the service`() {
        lifetime.received(1)
        lifetime.opening(1)
        assertFalse(lifetime.free)
        assertTrue(lifetime.ended(1))
        assertTrue(lifetime.free)
        assertEquals(1, lifetime.newest)
    }

    @Test
    fun `a stop is what ends the service, not the session it stopped`() {
        lifetime.received(1)
        lifetime.opening(1)
        lifetime.received(2)
        lifetime.stopped()
        assertFalse(lifetime.ended(1))
        assertTrue(lifetime.free)
        assertEquals(2, lifetime.newest)
    }

    @Test
    fun `a start made while the last session closes keeps the service`() {
        lifetime.received(1)
        lifetime.opening(1)
        lifetime.received(2)
        lifetime.stopped()
        lifetime.received(3)
        lifetime.opening(3)
        // The first session's end and the stop's both arrive now, and neither may end it.
        assertFalse(lifetime.ended(1))
        assertFalse(lifetime.free)
        assertTrue(lifetime.ended(3))
        assertEquals(3, lifetime.newest)
    }

    @Test
    fun `a second start for a session already running is the one its end answers`() {
        lifetime.received(1)
        lifetime.opening(1)
        lifetime.received(2)
        lifetime.opening(2)
        assertFalse(lifetime.ended(1))
        assertTrue(lifetime.ended(2))
    }

    @Test
    fun `commands delivered out of order never lower the newest`() {
        lifetime.received(4)
        lifetime.received(3)
        assertEquals(4, lifetime.newest)
    }
}
