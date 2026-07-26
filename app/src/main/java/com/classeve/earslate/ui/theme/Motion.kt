package com.classeve.earslate.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

// ClassEve motion tokens — 140ms / 220ms / 320ms with a restrained precise curve.

const val MotionFastMs = 140
const val MotionBaseMs = 220
const val MotionSlowMs = 320

/** cubic-bezier(0.22, 1, 0.36, 1) — the same easing used on classeve.com. */
val PreciseEasing: Easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

/**
 * True when the user has turned off system animations (animator duration
 * scale of 0 — set by the "Remove animations" accessibility toggle or by
 * Developer options). All *decorative* motion — pulses, staggered reveals,
 * press-scale feedback, infinite transitions — must be skipped when this is
 * on. State changes should still land instantly; only the choreography goes.
 *
 * Read once per composition root: the value only changes from system
 * settings, and re-reading on every recomposition would hit the
 * ContentResolver needlessly.
 */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    }
}
