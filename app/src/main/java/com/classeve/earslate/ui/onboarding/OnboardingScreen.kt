package com.classeve.earslate.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.classeve.earslate.session.TargetLanguage
import com.classeve.earslate.ui.components.FramedPanel
import com.classeve.earslate.ui.components.SectionHeader
import com.classeve.earslate.ui.settings.LanguagePickerDialog
import com.classeve.earslate.ui.theme.EarslateTheme

/**
 * First-run walkthrough. Blueprint §18.3:
 *   - choose native language (Step 0, prominent picker — user must not miss this)
 *   - mic + notification permissions rationale
 *   - Quick Settings tile hint
 *   - playback route test
 *
 * Single scrolling screen, no multi-step pager — less ceremony, easier to
 * understand, matches the Classeve "no fluff" design voice.
 */
@Composable
fun OnboardingScreen(
    onContinue: () -> Unit,
    initialLanguage: TargetLanguage = TargetLanguage.EnglishUS,
    onLanguageChange: (TargetLanguage) -> Unit = {},
    padding: PaddingValues = PaddingValues(0.dp),
) {
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
            Text(
                text = "EARSLATE",
                style = EarslateTheme.textStyles.kicker,
                color = EarslateTheme.colors.textTertiary,
            )

            SectionHeader(
                kicker = "Welcome",
                headline = "A translator that just\nlistens.",
                support = "Tap start. Hear nearby speech come back in your language. That's the whole product.",
            )

            // ── language picker ────────────────────────────────────────
            var selectedLanguage by remember(initialLanguage) { mutableStateOf(initialLanguage) }
            var showPicker by remember { mutableStateOf(false) }

            if (showPicker) {
                LanguagePickerDialog(
                    currentLanguage = selectedLanguage,
                    onSelect = { lang ->
                        selectedLanguage = lang
                        onLanguageChange(lang)
                        showPicker = false
                    },
                    onDismiss = { showPicker = false },
                )
            }

            FramedPanel {
                SectionHeader(
                    kicker = "Step 0",
                    headline = "Choose your language.",
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = EarslateTheme.colors.surfaceStrong,
                            shape = EarslateTheme.shapes.md,
                        )
                        .clickable { showPicker = true }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            text = selectedLanguage.displayName,
                            style = EarslateTheme.textStyles.h3,
                            color = EarslateTheme.colors.accent,
                        )
                        Text(
                            text = "Tap to change",
                            style = EarslateTheme.textStyles.kicker,
                            color = EarslateTheme.colors.textTertiary,
                        )
                    }
                    Text(
                        text = selectedLanguage.bcp47,
                        style = EarslateTheme.textStyles.bodyMuted,
                        color = EarslateTheme.colors.textSecondary,
                    )
                }
            }

            FramedPanel {
                OnboardingStep(
                    index = "01",
                    title = "Microphone access",
                    body = "earslate only translates what it can hear. Android will ask once; grant it and you never see the prompt again.",
                )
                OnboardingStep(
                    index = "02",
                    title = "Notifications",
                    body = "A persistent notification keeps the translator alive while the screen is off — Android requires this for microphone use in the background.",
                )
                OnboardingStep(
                    index = "03",
                    title = "Quick Settings tile",
                    body = "Swipe down twice from the top of your screen, tap the pencil/edit icon, then drag 'earslate' into your active tiles. One tap to start or stop from anywhere.",
                )
                OnboardingStep(
                    index = "04",
                    title = "Earbuds recommended",
                    body = "Bluetooth or wired earbuds dramatically reduce echo. Speaker mode works but is less reliable in noisy spaces.",
                )
            }

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = onContinue,
                colors = ButtonDefaults.buttonColors(
                    containerColor = EarslateTheme.colors.accent,
                    contentColor = EarslateTheme.colors.canvas,
                ),
                shape = EarslateTheme.shapes.md,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "Continue",
                    style = EarslateTheme.textStyles.body.copy(fontWeight = FontWeight.SemiBold),
                )
            }
        }
    }
}

@Composable
private fun OnboardingStep(
    index: String,
    title: String,
    body: String,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = index,
            style = EarslateTheme.textStyles.kicker,
            color = EarslateTheme.colors.textTertiary,
        )
        Text(
            text = title,
            style = EarslateTheme.textStyles.h3,
            color = EarslateTheme.colors.textPrimary,
        )
        Text(
            text = body,
            style = EarslateTheme.textStyles.bodyMuted,
            color = EarslateTheme.colors.textSecondary,
        )
    }
}
