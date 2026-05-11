package com.classeve.earslate.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.classeve.earslate.ui.theme.EarslateTheme

/**
 * ClassEve dock-plane / framed panel pattern. Flat matte fill at `bg-elev-1`,
 * no border, no shadow, no blur. Generous interior padding, brand radius `lg`.
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
                color = EarslateTheme.colors.elev1,
                shape = EarslateTheme.shapes.lg,
            )
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        content()
    }
}
