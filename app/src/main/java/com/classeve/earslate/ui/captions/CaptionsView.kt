package com.classeve.earslate.ui.captions

import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.classeve.earslate.ui.components.ListeningIndicator
import com.classeve.earslate.ui.theme.EarslateTheme
import com.classeve.earslate.ui.theme.MotionBaseMs
import com.classeve.earslate.ui.theme.PreciseEasing

/**
 * Renders the rolling caption transcript. Lines fade in as they commit; the
 * pending partial line shows in a muted tone so the user sees incremental
 * progress without a flicker of stale text.
 *
 * Accessibility: the panel is a polite live region — each newly committed
 * caption line is announced by TalkBack without stealing focus, which is the
 * whole point of the app for users who can't hear the source audio. Text
 * remains selectable/copyable.
 */
@Composable
fun CaptionsView(
    lines: List<String>,
    pending: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
) {
    val latestLine = lines.lastOrNull().orEmpty()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                color = EarslateTheme.colors.elev1,
                shape = EarslateTheme.shapes.lg,
            )
            .padding(24.dp)
            // Polite live region: when the description below changes (a new
            // committed line), TalkBack announces it without interrupting.
            .semantics {
                liveRegion = LiveRegionMode.Polite
                if (latestLine.isNotEmpty()) contentDescription = latestLine
            },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "CAPTIONS",
                style = EarslateTheme.textStyles.meta,
                color = EarslateTheme.colors.textTertiary,
            )
            if (active) {
                // Decorative — the status pill on the main screen carries the
                // spoken "Listening" state for TalkBack.
                ListeningIndicator(color = EarslateTheme.colors.ember)
            }
        }

        if (lines.isEmpty() && pending.isEmpty()) {
            EmptyState(active = active)
        } else {
            val listState = rememberLazyListState()

            // ONE list, used for both rendering and scrolling. The two used to
            // be computed separately and disagreed: the column emitted
            // `lines.size + 1` items whenever a partial line was in flight,
            // while the effect scrolled to `lines.lastIndex`.
            val rows = remember(lines, pending) { captionRows(lines, pending) }
            val target = captionScrollTarget(rows)

            // Keyed on the ROWS, not on their count.
            //
            // `LaunchedEffect(lines.size)` missed the two cases that matter
            // most. The live partial row grows without the count changing, so
            // the translation appeared below the fold while the user watched
            // it being typed. And CaptionsStore keeps a `takeLast(48)` rolling
            // window, so once 48 lines have committed the count never changes
            // again — a size-keyed effect simply stopped firing for the rest of
            // the session.
            LaunchedEffect(rows) {
                val lastLaidOut = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                if (shouldFollowCaptions(lastVisibleIndex = lastLaidOut, targetIndex = target)) {
                    listState.animateScrollToItem(target)
                }
            }

            SelectionContainer {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // Keys stay POSITIONAL, deliberately. A caption is a plain
                    // string with no stable id of its own, and duplicates are
                    // routine in conversation — "yes", "okay", "mm" — so keying
                    // on content would hand the LazyColumn duplicate keys and
                    // crash it. Positional keys over a rolling window are the
                    // lesser problem, and the one already shipped. Give
                    // CaptionsStore a per-line id and this can improve; until
                    // then, leave it.
                    itemsIndexed(rows, key = { index, _ -> index }) { _, row ->
                        Text(
                            text = row.text,
                            style = EarslateTheme.textStyles.body,
                            // The line still being spoken stays muted, so a
                            // partial is never mistaken for settled text.
                            color = if (row.live) {
                                EarslateTheme.colors.textSecondary
                            } else {
                                EarslateTheme.colors.textPrimary
                            },
                            modifier = Modifier.animateItem(
                                fadeInSpec = tween(
                                    durationMillis = MotionBaseMs,
                                    easing = PreciseEasing,
                                ),
                                fadeOutSpec = tween(
                                    durationMillis = MotionBaseMs,
                                    easing = PreciseEasing,
                                ),
                            ),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Tasteful empty state: quiet dot motif + copy that matches the session
 * state, so the panel never looks broken before the first line arrives.
 */
@Composable
private fun EmptyState(active: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(3) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(
                            color = if (active) {
                                EarslateTheme.colors.ember
                            } else {
                                EarslateTheme.colors.surfaceStrong
                            },
                            shape = CircleShape,
                        ),
                )
            }
        }
        Text(
            text = if (active) "Listening…" else "Quiet in here",
            style = EarslateTheme.textStyles.h3,
            color = EarslateTheme.colors.textPrimary,
        )
        Text(
            text = if (active) {
                "Captions appear the moment someone speaks."
            } else {
                "Tap Start listening and translated speech will stream here, line by line."
            },
            style = EarslateTheme.textStyles.bodySmall,
            color = EarslateTheme.colors.textSecondary,
        )
    }
}
