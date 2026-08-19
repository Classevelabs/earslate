package com.classeve.earslate.ui.captions

/**
 * One row of the captions list.
 *
 * [live] marks the partial line that is still being spoken — the row whose TEXT
 * grows between frames while the row count stays exactly the same.
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
fun captionRows(lines: List<String>, pending: String): List<CaptionRow> =
    buildList(lines.size + 1) {
        lines.mapTo(this) { CaptionRow(text = it, live = false) }
        if (pending.isNotEmpty()) add(CaptionRow(text = pending, live = true))
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
 * Whether the transcript should scroll to the newest row.
 *
 * True only when the view is already showing the end of the transcript. Someone
 * who has scrolled up is reading, and captions arrive several times a second
 * while anyone is speaking — following unconditionally would drag them back to
 * the bottom on every partial-caption delta, making the transcript unreadable
 * for exactly as long as the conversation lasts.
 *
 * @param lastVisibleIndex index of the last row currently laid out, or `-1`
 *   when nothing has been laid out yet. Nothing-laid-out must follow: otherwise
 *   opening the screen on an existing transcript leaves it scrolled to the top.
 * @param targetIndex the row to follow — [captionScrollTarget].
 */
fun shouldFollowCaptions(lastVisibleIndex: Int, targetIndex: Int): Boolean {
    if (targetIndex < 0) return false
    if (lastVisibleIndex < 0) return true
    // One row of slack: the update being reacted to has usually just ADDED the
    // target row, so the last row laid out is still the one before it.
    return lastVisibleIndex >= targetIndex - 1
}
