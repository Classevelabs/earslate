package com.classeve.earslate.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.classeve.earslate.session.SupportedLanguages
import com.classeve.earslate.session.TargetLanguage
import com.classeve.earslate.ui.theme.EarslateTheme

@Composable
fun LanguagePickerDialog(
    currentLanguage: TargetLanguage,
    onSelect: (TargetLanguage) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }

    val filtered = remember(query) {
        if (query.isBlank()) {
            SupportedLanguages
        } else {
            SupportedLanguages.filter {
                it.displayName.contains(query, ignoreCase = true)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = EarslateTheme.colors.elev1,
        shape = EarslateTheme.shapes.lg,
        title = {
            Text(
                text = "Target language",
                style = EarslateTheme.textStyles.h3,
                color = EarslateTheme.colors.textPrimary,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = {
                        Text(
                            text = "Search languages...",
                            style = EarslateTheme.textStyles.bodyMuted,
                            color = EarslateTheme.colors.textTertiary,
                        )
                    },
                    singleLine = true,
                    textStyle = EarslateTheme.textStyles.body.copy(
                        color = EarslateTheme.colors.textPrimary,
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = EarslateTheme.colors.accent,
                        unfocusedBorderColor = EarslateTheme.colors.borderDefault,
                        cursorColor = EarslateTheme.colors.accent,
                    ),
                    shape = EarslateTheme.shapes.md,
                    modifier = Modifier.fillMaxWidth(),
                )

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(filtered, key = { it.bcp47 }) { language ->
                        val isSelected = language.bcp47 == currentLanguage.bcp47
                        LanguageRow(
                            language = language,
                            isSelected = isSelected,
                            onClick = { onSelect(language) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = "Cancel",
                    style = EarslateTheme.textStyles.kicker,
                    color = EarslateTheme.colors.textSecondary,
                )
            }
        },
    )
}

@Composable
private fun LanguageRow(
    language: TargetLanguage,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (isSelected) {
        EarslateTheme.colors.surfaceStrong
    } else {
        EarslateTheme.colors.surfaceGhost
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(color = bg, shape = EarslateTheme.shapes.md)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = language.displayName,
            style = EarslateTheme.textStyles.body,
            color = if (isSelected) {
                EarslateTheme.colors.accent
            } else {
                EarslateTheme.colors.textPrimary
            },
        )
        Text(
            text = language.bcp47,
            style = EarslateTheme.textStyles.kicker,
            color = EarslateTheme.colors.textTertiary,
        )
    }
}
