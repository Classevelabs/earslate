package com.classeve.earslate.ui.captions

/**
 * One row of the captions list.
 *
 * [live] marks a line that is still being spoken — a row whose TEXT grows
 * between frames while the row count stays exactly the same.
 */
data class CaptionRow(
    val text: String,
    val live: Boolean,
)

/**
 * Every row the captions list renders, in order.
 *
 * This exists so that the list and its autoscroll cannot disagree about how
 * many rows there are. They did, and it was the bug: the `LazyColumn` emitted
 * one item per committed line **plus** a trailing item for the partial line,
 * while the scroll effect targeted `lines.lastIndex`. So for as long as
 * somebody was mid-sentence — which is the entire time the app is useful — the
 * newest translated text sat one row below the fold and the view never scrolled
 * to it.
 *
 * Deriving both the rendering and the scroll target from this one list is what
 * removes the whole class rather than the reported instance: add a footer row
 * here tomorrow and the scroll target follows it for free.
 */
fun captionRows(lines: List<String>, pending: List<String>): List<CaptionRow> =
    buildList(lines.size + pending.size) {
        lines.mapTo(this) { CaptionRow(text = it, live = false) }
        pending.filter { it.isNotEmpty() }.mapTo(this) { CaptionRow(text = it, live = true) }
    }

/**
 * The row autoscroll must reach: the newest row the list actually renders,
 * live or committed. `-1` when there is nothing to scroll to.
 *
 * A one-line function with a test on it, on purpose. "Which index does
 * autoscroll aim at" is the exact thing that was wrong, and naming it means a
 * future edit that quietly goes back to "the last committed line" fails a test
 * instead of shipping.
 */
fun captionScrollTarget(rows: List<CaptionRow>): Int = rows.lastIndex

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
