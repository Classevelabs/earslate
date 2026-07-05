package com.classeve.earslate.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

    val fractions: List<Float> = if (reducedMotion) {
        listOf(0.55f, 0.95f, 0.7f)
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
            ).value
        }
    }

    Row(
        modifier = modifier.height(maxBarHeight),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        fractions.forEach { fraction ->
            Box(
                modifier = Modifier
                    .width(barWidth)
                    .height(maxBarHeight * fraction)
                    .background(color = color, shape = RoundedCornerShape(percent = 50)),
            )
        }
    }
}
