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
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.classeve.earslate.session.TargetLanguage
import com.classeve.earslate.session.TranslationProvider
import com.classeve.earslate.ui.components.BackRow
import com.classeve.earslate.ui.components.FramedPanel
import com.classeve.earslate.ui.components.SectionHeader
import com.classeve.earslate.ui.theme.EarslateTheme

/**
 * Settings — ClassEve brand v6. earslate is a bidirectional conversation
 * translator with NO modes: the only translation settings are the two
 * languages. Flat rows inside framed dock-planes, ember boxy toggles.
 */
@Composable
fun SettingsScreen(
    initialMyLanguage: TargetLanguage = TargetLanguage.EnglishUS,
    initialTheirLanguage: TargetLanguage = TargetLanguage.EnglishUS,
    initialCaptionsEnabled: Boolean = true,
    initialPreferEarbuds: Boolean = true,
    initialExternalOnly: Boolean = false,
    initialDiagnosticsEnabled: Boolean = false,
    initialPersistentNotification: Boolean = false,
    initialProvider: TranslationProvider = TranslationProvider.AUTOMATIC,
    onBack: () -> Unit,
    onMyLanguageChange: (TargetLanguage) -> Unit = {},
    onTheirLanguageChange: (TargetLanguage) -> Unit = {},
    onCaptionsEnabledChange: (Boolean) -> Unit = {},
    onPreferEarbudsChange: (Boolean) -> Unit = {},
    onExternalOnlyChange: (Boolean) -> Unit = {},
    onDiagnosticsEnabledChange: (Boolean) -> Unit = {},
    onPersistentNotificationChange: (Boolean) -> Unit = {},
    onProviderChange: (TranslationProvider) -> Unit = {},
    onOpenDiagnostics: () -> Unit = {},
    onOpenOnboarding: () -> Unit = {},
    onOpenHelp: () -> Unit = {},
    onOpenKeySetup: () -> Unit = {},
    configuredKeySummary: String = "Not set up",
    padding: PaddingValues = PaddingValues(0.dp),
) {
    var myLanguage by remember { mutableStateOf(initialMyLanguage) }
    var theirLanguage by remember { mutableStateOf(initialTheirLanguage) }
    var captionsEnabled by remember { mutableStateOf(initialCaptionsEnabled) }
    var preferEarbuds by remember { mutableStateOf(initialPreferEarbuds) }
    var externalOnly by remember { mutableStateOf(initialExternalOnly) }
    var diagnosticsEnabled by remember { mutableStateOf(initialDiagnosticsEnabled) }
    var persistentNotification by remember { mutableStateOf(initialPersistentNotification) }
    var provider by remember { mutableStateOf(initialProvider) }
    var showMyPicker by remember { mutableStateOf(false) }
    var showTheirPicker by remember { mutableStateOf(false) }

    var showProviderDialog by remember { mutableStateOf(false) }

    if (showProviderDialog) {
        AlertDialog(
            onDismissRequest = { showProviderDialog = false },
            containerColor = EarslateTheme.colors.elev2,
            titleContentColor = EarslateTheme.colors.textPrimary,
            textContentColor = EarslateTheme.colors.textSecondary,
            title = { Text("Translation provider") },
            text = {
                Column {
                    TranslationProvider.entries.forEach { option ->
                        TextButton(
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics { selected = provider == option },
                            onClick = {
                                provider = option
                                onProviderChange(option)
                                showProviderDialog = false
                            },
                        ) {
                            Text(
                                text = option.displayName,
                                color = if (provider == option) {
                                    EarslateTheme.colors.ember
                                } else {
                                    EarslateTheme.colors.textPrimary
                                },
                            )
                        }
                    }
                }
            },
            confirmButton = {},
        )
    }

    if (showMyPicker) {
        LanguagePickerDialog(
            currentLanguage = myLanguage,
            onSelect = { selected ->
                myLanguage = selected
                onMyLanguageChange(selected)
                showMyPicker = false
            },
            onDismiss = { showMyPicker = false },
        )
    }

    if (showTheirPicker) {
        LanguagePickerDialog(
            currentLanguage = theirLanguage,
            onSelect = { selected ->
                theirLanguage = selected
                onTheirLanguageChange(selected)
                showTheirPicker = false
            },
            onDismiss = { showTheirPicker = false },
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
                kicker = "Languages",
                headline = "Conversation.",
                support = "Both directions translate automatically — each person hears the other in their own language.",
            )

            FramedPanel {
                SettingsRow(
                    label = "Your language",
                    value = myLanguage.displayName,
                    onClick = { showMyPicker = true },
                )
                Divider()
                SettingsRow(
                    label = "Their language",
                    value = theirLanguage.displayName,
                    onClick = { showTheirPicker = true },
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
                // The runtime has honoured this since the half-duplex gate was
                // written (SessionCoordinator.shouldGateMic) and the in-app help
                // told users to enable it, but no control ever existed to set
                // it — the only writer was a repository setter with no caller.
                // On speaker the gate is unconditional; this is the earbud
                // opt-in, which is why the helper says what it says.
                ToggleRow(
                    label = "External only",
                    helper = "Mute the microphone while the translator speaks, on earbuds too. On speaker this always happens.",
                    value = externalOnly,
                    onChange = {
                        externalOnly = it
                        onExternalOnlyChange(it)
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
                kicker = "Service",
                headline = "Translation provider.",
                support = "earslate runs on your own API key, billed to your own account. " +
                    "Automatic uses whichever provider you have a key for. Gemini translates " +
                    "both directions at once; OpenAI translates into one language at a time.",
            )

            FramedPanel {
                SettingsRow(
                    label = "Provider",
                    value = provider.displayName,
                    onClick = { showProviderDialog = true },
                    onClickLabel = "Choose translation provider",
                )
                Divider()
                SettingsRow(
                    label = "API keys",
                    value = configuredKeySummary,
                    onClick = onOpenKeySetup,
                    onClickLabel = "Manage API keys",
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
                    enter = expandVertically(tween(220)) + fadeIn(tween(220)),
                    exit = shrinkVertically(tween(220)) + fadeOut(tween(220)),
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
    onClickLabel: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .clickable(
                onClick = onClick,
                onClickLabel = onClickLabel,
                role = Role.Button,
            )
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
            .defaultMinSize(minHeight = 48.dp)
            // toggleable() (rather than clickable) gives TalkBack the switch
            // role plus a spoken "on/off" state and the correct toggle action.
            .toggleable(
                value = value,
                role = Role.Switch,
                onValueChange = onChange,
            )
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 16.dp),
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
    val bg by animateColorAsState(
        targetValue = if (value) EarslateTheme.colors.ember else EarslateTheme.colors.surfaceSoft,
        animationSpec = tween(220),
        label = "toggle-bg",
    )
    val fg by animateColorAsState(
        targetValue = if (value) EarslateTheme.colors.onEmber else EarslateTheme.colors.creamSoft,
        animationSpec = tween(220),
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
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(color = EarslateTheme.colors.borderSubtle),
    )
}
