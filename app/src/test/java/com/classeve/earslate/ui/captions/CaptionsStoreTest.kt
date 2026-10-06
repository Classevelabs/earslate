package com.classeve.earslate.ui.captions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The conversation as the screen is given it: one caption for each thing said, on its speaker's side. */
class CaptionsStoreTest {

    private val inbound = 1
    private val outbound = 2

    private fun CaptionsStore.shown() = captions.value.map { Triple(it.side, it.text, it.live) }

    @Test
    fun `a caption is live while it is being spoken and settled when its speaker stops`() {
        val store = CaptionsStore()
        store.appendDelta(inbound, CaptionSide.THEIRS, "Good mor")
        assertEquals(listOf(Triple(CaptionSide.THEIRS, "Good mor", true)), store.shown())

        store.appendDelta(inbound, CaptionSide.THEIRS, "ning. ")
        store.commitLine(inbound)
        assertEquals(listOf(Triple(CaptionSide.THEIRS, "Good morning.", false)), store.shown())
        assertEquals(listOf("Good morning."), store.settled())
        assertTrue(store.live().isEmpty())
    }

    // The paragraph the captions used to be could not say who had spoken.
    @Test
    fun `what they said and what I said are told apart`() {
        val store = CaptionsStore()
        store.appendDelta(inbound, CaptionSide.THEIRS, "Where is the station?")
        store.commitLine(inbound)
        store.appendDelta(outbound, CaptionSide.MINE, "Está a diez minutos.")
        store.commitLine(outbound)

        assertEquals(listOf(CaptionSide.THEIRS, CaptionSide.MINE), store.captions.value.map { it.side })
    }

    // A caption that moved when it settled would jump under the reader's eye,
    // and under a finger about to copy it.
    @Test
    fun `a caption keeps the place it began in, whichever is finished first`() {
        val store = CaptionsStore()
        store.appendDelta(inbound, CaptionSide.THEIRS, "Where is")
        store.appendDelta(outbound, CaptionSide.MINE, "Está")
        val began = store.captions.value.map { it.id }

        store.commitLine(outbound)
        store.appendDelta(inbound, CaptionSide.THEIRS, " the station?")
        store.commitLine(inbound)

        assertEquals("the same two captions, in the same order", began, store.captions.value.map { it.id })
        assertEquals(listOf("Where is the station?", "Está"), store.settled())
    }

    // "Yes." is said many times in a conversation; the list needs to tell them apart.
    @Test
    fun `every caption has a name of its own, even when the words are the same`() {
        val store = CaptionsStore()
        repeat(3) {
            store.appendDelta(inbound, CaptionSide.THEIRS, "Yes.")
            store.commitLine(inbound)
        }
        val ids = store.captions.value.map { it.id }
        assertEquals(3, ids.toSet().size)
    }

    @Test
    fun `a caption thrown away leaves the settled ones where they were`() {
        val store = CaptionsStore()
        store.appendDelta(inbound, CaptionSide.THEIRS, "Hello.")
        store.commitLine(inbound)
        store.appendDelta(outbound, CaptionSide.MINE, "Hel")
        store.discardPending(outbound)

        assertEquals(listOf(Triple(CaptionSide.THEIRS, "Hello.", false)), store.shown())
        store.discardPending(outbound)
        store.commitLine(outbound)
        assertEquals(1, store.captions.value.size)
    }

    @Test
    fun `nothing but spaces is not a caption`() {
        val store = CaptionsStore()
        store.appendDelta(inbound, CaptionSide.THEIRS, "  ")
        assertTrue(store.captions.value.isEmpty())
        store.commitLine(inbound)
        assertTrue(store.captions.value.isEmpty())
    }

    @Test
    fun `after a caption settles, the same direction starts a new one`() {
        val store = CaptionsStore()
        store.appendDelta(inbound, CaptionSide.THEIRS, "One.")
        store.commitLine(inbound)
        store.appendDelta(inbound, CaptionSide.THEIRS, "Two")

        val (first, second) = store.captions.value
        assertNotEquals(first.id, second.id)
        assertEquals(listOf("One."), store.settled())
        assertEquals(listOf("Two"), store.live())
    }

    @Test
    fun `only the newest captions are kept`() {
        val store = CaptionsStore(maxCaptions = 3)
        for (n in 1..5) {
            store.appendDelta(inbound, CaptionSide.THEIRS, "line $n")
            store.commitLine(inbound)
        }
        store.appendDelta(outbound, CaptionSide.MINE, "línea 6")

        assertEquals(listOf("line 4", "line 5"), store.settled())
        assertEquals(listOf("línea 6"), store.live())
    }

    @Test
    fun `clearing empties it, and what was being said with it`() {
        val store = CaptionsStore()
        store.appendDelta(inbound, CaptionSide.THEIRS, "One.")
        store.commitLine(inbound)
        store.appendDelta(outbound, CaptionSide.MINE, "Dos")
        store.clear()
        assertTrue(store.captions.value.isEmpty())

        store.commitLine(outbound)
        assertTrue("a caption begun before the clear does not come back", store.captions.value.isEmpty())
    }
}
