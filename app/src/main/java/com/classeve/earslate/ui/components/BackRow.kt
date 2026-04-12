package com.classeve.earslate.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.classeve.earslate.ui.theme.EarslateTheme

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
                .size(8.dp)
                .background(
                    color = EarslateTheme.colors.textTertiary,
                    shape = CircleShape,
                ),
        )
        Text(
            text = "BACK",
            style = EarslateTheme.textStyles.kicker,
            color = EarslateTheme.colors.textTertiary,
        )
    }
}
