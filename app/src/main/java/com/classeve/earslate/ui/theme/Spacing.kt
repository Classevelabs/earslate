package com.classeve.earslate.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 4 dp baseline grid. Names match the ClassEve CSS `--space-N` tokens so that
 * Figma / dev sync feels identical across the website and the app.
 */
@Immutable
data class EarslateSpacing(
    val s1: Dp = 4.dp,
    val s2: Dp = 8.dp,
    val s3: Dp = 12.dp,
    val s4: Dp = 16.dp,
    val s5: Dp = 20.dp,
    val s6: Dp = 24.dp,
    val s8: Dp = 32.dp,
    val s10: Dp = 40.dp,
    val s12: Dp = 48.dp,
    val s16: Dp = 64.dp,
    val s20: Dp = 80.dp,
    val s24: Dp = 96.dp,
)

val DefaultEarslateSpacing = EarslateSpacing()

val LocalEarslateSpacing = staticCompositionLocalOf { DefaultEarslateSpacing }
