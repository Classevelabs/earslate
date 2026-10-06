package com.classeve.earslate.ui.captions

/**
 * The row autoscroll must reach: the newest caption the list renders, live or
 * settled. `-1` when there is nothing to scroll to.
 *
 * A one-line function with a test on it, on purpose. "Which index does
 * autoscroll aim at" is the exact thing that was once wrong, and naming it
 * means a future edit that quietly goes back to "the last settled caption"
 * fails a test instead of shipping.
 */
fun captionScrollTarget(captions: List<Caption>): Int = captions.lastIndex

/**
 * Whether the captions list keeps to its newest row. It does until a hand
 * moves it away, and again once it comes to rest at the end.
 *
 * Kept, not worked out afresh from where the list happens to be. Two captions
 * arriving before the list had finished moving to the first left it short of
 * the end, which read as somebody having scrolled up, and it never moved again.
 */
class CaptionFollow {

    var following = true
        private set

    /** A finger has taken hold of the list: whoever it is, is reading. */
    fun takenHold() {
        following = false
    }

    /** The list has stopped moving. [atEnd] when its last row is in view. */
    fun cameToRest(atEnd: Boolean) {
        // Stopping short on its own is the list being overtaken, not a reader.
        if (atEnd) following = true
    }
}

/**
 * How far into the followed row the list must be for the row's end, where its
 * newest words are, to show. Nothing for a row that fits the panel.
 *
 * @param rowHeight the row as it was last laid out, or null when it has not been.
 */
fun captionEndOffset(rowHeight: Int?, panelHeight: Int): Int {
    // Never more than the row holds: the list adds this to a position, and a
    // number chosen only for being large wraps round and aims at the top.
    return ((rowHeight ?: 0) - panelHeight).coerceAtLeast(0)
}

/** Who said a caption, for a reader who cannot see which side of the screen it is on. */
fun CaptionSide.speaker(): String = when (this) {
    CaptionSide.THEIRS -> "Them"
    CaptionSide.MINE -> "Me"
}

/**
 * The whole conversation as text to paste somewhere else: one line for each
 * thing said, with who said it, in the order it was said.
 */
fun conversationText(captions: List<Caption>): String =
    captions.joinToString("\n") { "${it.side.speaker()}: ${it.text.trim()}" }
