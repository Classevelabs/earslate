package com.classeve.earslate.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing

// ClassEve motion tokens — 140ms / 220ms / 320ms with a restrained precise curve.
// Blueprint §motion.

const val MotionFastMs = 140
const val MotionBaseMs = 220
const val MotionSlowMs = 320

/** cubic-bezier(0.22, 1, 0.36, 1) — the same easing used on classeve.com. */
val PreciseEasing: Easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
