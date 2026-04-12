package com.classeve.earslate.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.classeve.earslate.session.RuntimeError
import com.classeve.earslate.ui.theme.EarslateTheme

/**
 * Error banner — hairline bordered panel in the ClassEve danger tint. Uses the
 * muted `--danger` token, not the saturated `--error` color, so it stays on
 * brand. Blueprint §9: "Avoid color-only error states; include icons, text, or
 * other affordances" — we include a KICKER label + explicit message text +
 * optional retry action.
 */
@Composable
fun ErrorBanner(
    error: RuntimeError,
    onRetry: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val kickerLabel = when (error.kind) {
        RuntimeError.Kind.MISSING_API_KEY -> "SETUP REQUIRED"
        RuntimeError.Kind.BOOTSTRAP_FAILED -> "BOOTSTRAP FAILED"
        RuntimeError.Kind.CONNECT_FAILED -> "CONNECT FAILED"
        RuntimeError.Kind.PERMISSION_DENIED -> "PERMISSION NEEDED"
        RuntimeError.Kind.UNKNOWN -> "ERROR"
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                color = EarslateTheme.colors.errorBg,
                shape = EarslateTheme.shapes.md,
            )
            .border(
                width = 1.dp,
                color = EarslateTheme.colors.errorBorder,
                shape = EarslateTheme.shapes.md,
            )
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = kickerLabel,
            style = EarslateTheme.textStyles.kicker,
            color = EarslateTheme.colors.danger,
        )
        Text(
            text = error.message,
            style = EarslateTheme.textStyles.body,
            color = EarslateTheme.colors.textPrimary,
        )
        if (onRetry != null || onDismiss != null) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                onRetry?.let {
                    BannerAction(label = "RETRY", primary = true, onClick = it)
                }
                onDismiss?.let {
                    BannerAction(label = "DISMISS", primary = false, onClick = it)
                }
            }
        }
    }
}

@Composable
private fun BannerAction(
    label: String,
    primary: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (primary) EarslateTheme.colors.danger else EarslateTheme.colors.surfaceSoft
    val fg = if (primary) EarslateTheme.colors.canvas else EarslateTheme.colors.textPrimary
    val borderColor =
        if (primary) EarslateTheme.colors.danger else EarslateTheme.colors.borderDefault

    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .clickable(onClick = onClick)
            .background(color = bg, shape = EarslateTheme.shapes.pill)
            .border(width = 1.dp, color = borderColor, shape = EarslateTheme.shapes.pill)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            style = EarslateTheme.textStyles.kicker.copy(fontWeight = FontWeight.SemiBold),
            color = fg,
        )
    }
}
