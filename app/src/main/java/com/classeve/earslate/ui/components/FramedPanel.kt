package com.classeve.earslate.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.classeve.earslate.ui.theme.EarslateTheme

/**
 * The Classeve "framed plane" pattern — matte elevated surface, hairline
 * border, large radius, generous interior padding. No shadow. Blueprint §8.5.
 */
@Composable
fun FramedPanel(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                color = EarslateTheme.colors.surfaceGhost,
                shape = EarslateTheme.shapes.lg,
            )
            .border(
                width = 1.dp,
                color = EarslateTheme.colors.borderSubtle,
                shape = EarslateTheme.shapes.lg,
            )
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        content()
    }
}
