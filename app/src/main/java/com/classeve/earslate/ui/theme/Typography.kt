package com.classeve.earslate.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

// Task 6 will swap these to Space Grotesk + Geist + IBM Plex Mono via Google Fonts provider.
// For the scaffold, system fonts carry the correct sizes/weights/letter-spacing.
private val DisplayFamily = FontFamily.SansSerif
private val BodyFamily = FontFamily.SansSerif
private val MonoFamily = FontFamily.Monospace

private val DisplayStyle = TextStyle(
    fontFamily = DisplayFamily,
    fontWeight = FontWeight.SemiBold,
    fontStyle = FontStyle.Normal,
    fontSize = 40.sp,
    lineHeight = 40.sp,
    letterSpacing = (-0.04).em,
)

private val H1Style = TextStyle(
    fontFamily = DisplayFamily,
    fontWeight = FontWeight.SemiBold,
    fontSize = 30.sp,
    lineHeight = 33.sp,
    letterSpacing = (-0.03).em,
)

private val H2Style = TextStyle(
    fontFamily = DisplayFamily,
    fontWeight = FontWeight.SemiBold,
    fontSize = 22.sp,
    lineHeight = 26.sp,
    letterSpacing = (-0.03).em,
)

private val H3Style = TextStyle(
    fontFamily = DisplayFamily,
    fontWeight = FontWeight.SemiBold,
    fontSize = 18.sp,
    lineHeight = 22.sp,
    letterSpacing = (-0.02).em,
)

private val BodyStyle = TextStyle(
    fontFamily = BodyFamily,
    fontWeight = FontWeight.Normal,
    fontSize = 16.sp,
    lineHeight = 24.sp,
)

private val BodyMutedStyle = BodyStyle.copy(
    fontSize = 15.sp,
    lineHeight = 22.sp,
)

private val BodySmallStyle = BodyStyle.copy(
    fontSize = 14.sp,
    lineHeight = 20.sp,
)

private val CaptionStyle = TextStyle(
    fontFamily = BodyFamily,
    fontWeight = FontWeight.Normal,
    fontSize = 12.sp,
    lineHeight = 16.sp,
    letterSpacing = 0.01.em,
)

private val KickerStyle = TextStyle(
    fontFamily = MonoFamily,
    fontWeight = FontWeight.Medium,
    fontSize = 11.sp,
    lineHeight = 16.sp,
    letterSpacing = 0.08.em,
)

@Immutable
data class EarslateTextStyles(
    val display: TextStyle,
    val h1: TextStyle,
    val h2: TextStyle,
    val h3: TextStyle,
    val body: TextStyle,
    val bodyMuted: TextStyle,
    val bodySmall: TextStyle,
    val caption: TextStyle,
    val meta: TextStyle,
    val kicker: TextStyle,
)

val DefaultEarslateTextStyles = EarslateTextStyles(
    display = DisplayStyle,
    h1 = H1Style,
    h2 = H2Style,
    h3 = H3Style,
    body = BodyStyle,
    bodyMuted = BodyMutedStyle,
    bodySmall = BodySmallStyle,
    caption = CaptionStyle,
    meta = KickerStyle,
    kicker = KickerStyle,
)

val LocalEarslateTextStyles = staticCompositionLocalOf { DefaultEarslateTextStyles }

val EarslateMaterialTypography = Typography(
    displayLarge = DisplayStyle,
    displayMedium = DisplayStyle.copy(fontSize = 34.sp, lineHeight = 34.sp),
    displaySmall = H1Style,
    headlineLarge = H1Style,
    headlineMedium = H2Style,
    headlineSmall = H3Style,
    titleLarge = H3Style,
    titleMedium = BodyStyle.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = BodySmallStyle.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = BodyStyle,
    bodyMedium = BodyMutedStyle,
    bodySmall = BodySmallStyle,
    labelLarge = BodyStyle.copy(fontWeight = FontWeight.SemiBold),
    labelMedium = CaptionStyle,
    labelSmall = KickerStyle,
)
