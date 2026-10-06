package com.classeve.earslate.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.classeve.earslate.ui.theme.PreciseEasing
import com.classeve.earslate.ui.theme.rememberReducedMotion

/**
 * Tiny three-bar "equaliser" that gently oscillates while a session is
 * listening/translating. Purely decorative — always pair it with a text
 * status label; it is hidden from TalkBack (no semantics of its own, the
 * enclosing status pill carries the description).
 *
 * Respects the system "remove animations" setting: with reduced motion the
 * bars render at staggered static heights (still reads as "active", no
 * movement).
 */
@Composable
fun ListeningIndicator(
    color: Color,
    modifier: Modifier = Modifier,
    barWidth: Dp = 3.dp,
    maxBarHeight: Dp = 12.dp,
) {
    val reducedMotion = rememberReducedMotion()

    val moving: List<State<Float>>? = if (reducedMotion) {
        null
    } else {
        val transition = rememberInfiniteTransition(label = "listening-bars")
        listOf(0, 160, 320).map { delayMs ->
            transition.animateFloat(
                initialValue = 0.35f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 520, easing = PreciseEasing),
                    repeatMode = RepeatMode.Reverse,
                    initialStartOffset = StartOffset(delayMs),
                ),
                label = "listening-bar-$delayMs",
            )
        }
    }

    // The heights are read here, where they are drawn, and nowhere else. Read
    // while composing, they had whatever holds this indicator composed and
    // laid out again on every frame for as long as a session ran.
    Canvas(modifier = modifier.size(width = barWidth * BARS + BAR_GAP * (BARS - 1), height = maxBarHeight)) {
        val width = barWidth.toPx()
        val step = width + BAR_GAP.toPx()
        repeat(BARS) { bar ->
            val height = size.height * (moving?.get(bar)?.value ?: STILL[bar])
            drawRoundRect(
                color = color,
                topLeft = Offset(bar * step, (size.height - height) / 2),
                size = Size(width, height),
                cornerRadius = CornerRadius(width / 2),
            )
        }
    }
}

private const val BARS = 3
private val BAR_GAP = 2.dp

/** The bars as they stand when nothing may move. */
private val STILL = floatArrayOf(0.55f, 0.95f, 0.7f)
