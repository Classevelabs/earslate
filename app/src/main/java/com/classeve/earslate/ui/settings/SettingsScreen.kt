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
 * Settings — ClassEve brand v6. Flat rows inside framed dock-planes, ember boxy
 * toggles.
 *
 * earslate decides both languages by listening, so there is nothing here to
 * configure for the thing the app actually does. What remains is the handful of
 * choices that cannot be worked out from the room: how the microphone behaves
 * while the translator is talking, whether the shade keeps a control, and whose
 * API key pays for the session.
 *
 * The language pickers are last and behind a switch on purpose. They were the
 * first thing on the main screen and they were a setup step in front of a
 * product whose whole point is not having one.
 */
@Composable
fun SettingsScreen(
    initialMyLanguage: TargetLanguage = TargetLanguage.EnglishUS,
    initialTheirLanguage: TargetLanguage = TargetLanguage.EnglishUS,
    initialManualLanguages: Boolean = false,
    initialExternalOnly: Boolean = false,
    initialPersistentNotification: Boolean = false,
    initialProvider: TranslationProvider = TranslationProvider.AUTOMATIC,
    onBack: () -> Unit,
    onMyLanguageChange: (TargetLanguage) -> Unit = {},
    onTheirLanguageChange: (TargetLanguage) -> Unit = {},
    onManualLanguagesChange: (Boolean) -> Unit = {},
    onExternalOnlyChange: (Boolean) -> Unit = {},
    onPersistentNotificationChange: (Boolean) -> Unit = {},
    onProviderChange: (TranslationProvider) -> Unit = {},
    onOpenOnboarding: () -> Unit = {},
    onOpenHelp: () -> Unit = {},
    onOpenKeySetup: () -> Unit = {},
    configuredKeySummary: String = "Not set up",
    padding: PaddingValues = PaddingValues(0.dp),
) {
    // Keyed on the incoming value, not remembered once.
    //
    // These are fed from the settings StateFlow, which is SEEDED WITH DEFAULTS
    // until DataStore's first disk read lands. A bare remember{} captures that
    // seed on the first composition and never looks again, so a screen opened
    // quickly after a cold start — the ordinary case after process death —
    // showed every row at its default: captions on, earbuds preferred, English.
    // The rows are the user's own settings misreported back to them, which is
    // worse than a spinner, because there is nothing to indicate it is wrong.
    //
    // Keying re-seeds each row when the real value arrives. A local edit is not
    // lost to it: every onChange writes through immediately, so the value that
    // comes back IS the edit.
    var myLanguage by remember(initialMyLanguage) { mutableStateOf(initialMyLanguage) }
    var theirLanguage by remember(initialTheirLanguage) { mutableStateOf(initialTheirLanguage) }
    var manualLanguages by remember(initialManualLanguages) { mutableStateOf(initialManualLanguages) }
    var externalOnly by remember(initialExternalOnly) { mutableStateOf(initialExternalOnly) }
    var persistentNotification by
        remember(initialPersistentNotification) { mutableStateOf(initialPersistentNotification) }
    var provider by remember(initialProvider) { mutableStateOf(initialProvider) }
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
                kicker = "Language",
                headline = "The one you speak.",
                support = "Everything said around you arrives in this. Theirs is worked out " +
                    "by listening, so there is nothing to set for them.",
            )

            FramedPanel {
                SettingsRow(
                    label = "Your language",
                    value = myLanguage.displayName,
                    onClick = { showMyPicker = true },
                    onClickLabel = "Change your language",
                )
            }

            SectionHeader(
                kicker = "Microphone",
                headline = "While it speaks.",
                support = "What the mic does while the translator is talking.",
            )

            FramedPanel {
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
                headline = "Pin their language.",
                support = "earslate hears which language is being spoken and answers in it. " +
                    "Turn this on only if you want to fix that side yourself.",
            )

            FramedPanel {
                ToggleRow(
                    label = "Choose languages manually",
                    helper = "Off, the other side follows whoever is speaking, and starts on English " +
                        "until something is recognised. On, it stays where you put it.",
                    value = manualLanguages,
                    onChange = {
                        manualLanguages = it
                        onManualLanguagesChange(it)
                    },
                )
                AnimatedVisibility(
                    visible = manualLanguages,
                    enter = expandVertically(tween(220)) + fadeIn(tween(220)),
                    exit = shrinkVertically(tween(220)) + fadeOut(tween(220)),
                ) {
                    Column {
                        Divider()
                        SettingsRow(
                            label = "Their language",
                            value = theirLanguage.displayName,
                            onClick = { showTheirPicker = true },
                            onClickLabel = "Change their language",
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
