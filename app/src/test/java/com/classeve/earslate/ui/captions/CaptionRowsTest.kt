package com.classeve.earslate.ui.captions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The captions panel once scrolled to the wrong row: it rendered one item per
 * settled line plus one for the line still being spoken, and then scrolled to
 * the last SETTLED line. While anyone was mid-sentence the newest translated
 * text sat one row below the fold. For a user who cannot hear the speaker,
 * that is the product not working.
 *
 * These pin the arithmetic. Whether the list then goes there, on a real
 * screen, is `CaptionsOnDeviceTest`'s to prove.
 */
class CaptionRowsTest {

    private fun theirs(id: Long, text: String, live: Boolean = false) = Caption(id, CaptionSide.THEIRS, text, live)
    private fun mine(id: Long, text: String, live: Boolean = false) = Caption(id, CaptionSide.MINE, text, live)

    /** The regression this file exists for: the target was the last settled line. */
    @Test
    fun `autoscroll targets the caption still being spoken, not the last settled one`() {
        val captions = listOf(theirs(0, "one"), mine(1, "two"), theirs(2, "three in prog", live = true))

        assertEquals(2, captionScrollTarget(captions))
        assertTrue("the followed row must be the one still being spoken", captions[captionScrollTarget(captions)].live)
    }

    @Test
    fun `autoscroll targets the last settled caption when nothing is being spoken`() {
        assertEquals(2, captionScrollTarget(listOf(theirs(0, "one"), theirs(1, "two"), mine(2, "three"))))
    }

    // The very first thing a session produces is a caption with nothing behind it.
    @Test
    fun `the first caption of a session is followed while it is still being spoken`() {
        assertEquals(0, captionScrollTarget(listOf(theirs(0, "hel", live = true))))
    }

    @Test
    fun `an empty conversation has nothing to scroll to`() {
        assertEquals(-1, captionScrollTarget(emptyList()))
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

    // What "copy all" puts on the clipboard.
    @Test
    fun `the whole conversation is copied in order, each line with who said it`() {
        val captions = listOf(
            theirs(0, "ਸਤ ਸ੍ਰੀ ਅਕਾਲ, ਸਟੇਸ਼ਨ ਕਿੱਥੇ ਹੈ?"),
            mine(1, "It is ten minutes from here. "),
            theirs(2, "ਧੰਨ", live = true),
        )

        assertEquals(
            "Them: ਸਤ ਸ੍ਰੀ ਅਕਾਲ, ਸਟੇਸ਼ਨ ਕਿੱਥੇ ਹੈ?\nMe: It is ten minutes from here.\nThem: ਧੰਨ",
            conversationText(captions),
        )
    }

    @Test
    fun `an empty conversation copies as nothing`() {
        assertEquals("", conversationText(emptyList()))
    }
}
