package com.classeve.earslate.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.classeve.earslate.ui.theme.EarslateTheme

/**
 * Classeve-style section opener: uppercase kicker label, big display-weight
 * headline, optional support line. Matches the "Section Opener" pattern from
 * the website blueprint §8.4.
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
            style = EarslateTheme.textStyles.kicker,
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
                style = EarslateTheme.textStyles.bodyMuted,
                color = EarslateTheme.colors.textSecondary,
            )
        }
    }
}
