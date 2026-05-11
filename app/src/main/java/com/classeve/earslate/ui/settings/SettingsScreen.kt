package com.classeve.earslate.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.classeve.earslate.session.TargetLanguage
import com.classeve.earslate.ui.components.BackRow
import com.classeve.earslate.ui.components.FramedPanel
import com.classeve.earslate.ui.components.SectionHeader
import com.classeve.earslate.ui.theme.EarslateTheme
import com.classeve.earslate.ui.theme.MotionBaseMs
import com.classeve.earslate.ui.theme.PreciseEasing

/**
 * Settings — ClassEve brand v6. Flat rows inside framed dock-planes, ember
 * boxy toggles, mono uppercase meta-labels. No border, no shadow, no glass.
 */
@Composable
fun SettingsScreen(
    initialTargetLanguage: TargetLanguage = TargetLanguage.EnglishUS,
    initialSecondaryLanguage: TargetLanguage? = null,
    initialConversationMode: Boolean = false,
    initialExternalOnly: Boolean = false,
    initialCaptionsEnabled: Boolean = true,
    initialPreferEarbuds: Boolean = true,
    initialDiagnosticsEnabled: Boolean = false,
    initialPersistentNotification: Boolean = false,
    onBack: () -> Unit,
    onTargetLanguageChange: (TargetLanguage) -> Unit = {},
    onSecondaryLanguageChange: (TargetLanguage?) -> Unit = {},
    onConversationModeChange: (Boolean) -> Unit = {},
    onExternalOnlyChange: (Boolean) -> Unit = {},
    onCaptionsEnabledChange: (Boolean) -> Unit = {},
    onPreferEarbudsChange: (Boolean) -> Unit = {},
    onDiagnosticsEnabledChange: (Boolean) -> Unit = {},
    onPersistentNotificationChange: (Boolean) -> Unit = {},
    onOpenDiagnostics: () -> Unit = {},
    onOpenOnboarding: () -> Unit = {},
    onOpenHelp: () -> Unit = {},
    padding: PaddingValues = PaddingValues(0.dp),
) {
    var targetLanguage by remember { mutableStateOf(initialTargetLanguage) }
    var secondaryLanguage by remember { mutableStateOf(initialSecondaryLanguage) }
    var conversationMode by remember { mutableStateOf(initialConversationMode) }
    var externalOnly by remember { mutableStateOf(initialExternalOnly) }
    var captionsEnabled by remember { mutableStateOf(initialCaptionsEnabled) }
    var preferEarbuds by remember { mutableStateOf(initialPreferEarbuds) }
    var diagnosticsEnabled by remember { mutableStateOf(initialDiagnosticsEnabled) }
    var persistentNotification by remember { mutableStateOf(initialPersistentNotification) }
    var showNativePicker by remember { mutableStateOf(false) }
    var showSecondaryPicker by remember { mutableStateOf(false) }

    if (showNativePicker) {
        LanguagePickerDialog(
            currentLanguage = targetLanguage,
            onSelect = { selected ->
                targetLanguage = selected
                onTargetLanguageChange(selected)
                showNativePicker = false
            },
            onDismiss = { showNativePicker = false },
        )
    }

    if (showSecondaryPicker) {
        LanguagePickerDialog(
            currentLanguage = secondaryLanguage ?: TargetLanguage.EnglishUS,
            onSelect = { selected ->
                secondaryLanguage = selected
                onSecondaryLanguageChange(selected)
                showSecondaryPicker = false
            },
            onDismiss = { showSecondaryPicker = false },
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(EarslateTheme.colors.canvas)
            .padding(padding)
            .statusBarsPadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 48.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            BackRow(onBack = onBack)

            SectionHeader(
                kicker = "Language",
                headline = "Translation.",
                support = "Set your native language and how the translator behaves.",
            )

            FramedPanel {
                SettingsRow(
                    label = "Your language",
                    value = targetLanguage.displayName,
                    onClick = { showNativePicker = true },
                )
                Divider()
                ToggleRow(
                    label = "Conversation mode",
                    helper = "Bidirectional: foreign speech → your language, your speech → secondary language.",
                    value = conversationMode,
                    onChange = {
                        conversationMode = it
                        onConversationModeChange(it)
                    },
                )
                AnimatedVisibility(
                    visible = conversationMode,
                    enter = expandVertically(tween(MotionBaseMs, easing = PreciseEasing)) + fadeIn(tween(MotionBaseMs)),
                    exit = shrinkVertically(tween(MotionBaseMs, easing = PreciseEasing)) + fadeOut(tween(MotionBaseMs)),
                ) {
                    Column {
                        Divider()
                        SettingsRow(
                            label = "Secondary language",
                            value = secondaryLanguage?.displayName ?: "Select",
                            onClick = { showSecondaryPicker = true },
                        )
                    }
                }
                Divider()
                ToggleRow(
                    label = "External only",
                    helper = "Only translate external speech. Suppresses translation of your own voice and speaker playback.",
                    value = externalOnly,
                    onChange = {
                        externalOnly = it
                        onExternalOnlyChange(it)
                    },
                )
            }

            SectionHeader(
                kicker = "Output",
                headline = "Playback.",
                support = "Controls for how translated audio and text are delivered.",
            )

            FramedPanel {
                ToggleRow(
                    label = "Captions",
                    helper = "Stream translated text alongside audio.",
                    value = captionsEnabled,
                    onChange = {
                        captionsEnabled = it
                        onCaptionsEnabledChange(it)
                    },
                )
                Divider()
                ToggleRow(
                    label = "Prefer earbuds",
                    helper = "Warn when falling back to speaker mode — speaker + mic creates echo.",
                    value = preferEarbuds,
                    onChange = {
                        preferEarbuds = it
                        onPreferEarbudsChange(it)
                    },
                )
                Divider()
                ToggleRow(
                    label = "Notification controls",
                    helper = "Keep a start/stop toggle in the notification shade even when the translator is idle.",
                    value = persistentNotification,
                    onChange = {
                        persistentNotification = it
                        onPersistentNotificationChange(it)
                    },
                )
            }

            SectionHeader(
                kicker = "Help",
                headline = "Resources.",
                support = "User guide and onboarding walkthrough.",
            )

            FramedPanel {
                SettingsRow(
                    label = "User guide",
                    value = "Open",
                    onClick = onOpenHelp,
                )
                Divider()
                SettingsRow(
                    label = "View onboarding",
                    value = "Start",
                    onClick = onOpenOnboarding,
                )
            }

            SectionHeader(
                kicker = "Advanced",
                headline = "Diagnostics.",
                support = "Runtime metrics and session traces. Off by default.",
            )

            FramedPanel {
                ToggleRow(
                    label = "Enable diagnostics",
                    helper = "Collects time-to-first-audio, reconnects, underruns. Never leaves the device.",
                    value = diagnosticsEnabled,
                    onChange = {
                        diagnosticsEnabled = it
                        onDiagnosticsEnabledChange(it)
                    },
                )
                AnimatedVisibility(
                    visible = diagnosticsEnabled,
                    enter = expandVertically(tween(MotionBaseMs, easing = PreciseEasing)) + fadeIn(tween(MotionBaseMs)),
                    exit = shrinkVertically(tween(MotionBaseMs, easing = PreciseEasing)) + fadeOut(tween(MotionBaseMs)),
                ) {
                    Column {
                        Divider()
                        SettingsRow(
                            label = "Open diagnostics",
                            value = "View session",
                            onClick = onOpenDiagnostics,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsRow(
    label: String,
    value: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = EarslateTheme.textStyles.body,
            color = EarslateTheme.colors.textPrimary,
        )
        Text(
            text = value,
            style = EarslateTheme.textStyles.body,
            color = EarslateTheme.colors.textSecondary,
        )
    }
}

@Composable
private fun ToggleRow(
    label: String,
    helper: String,
    value: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!value) }
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Column(
            modifier = Modifier.padding(end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = label,
                style = EarslateTheme.textStyles.body,
                color = EarslateTheme.colors.textPrimary,
            )
            Text(
                text = helper,
                style = EarslateTheme.textStyles.bodySmall,
                color = EarslateTheme.colors.textTertiary,
            )
        }
        TogglePill(value = value)
    }
}

@Composable
private fun TogglePill(value: Boolean) {
    // Toggle pill — ember when on, surfaceSoft when off. Mono uppercase label
    // with the brand meta letter-spacing. No border.
    val animSpec = tween<androidx.compose.ui.graphics.Color>(MotionBaseMs, easing = PreciseEasing)
    val bg by animateColorAsState(
        targetValue = if (value) EarslateTheme.colors.ember else EarslateTheme.colors.surfaceSoft,
        animationSpec = animSpec,
        label = "toggle-bg",
    )
    val fg by animateColorAsState(
        targetValue = if (value) EarslateTheme.colors.onEmber else EarslateTheme.colors.creamSoft,
        animationSpec = animSpec,
        label = "toggle-fg",
    )
    val stateLabel = if (value) "ON" else "OFF"
    Box(
        modifier = Modifier
            .background(color = bg, shape = EarslateTheme.shapes.pill)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(
            text = stateLabel,
            style = EarslateTheme.textStyles.meta,
            color = fg,
        )
    }
}

@Composable
private fun Divider() {
    // One-edge separator, brand `--border-subtle` (cream @ 8% alpha).
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(color = EarslateTheme.colors.borderSubtle),
    )
}
