package com.classeve.earslate.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.classeve.earslate.ui.theme.EarslateTheme

/**
 * Back row — a thin meta-label line with a hairline arrow stub, matching the
 * `.route-indicator` motif from the canonical CSS. Tappable across its full
 * height so the user doesn't have to hit a pixel target.
 */
@Composable
fun BackRow(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            .clickable(onClick = onBack, onClickLabel = "Go back")
            .semantics { contentDescription = "Back" },
    ) {
        Box(
            modifier = Modifier
                .width(24.dp)
                .height(1.dp)
                .background(color = EarslateTheme.colors.textTertiary),
        )
        Text(
            text = "BACK",
            style = EarslateTheme.textStyles.meta,
            color = EarslateTheme.colors.textTertiary,
        )
    }
}
