package com.classeve.earslate.ui.captions

import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.classeve.earslate.ui.components.ListeningIndicator
import com.classeve.earslate.ui.theme.EarslateTheme
import com.classeve.earslate.ui.theme.MotionBaseMs
import com.classeve.earslate.ui.theme.PreciseEasing
import kotlinx.coroutines.delay

/**
 * The conversation as a chat: what they said on the left in my language, what
 * I said on the right in theirs. A caption still being spoken is shown muted,
 * so a partial is never mistaken for settled text.
 *
 * Tapping a caption copies it; the header copies the whole conversation.
 *
 * Accessibility: the panel is a polite live region — each newly settled
 * caption is announced by TalkBack without stealing focus, which is the whole
 * point of the app for users who can't hear the source audio.
 */
@Composable
fun CaptionsView(
    captions: List<Caption>,
    modifier: Modifier = Modifier,
    active: Boolean = false,
) {
    val clipboard = LocalClipboardManager.current
    val haptics = LocalHapticFeedback.current
    val latest = captions.lastOrNull { !it.live }

    // What was just copied, so it can say so for a moment.
    var copied by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(copied) {
        if (copied != null) {
            delay(COPIED_SHOWN_MS)
            copied = null
        }
    }
    fun copy(text: String, what: Long) {
        clipboard.setText(AnnotatedString(text))
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        copied = what
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                color = EarslateTheme.colors.elev1,
                shape = EarslateTheme.shapes.lg,
            )
            .padding(horizontal = 16.dp, vertical = 20.dp)
            // Polite live region: when the description below changes (a newly
            // settled caption), TalkBack announces it without interrupting.
            .semantics {
                liveRegion = LiveRegionMode.Polite
                if (latest != null) contentDescription = latest.spoken()
            },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // The header begins and ends where the captions under it do.
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
            Spacer(Modifier.weight(1f))
            if (captions.isNotEmpty()) {
                CopyAll(
                    done = copied == ALL,
                    onClick = { copy(conversationText(captions), ALL) },
                )
            }
        }

        if (captions.isEmpty()) {
            EmptyState(active = active)
        } else {
            val target = captionScrollTarget(captions)

            // Opened on a conversation already under way, the list starts at its end.
            val listState = rememberLazyListState(initialFirstVisibleItemIndex = target.coerceAtLeast(0))

            val following = keepsToItsEnd(listState, listState.interactionSource)

            // Keyed on the captions, not on their count: the caption being
            // spoken grows without the count changing, and once the store's
            // window is full the count never changes again.
            LaunchedEffect(captions, following) {
                if (!following || target < 0) return@LaunchedEffect
                val layout = listState.layoutInfo
                val row = layout.visibleItemsInfo.lastOrNull { it.index == target }
                val panel = layout.viewportEndOffset - layout.viewportStartOffset
                listState.animateScrollToItem(target, scrollOffset = captionEndOffset(row?.size, panel))
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(captions, key = { it.id }) { caption ->
                    Bubble(
                        caption = caption,
                        copied = copied == caption.id,
                        onCopy = { copy(caption.text, caption.id) },
                        modifier = Modifier.animateItem(
                            fadeInSpec = tween(durationMillis = MotionBaseMs, easing = PreciseEasing),
                            fadeOutSpec = tween(durationMillis = MotionBaseMs, easing = PreciseEasing),
                        ),
                    )
                }
            }
        }
    }
}

/** How TalkBack says a caption: who, then what. */
private fun Caption.spoken(): String = "${side.speaker()}: $text"

@Composable
private fun CopyAll(done: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .defaultMinSize(minHeight = 40.dp)
            .clip(EarslateTheme.shapes.sm)
            .clickable(
                onClick = onClick,
                onClickLabel = "Copy the whole conversation",
                role = Role.Button,
            )
            .padding(start = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (done) "COPIED" else "COPY ALL",
            style = EarslateTheme.textStyles.meta,
            color = EarslateTheme.colors.ember,
        )
    }
}

/**
 * One caption. Theirs sits on the left and mine on the right, each with its
 * squared corner on its own side, the way a conversation is read everywhere.
 */
@Composable
private fun Bubble(
    caption: Caption,
    copied: Boolean,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val mine = caption.side == CaptionSide.MINE
    val shape = if (mine) MineShape else TheirsShape
    val colors = EarslateTheme.colors

    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        // Never the full width, so the side a caption is on can be seen at a glance.
        Box(
            modifier = Modifier.fillMaxWidth(BUBBLE_WIDTH),
            contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Column(
                modifier = Modifier
                    .clip(shape)
                    .background(if (mine) colors.emberSoft else colors.elev3)
                    .then(if (mine) Modifier.border(1.dp, colors.emberLine, shape) else Modifier)
                    .clickable(
                        onClick = onCopy,
                        onClickLabel = "Copy",
                        role = Role.Button,
                    )
                    .semantics { contentDescription = caption.spoken() }
                    .padding(start = 14.dp, end = 12.dp, top = 10.dp, bottom = 8.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = caption.text,
                    style = EarslateTheme.textStyles.body,
                    color = if (caption.live) colors.textSecondary else colors.textPrimary,
                    modifier = Modifier.align(Alignment.Start),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (copied) {
                        Text(
                            text = "COPIED",
                            style = EarslateTheme.textStyles.meta,
                            color = colors.ember,
                        )
                    }
                    // The mark that says a caption can be copied. Decorative:
                    // the whole caption is the button, and says so itself.
                    Icon(
                        imageVector = if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                        contentDescription = null,
                        tint = if (copied) colors.ember else colors.textTertiary,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
    }
}

/**
 * True while [scrolling] should keep to its end: until a hand moves it away,
 * and again once it comes to rest there. [since] starts the question afresh.
 */
@Composable
fun keepsToItsEnd(scrolling: ScrollableState, touches: InteractionSource, since: Any? = Unit): Boolean {
    val follow = remember(scrolling, since) { CaptionFollow() }
    var following by remember(follow) { mutableStateOf(follow.following) }
    LaunchedEffect(follow) {
        touches.interactions.collect {
            if (it is DragInteraction.Start) {
                follow.takenHold()
                following = follow.following
            }
        }
    }
    LaunchedEffect(follow) {
        snapshotFlow { scrolling.isScrollInProgress }.collect { moving ->
            if (!moving) {
                follow.cameToRest(atEnd = !scrolling.canScrollForward)
                following = follow.following
            }
        }
    }
    return following
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
                "Tap Start listening and the conversation will appear here, caption by caption."
            },
            style = EarslateTheme.textStyles.bodySmall,
            color = EarslateTheme.colors.textSecondary,
        )
    }
}

/** The key under which "copy all" reports itself copied; no caption has it. */
private const val ALL = -1L
private const val COPIED_SHOWN_MS = 1_600L
private const val BUBBLE_WIDTH = 0.86f

private val TheirsShape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomEnd = 16.dp, bottomStart = 4.dp)
private val MineShape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomEnd = 4.dp, bottomStart = 16.dp)
