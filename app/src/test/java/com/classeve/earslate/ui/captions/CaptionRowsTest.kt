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
 * These pin the arithmetic. They do **not** prove that Compose scrolls: this
 * module has no Compose test infrastructure, so the plumbing between these
 * functions and `LazyListState` is verified by reading, not by running. What
 * they do prove is that the index the view aims at is the last row the view
 * actually emits — which is the part that was wrong.
 */
class CaptionRowsTest {

    @Test
    fun `the partial line is a row of its own`() {
        val rows = captionRows(listOf("Good morning.", "How are you?"), "I am fi")

        assertEquals(3, rows.size)
        assertEquals("I am fi", rows.last().text)
        assertTrue("the trailing row is the live one", rows.last().live)
        assertFalse(rows[0].live)
        assertFalse(rows[1].live)
    }

    @Test
    fun `nothing pending means no live row`() {
        val rows = captionRows(listOf("Good morning."), "")

        assertEquals(1, rows.size)
        assertFalse(rows.single().live)
    }

    /**
     * The regression this file exists for. Before the fix the target was the
     * last COMMITTED line, so this returned 1 while the list rendered 3 rows.
     */
    @Test
    fun `autoscroll targets the live row, not the last committed line`() {
        val rows = captionRows(listOf("one", "two"), "three in prog")

        assertEquals(2, captionScrollTarget(rows))
        assertTrue(
            "the followed row must be the one still being spoken",
            rows[captionScrollTarget(rows)].live,
        )
    }

    @Test
    fun `autoscroll targets the last committed line when nothing is pending`() {
        val rows = captionRows(listOf("one", "two", "three"), "")

        assertEquals(2, captionScrollTarget(rows))
    }

    /**
     * The very first thing a session produces is a partial line with nothing
     * committed behind it. It has to be followed, or the app's first visible
     * output is the row it never scrolls to.
     */
    @Test
    fun `a partial line with no committed lines is still followed`() {
        val rows = captionRows(emptyList(), "hel")

        assertEquals(1, rows.size)
        assertEquals(0, captionScrollTarget(rows))
        assertTrue(shouldFollowCaptions(lastVisibleIndex = -1, targetIndex = 0))
    }

    /**
     * CaptionsStore keeps `takeLast(48)`. At the cap the row COUNT stops
     * changing, which is what made a size-keyed scroll effect go permanently
     * quiet — so the full window plus a partial is the case worth pinning.
     */
    @Test
    fun `a full rolling window plus a partial is forty-nine rows`() {
        val committed = (1..48).map { "line $it" }

        val rows = captionRows(committed, "line 49 in prog")

        assertEquals(49, rows.size)
        assertEquals(48, captionScrollTarget(rows))
    }

    @Test
    fun `an empty transcript has nothing to scroll to`() {
        val rows = captionRows(emptyList(), "")

        assertEquals(0, rows.size)
        assertEquals(-1, captionScrollTarget(rows))
        assertFalse(shouldFollowCaptions(lastVisibleIndex = -1, targetIndex = -1))
    }

    @Test
    fun `a reader who has scrolled up is not dragged back down`() {
        // 40 rows, the reader is looking at row 12. Captions keep arriving.
        assertFalse(shouldFollowCaptions(lastVisibleIndex = 12, targetIndex = 39))
    }

    @Test
    fun `a view already at the end keeps following`() {
        // The new row has just been added, so the last row LAID OUT is still
        // the previous one: one index behind the target.
        assertTrue(shouldFollowCaptions(lastVisibleIndex = 38, targetIndex = 39))
        // And when only the live row's text grew, the count did not move.
        assertTrue(shouldFollowCaptions(lastVisibleIndex = 39, targetIndex = 39))
    }

    @Test
    fun `a list that has not been laid out yet follows`() {
        // First composition, or coming back to the screen with a transcript
        // already in the store: without this the panel opens at the top.
        assertTrue(shouldFollowCaptions(lastVisibleIndex = -1, targetIndex = 30))
    }
}
