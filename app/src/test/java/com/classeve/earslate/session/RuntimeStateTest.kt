package com.classeve.earslate.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeStateTest {

    @Test
    fun `IDLE and STOPPING are not active`() {
        assertFalse(RuntimeState.IDLE.isActive)
        assertFalse(RuntimeState.STOPPING.isActive)
    }

    @Test
    fun `all other states are active`() {
        val notActive = setOf(RuntimeState.IDLE, RuntimeState.STOPPING)
        RuntimeState.entries.filterNot { it in notActive }.forEach {
            assertTrue("$it should be active", it.isActive)
        }
    }

    @Test
    fun `recovery states are flagged`() {
        assertTrue(RuntimeState.RECONNECTING.isRecovering)
        assertTrue(RuntimeState.RESUMING.isRecovering)
        assertTrue(RuntimeState.DEGRADED.isRecovering)
    }

    @Test
    fun `state store starts idle`() {
        val store = RuntimeStateStore()
        assertEquals(RuntimeState.IDLE, store.state.value)
    }

    @Test
    fun `state store accepts transitions`() {
        val store = RuntimeStateStore()
        store.set(RuntimeState.LISTENING)
        assertEquals(RuntimeState.LISTENING, store.state.value)
        store.set(RuntimeState.IDLE)
        assertEquals(RuntimeState.IDLE, store.state.value)
    }

}
