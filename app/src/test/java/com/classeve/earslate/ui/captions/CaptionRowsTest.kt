package com.classeve.earslate.ui.captions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The captions panel scrolled to the wrong row.
 *
 * `CaptionsView` rendered one item per committed line **plus** a trailing item
 * for the partial line still being spoken, and then scrolled to
 * `lines.lastIndex` — the last COMMITTED line. So while anyone was mid-sentence
 * the newest translated text sat one row below the fold, and the panel never
 * reached it. For a user who cannot hear the speaker, that is the product not
 * working.
 *
 * These pin the arithmetic: the index the view aims at is the last row the
 * view actually emits, which is the part that was wrong. Whether the list then
 * goes there, on a real screen, is `CaptionsOnDeviceTest`'s to prove.
 */
class CaptionRowsTest {

    @Test
    fun `the partial line is a row of its own`() {
        val rows = captionRows(listOf("Good morning.", "How are you?"), listOf("I am fi"))

        assertEquals(3, rows.size)
        assertEquals("I am fi", rows.last().text)
        assertTrue("the trailing row is the live one", rows.last().live)
        assertFalse(rows[0].live)
        assertFalse(rows[1].live)
    }

    @Test
    fun `nothing pending means no live row`() {
        val rows = captionRows(listOf("Good morning."), emptyList())

        assertEquals(1, rows.size)
        assertFalse(rows.single().live)
    }

    /**
     * The regression this file exists for. Before the fix the target was the
     * last COMMITTED line, so this returned 1 while the list rendered 3 rows.
     */
    @Test
    fun `autoscroll targets the live row, not the last committed line`() {
        val rows = captionRows(listOf("one", "two"), listOf("three in prog"))

        assertEquals(2, captionScrollTarget(rows))
        assertTrue(
            "the followed row must be the one still being spoken",
            rows[captionScrollTarget(rows)].live,
        )
    }

    @Test
    fun `autoscroll targets the last committed line when nothing is pending`() {
        val rows = captionRows(listOf("one", "two", "three"), emptyList())

        assertEquals(2, captionScrollTarget(rows))
    }

    /**
     * The very first thing a session produces is a partial line with nothing
     * committed behind it. It has to be followed, or the app's first visible
     * output is the row it never scrolls to.
     */
    @Test
    fun `a partial line with no committed lines is still followed`() {
        val rows = captionRows(emptyList(), listOf("hel"))

        assertEquals(1, rows.size)
        assertEquals(0, captionScrollTarget(rows))
    }

    /**
     * CaptionsStore keeps `takeLast(48)`. At the cap the row COUNT stops
     * changing, which is what made a size-keyed scroll effect go permanently
     * quiet — so the full window plus a partial is the case worth pinning.
     */
    @Test
    fun `a full rolling window plus a partial is forty-nine rows`() {
        val committed = (1..48).map { "line $it" }

        val rows = captionRows(committed, listOf("line 49 in prog"))

        assertEquals(49, rows.size)
        assertEquals(48, captionScrollTarget(rows))
    }

    @Test
    fun `an empty transcript has nothing to scroll to`() {
        val rows = captionRows(emptyList(), emptyList())

        assertEquals(0, rows.size)
        assertEquals(-1, captionScrollTarget(rows))
    }

    @Test
    fun `a new list follows`() {
        assertTrue(CaptionFollow().following)
    }

    // Whether the list was following used to be read off where it had got to.
    // Two captions before it finished moving, and it never moved again.
    @Test
    fun `captions arriving faster than the list can scroll never stop it following`() {
        val follow = CaptionFollow()
        // It sets off, is overtaken by the next caption, and stops short of the end. Again and again.
        repeat(5) { follow.cameToRest(atEnd = false) }
        assertTrue(follow.following)
    }

    @Test
    fun `a reader who has moved the list is not dragged back to the end`() {
        val follow = CaptionFollow()
        follow.takenHold()
        assertFalse("not even while the finger is still down", follow.following)
        follow.cameToRest(atEnd = false)
        assertFalse(follow.following)
    }

    @Test
    fun `a reader who goes back to the end is followed again`() {
        val follow = CaptionFollow()
        follow.takenHold()
        follow.cameToRest(atEnd = false)
        follow.takenHold()
        follow.cameToRest(atEnd = true)
        assertTrue(follow.following)
    }

    // The list adds the offset to the row's position. Int.MAX_VALUE went in
    // here once, and the sum wrapped round to a place above the top.
    @Test
    fun `the end of a row is reached by an offset no larger than the row`() {
        assertEquals("a row that fits is shown whole", 0, captionEndOffset(rowHeight = 120, panelHeight = 880))
        assertEquals("a row not laid out yet", 0, captionEndOffset(rowHeight = null, panelHeight = 880))
        assertEquals("a line taller than the panel is entered by what does not fit", 320, captionEndOffset(rowHeight = 1_200, panelHeight = 880))
    }

    // Two people speaking close together each have a line in progress.
    @Test
    fun `each direction still speaking is a live row of its own, after the committed ones`() {
        val rows = captionRows(listOf("Good morning."), listOf("Where is the", "Dónde está"))
        assertEquals(
            listOf(
                CaptionRow("Good morning.", live = false),
                CaptionRow("Where is the", live = true),
                CaptionRow("Dónde está", live = true),
            ),
            rows,
        )
        assertEquals(2, captionScrollTarget(rows))
    }
}
