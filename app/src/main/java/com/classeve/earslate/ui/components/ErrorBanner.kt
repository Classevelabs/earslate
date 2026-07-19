package com.classeve.earslate.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
// `Color` is still required for the BandPalette data class declaration below.
import androidx.compose.ui.unit.dp
import com.classeve.earslate.session.RuntimeError
import com.classeve.earslate.ui.theme.EarslateTheme

/**
 * ClassEve runtime-error banner — flat oxblood-soft fill with cream text.
 *
 * Boxy action buttons. No glow, no gradients, no glass.
 */
@Composable
fun ErrorBanner(
    error: RuntimeError,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
) {
    val kickerLabel = when (error.kind) {
        RuntimeError.Kind.BOOTSTRAP_FAILED -> "BOOTSTRAP FAILED"
        RuntimeError.Kind.CONNECT_FAILED -> "CONNECT FAILED"
        RuntimeError.Kind.PERMISSION_DENIED -> "PERMISSION NEEDED"
        RuntimeError.Kind.UNKNOWN -> "ERROR"
    }

    // Brand-correct band treatment by kind.
    val palette = bandPaletteFor(error.kind)

    val showRetry = onRetry != null

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                color = palette.background,
                shape = EarslateTheme.shapes.lg,
            )
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = kickerLabel,
            style = EarslateTheme.textStyles.meta,
            color = palette.kicker,
        )
        Text(
            text = error.message,
            style = EarslateTheme.textStyles.body,
            color = palette.text,
        )
        if (showRetry || onDismiss != null) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showRetry) {
                    onRetry?.let {
                        BannerAction(
                            label = "RETRY",
                            primary = true,
                            onClick = it,
                        )
                    }
                }
                onDismiss?.let {
                    BannerAction(label = "DISMISS", primary = false, onClick = it)
                }
            }
        }
    }
}

private data class BandPalette(
    val background: Color,
    val text: Color,
    val kicker: Color,
)

@Composable
private fun bandPaletteFor(@Suppress("UNUSED_PARAMETER") kind: RuntimeError.Kind): BandPalette {
    val colors = EarslateTheme.colors
    return BandPalette(
        background = colors.oxbloodSoft,
        text = colors.cream,
        kicker = colors.creamSoft,
    )
}

@Composable
private fun BannerAction(
    label: String,
    primary: Boolean,
    onClick: () -> Unit,
) {
    val colors = EarslateTheme.colors
    // Primary CTA = ember pill with onEmber text. Secondary = bg-elev-2 fill
    // (the brand spec for secondary pills against a canvas / elev-1 surface).
    val bg = if (primary) colors.ember else colors.elev2
    val fg = if (primary) colors.onEmber else colors.cream

    Box(
        modifier = Modifier
            .clickable(onClick = onClick)
            .background(color = bg, shape = EarslateTheme.shapes.pill)
            .padding(horizontal = 18.dp, vertical = 10.dp),
    ) {
        Text(
            text = label,
            style = EarslateTheme.textStyles.meta.copy(fontWeight = FontWeight.SemiBold),
            color = fg,
        )
    }
}
