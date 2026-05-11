package com.classeve.earslate.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Raw tokens — keep in sync with res/values/colors.xml

// Canvas & elevation
val Canvas = Color(0xFF1A1612)
val Elev1 = Color(0xFF221C16)
val Elev2 = Color(0xFF2A2218)
val Elev3 = Color(0xFF3A2F23)

// Surfaces (alpha-over-cream)
val SurfaceGhost = Color(0x0AECE3D2)
val SurfaceSoft = Color(0x12ECE3D2)
val SurfaceStrong = Color(0x1FECE3D2)

// Borders
val BorderSubtle = Color(0x14E7D9C4)
val BorderDefault = Color(0x24E7D9C4)
val BorderStrong = Color(0x38E7D9C4)

// Text
val Cream = Color(0xFFECE3D2)
val CreamSoft = Color(0xFFD8CDB7)
val CreamDeep = Color(0xFFC7BBA2)
val TextPrimary = Color(0xFFECE3D2)
val TextSecondary = Color(0xFFB8AA8E)
val TextTertiary = Color(0xFF80735C)
val TextDisabled = Color(0xFF4A4032)

// Ember / oxblood accents
val Ember = Color(0xFFC2410C)
val EmberHover = Color(0xFFD85317)
val EmberPressed = Color(0xFFA93609)
val EmberSoft = Color(0x2EC2410C)
val EmberGlow = Color(0x66FF7828)
val EmberLine = Color(0x8CE76C20)
val Oxblood = Color(0xFF8B1E1E)
val OxbloodSoft = Color(0x388B1E1E)
val OnEmber = Color(0xFF1A0C06)

// Legacy accent aliases
val Accent = Ember
val AccentHover = EmberHover
val AccentPressed = EmberPressed
val SignalSoft = EmberSoft
val FocusRing = Color(0x52FF7828)

// States
val Success = Color(0xFFC9D3C6)
val Warning = Color(0xFFD6C7A8)
val Danger = Color(0xFFD3B3AF)
val ErrorStrong = Oxblood
val ErrorBg = OxbloodSoft
val ErrorBorder = Oxblood

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
    val cream: Color,
    val creamSoft: Color,
    val creamDeep: Color,
    val ember: Color,
    val emberHover: Color,
    val emberPressed: Color,
    val emberSoft: Color,
    val emberGlow: Color,
    val emberLine: Color,
    val oxblood: Color,
    val oxbloodSoft: Color,
    val onEmber: Color,
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
    cream = Cream,
    creamSoft = CreamSoft,
    creamDeep = CreamDeep,
    ember = Ember,
    emberHover = EmberHover,
    emberPressed = EmberPressed,
    emberSoft = EmberSoft,
    emberGlow = EmberGlow,
    emberLine = EmberLine,
    oxblood = Oxblood,
    oxbloodSoft = OxbloodSoft,
    onEmber = OnEmber,
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
