package com.classeve.earslate.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.classeve.earslate.session.TargetLanguage
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
    initialOtherLanguage: TargetLanguage? = null,
    initialPersistentNotification: Boolean = false,
    onBack: () -> Unit,
    onMyLanguageChange: (TargetLanguage) -> Unit = {},
    /** null resets the other language to Automatic. */
    onOtherLanguageChange: (TargetLanguage?) -> Unit = {},
    onPersistentNotificationChange: (Boolean) -> Unit = {},
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
    // showed every row at its default. The rows would then be the user's own
    // settings misreported back to them, which is worse than a spinner, because
    // there is nothing to indicate it is wrong.
    //
    // Keying re-seeds each row when the real value arrives. A local edit is not
    // lost to it: every onChange writes through immediately, so the value that
    // comes back IS the edit.
    var myLanguage by remember(initialMyLanguage) { mutableStateOf(initialMyLanguage) }
    var otherLanguage by remember(initialOtherLanguage) { mutableStateOf(initialOtherLanguage) }
    var persistentNotification by
        remember(initialPersistentNotification) { mutableStateOf(initialPersistentNotification) }
    var showMyPicker by remember { mutableStateOf(false) }
    var showOtherPicker by remember { mutableStateOf(false) }

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

    if (showOtherPicker) {
        LanguagePickerDialog(
            title = "Other language",
            currentLanguage = otherLanguage ?: myLanguage,
            onSelect = { selected ->
                otherLanguage = selected
                onOtherLanguageChange(selected)
                showOtherPicker = false
            },
            // Back to letting the app work it out from the conversation.
            onAutomatic = {
                otherLanguage = null
                onOtherLanguageChange(null)
                showOtherPicker = false
            },
            onDismiss = { showOtherPicker = false },
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
                headline = "Yours, and theirs.",
                support = "Everything said around you arrives in your language. The other " +
                    "language is worked out by listening — leave it on Automatic. Set it only " +
                    "if you want to be understood before the other person has spoken.",
            )

            FramedPanel {
                SettingsRow(
                    label = "Your language",
                    value = myLanguage.displayName,
                    onClick = { showMyPicker = true },
                    onClickLabel = "Change your language",
                )
                Divider()
                SettingsRow(
                    label = "Other language",
                    value = otherLanguage?.displayName ?: "Automatic",
                    onClick = { showOtherPicker = true },
                    onClickLabel = "Change the other language",
                )
            }

            SectionHeader(
                kicker = "Microphone",
                headline = "While it speaks.",
                support = "What the mic does while the translator is talking.",
            )

            FramedPanel {
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
                headline = "Your key.",
                support = "earslate runs on your own Google Gemini API key, billed to your own " +
                    "account. There is no ClassEve server in the path.",
            )

            FramedPanel {
                SettingsRow(
                    label = "API key",
                    value = configuredKeySummary,
                    onClick = onOpenKeySetup,
                    onClickLabel = "Manage API key",
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
