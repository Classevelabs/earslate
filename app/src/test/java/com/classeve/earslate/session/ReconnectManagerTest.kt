package com.classeve.earslate.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectManagerTest {

    @Test
    fun `first attempt is immediate`() {
        val rm = ReconnectManager()
        assertEquals(0L, rm.nextDelayMs())
    }

    @Test
    fun `delay grows on subsequent attempts`() {
        val rm = ReconnectManager()
        val delays = (1..6).map { rm.nextDelayMs() }

        assertEquals(0L, delays[0])
        assertTrue("second attempt >= 500ms", delays[1] >= 500L)
        assertTrue("second attempt capped jitter", delays[1] <= 500L + 125L)
        assertTrue("third attempt >= 1000ms", delays[2] >= 1_000L)
        assertTrue("fourth attempt >= 2000ms", delays[3] >= 2_000L)
        assertTrue("fifth attempt caps at 5000ms base", delays[4] >= 5_000L)
        assertTrue("fifth attempt jitter bounded", delays[4] <= 5_500L)
        assertTrue("sixth attempt still capped at 5000ms base", delays[5] >= 5_000L)
        assertTrue("sixth attempt jitter bounded", delays[5] <= 5_500L)
    }

    @Test
    fun `a session that stayed connected long enough earns a fresh backoff`() {
        val rm = ReconnectManager()
        rm.nextDelayMs()
        rm.nextDelayMs()
        assertEquals(2, rm.attemptNumber)

        rm.noteSessionEnded(ReconnectManager.STABLE_AFTER_MS)
        assertEquals(0, rm.attemptNumber)
        assertEquals(0L, rm.nextDelayMs())
    }

    @Test
    fun `a fast accept-then-drop does not reset the backoff`() {
        val rm = ReconnectManager()
        rm.nextDelayMs()
        rm.noteSessionEnded(0L)                                   // never really up
        rm.noteSessionEnded(ReconnectManager.STABLE_AFTER_MS - 1) // up, but too briefly
        assertEquals(1, rm.attemptNumber)
    }

    @Test
    fun `repeated fast drops exhaust the budget instead of looping forever`() {
        // Models the reconnect loop against a provider that reaches READY then
        // drops immediately every cycle: end a session, and while the budget is
        // not spent, schedule the next attempt. The backoff must climb to the
        // budget and stop — the reset-on-READY bug pinned it at 0 forever.
        val rm = ReconnectManager()
        val budget = 4 // mirrors SessionCoordinator.MAX_RECONNECT_ATTEMPTS
        var sessions = 0
        while (rm.attemptNumber < budget) {
            rm.noteSessionEnded(0L)
            if (rm.attemptNumber < budget) rm.nextDelayMs()
            sessions++
            assertTrue("must terminate, not loop forever", sessions <= budget + 2)
        }
        assertEquals(budget, rm.attemptNumber)
    }

    @Test
    fun `reset returns to attempt zero`() {
        val rm = ReconnectManager()
        rm.nextDelayMs()
        rm.nextDelayMs()
        rm.nextDelayMs()
        assertEquals(3, rm.attemptNumber)

        rm.reset()
        assertEquals(0, rm.attemptNumber)
        assertEquals(0L, rm.nextDelayMs())
        assertEquals(1, rm.attemptNumber)
    }
}
