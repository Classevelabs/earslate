package com.classeve.earslate.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Raw tokens — keep in sync with res/values/colors.xml

// Canvas & elevation
val Canvas = Color(0xFF000000)
val Elev1 = Color(0xFF040404)
val Elev2 = Color(0xFF080808)
val Elev3 = Color(0xFF0C0C0C)

// Surfaces (alpha-over-white)
val SurfaceGhost = Color(0x05FFFFFF)
val SurfaceSoft = Color(0x0AFFFFFF)
val SurfaceStrong = Color(0x12FFFFFF)

// Borders
val BorderSubtle = Color(0x14FFFFFF)
val BorderDefault = Color(0x1FFFFFFF)
val BorderStrong = Color(0x2EFFFFFF)

// Text
val TextPrimary = Color(0xFFE2DCD2)
val TextSecondary = Color(0xFFB2AA9D)
val TextTertiary = Color(0xFF787069)
val TextDisabled = Color(0xFF56524C)

// Accent & focus
val Accent = Color(0xFFD3CCC1)
val AccentHover = Color(0xFFE2DCD2)
val AccentPressed = Color(0xFFBEB7AC)
val SignalSoft = Color(0x33D3CCC1)
val FocusRing = Color(0x47E2DCD2)

// States
val Success = Color(0xFFC9D3C6)
val Warning = Color(0xFFD6C7A8)
val Danger = Color(0xFFD3B3AF)
val ErrorStrong = Color(0xFFFF6B6B)
val ErrorBg = Color(0x1AFF6B6B)
val ErrorBorder = Color(0x4DFF6B6B)

@Immutable
data class EarslateColors(
    val canvas: Color,
    val elev1: Color,
    val elev2: Color,
    val elev3: Color,
    val surfaceGhost: Color,
    val surfaceSoft: Color,
    val surfaceStrong: Color,
    val borderSubtle: Color,
    val borderDefault: Color,
    val borderStrong: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val textDisabled: Color,
    val accent: Color,
    val accentHover: Color,
    val accentPressed: Color,
    val signalSoft: Color,
    val focusRing: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    val error: Color,
    val errorBg: Color,
    val errorBorder: Color,
)

val DarkEarslateColors = EarslateColors(
    canvas = Canvas,
    elev1 = Elev1,
    elev2 = Elev2,
    elev3 = Elev3,
    surfaceGhost = SurfaceGhost,
    surfaceSoft = SurfaceSoft,
    surfaceStrong = SurfaceStrong,
    borderSubtle = BorderSubtle,
    borderDefault = BorderDefault,
    borderStrong = BorderStrong,
    textPrimary = TextPrimary,
    textSecondary = TextSecondary,
    textTertiary = TextTertiary,
    textDisabled = TextDisabled,
    accent = Accent,
    accentHover = AccentHover,
    accentPressed = AccentPressed,
    signalSoft = SignalSoft,
    focusRing = FocusRing,
    success = Success,
    warning = Warning,
    danger = Danger,
    error = ErrorStrong,
    errorBg = ErrorBg,
    errorBorder = ErrorBorder,
)

val LocalEarslateColors = staticCompositionLocalOf { DarkEarslateColors }
