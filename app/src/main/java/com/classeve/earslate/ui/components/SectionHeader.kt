package com.classeve.earslate.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.classeve.earslate.ui.theme.EarslateTheme

/**
 * ClassEve section opener — mono uppercase meta-label, big display headline,
 * optional support line. Mirrors the `.section-opener` pattern from the
 * canonical reference CSS.
 */
@Composable
fun SectionHeader(
    kicker: String,
    headline: String,
    support: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = kicker.uppercase(),
            style = EarslateTheme.textStyles.meta,
            color = EarslateTheme.colors.textTertiary,
        )
        Text(
            text = headline,
            style = EarslateTheme.textStyles.h1,
            color = EarslateTheme.colors.textPrimary,
        )
        if (!support.isNullOrBlank()) {
            Text(
                text = support,
                style = EarslateTheme.textStyles.body,
                color = EarslateTheme.colors.textSecondary,
            )
        }
    }
}
