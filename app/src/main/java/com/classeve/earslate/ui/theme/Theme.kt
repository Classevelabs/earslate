package com.classeve.earslate.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable

private val DarkColorScheme = darkColorScheme(
    background = Canvas,
    onBackground = TextPrimary,
    surface = Elev1,
    onSurface = TextPrimary,
    surfaceVariant = Elev2,
    onSurfaceVariant = TextSecondary,
    primary = Accent,
    onPrimary = Canvas,
    secondary = Accent,
    onSecondary = Canvas,
    tertiary = Accent,
    onTertiary = Canvas,
    error = ErrorStrong,
    onError = Canvas,
    outline = BorderDefault,
    outlineVariant = BorderSubtle,
)

object EarslateTheme {
    val colors: EarslateColors
        @Composable
        @ReadOnlyComposable
        get() = LocalEarslateColors.current

    val textStyles: EarslateTextStyles
        @Composable
        @ReadOnlyComposable
        get() = LocalEarslateTextStyles.current

    val shapes: EarslateShapes
        @Composable
        @ReadOnlyComposable
        get() = LocalEarslateShapes.current

    val spacing: EarslateSpacing
        @Composable
        @ReadOnlyComposable
        get() = LocalEarslateSpacing.current
}

@Composable
fun EarslateTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalEarslateColors provides DarkEarslateColors,
        LocalEarslateTextStyles provides DefaultEarslateTextStyles,
        LocalEarslateShapes provides DefaultEarslateShapes,
        LocalEarslateSpacing provides DefaultEarslateSpacing,
    ) {
        MaterialTheme(
            colorScheme = DarkColorScheme,
            typography = EarslateMaterialTypography,
            shapes = EarslateMaterialShapes,
            content = content,
        )
    }
}
