package com.classeve.earslate.ui.captions

import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.classeve.earslate.ui.theme.EarslateTheme
import com.classeve.earslate.ui.theme.MotionBaseMs
import com.classeve.earslate.ui.theme.PreciseEasing

/**
 * Renders the rolling caption transcript. Lines fade in as they commit; the
 * pending partial line shows in a muted tone so the user sees incremental
 * progress without a flicker of stale text.
 */
@Composable
fun CaptionsView(
    lines: List<String>,
    pending: String,
    modifier: Modifier = Modifier,
) {
    SelectionContainer {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .background(
                    color = EarslateTheme.colors.elev1,
                    shape = EarslateTheme.shapes.lg,
                )
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "CAPTIONS",
                style = EarslateTheme.textStyles.meta,
                color = EarslateTheme.colors.textTertiary,
            )

            if (lines.isEmpty() && pending.isEmpty()) {
                Text(
                    text = "Translated speech will stream here when a session is active.",
                    style = EarslateTheme.textStyles.body,
                    color = EarslateTheme.colors.textSecondary,
                )
            } else {
                val listState = rememberLazyListState()

                LaunchedEffect(lines.size) {
                    if (lines.isNotEmpty()) {
                        listState.animateScrollToItem(lines.lastIndex)
                    }
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    itemsIndexed(lines, key = { index, _ -> index }) { _, line ->
                        Text(
                            text = line,
                            style = EarslateTheme.textStyles.body,
                            color = EarslateTheme.colors.textPrimary,
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
                    if (pending.isNotEmpty()) {
                        item {
                            Text(
                                text = pending,
                                style = EarslateTheme.textStyles.body,
                                color = EarslateTheme.colors.textSecondary,
                            )
                        }
                    }
                }
            }
        }
    }
}
