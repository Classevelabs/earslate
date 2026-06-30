package com.classeve.earslate.ui.onboarding

import android.content.Intent
import android.net.Uri
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.classeve.earslate.session.TargetLanguage
import com.classeve.earslate.ui.components.FramedPanel
import com.classeve.earslate.ui.components.SectionHeader
import com.classeve.earslate.ui.settings.LanguagePickerDialog
import com.classeve.earslate.ui.theme.EarslateTheme

/**
 * First-run walkthrough. ClassEve brand v6 — matte ember, flat fills, no
 * gradient, no border, no glass.
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
                text = "EARSLATE / WELCOME",
                style = EarslateTheme.textStyles.meta,
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
                            color = EarslateTheme.colors.elev2,
                            shape = EarslateTheme.shapes.lg,
                        )
                        .clickable { showPicker = true }
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            text = selectedLanguage.displayName,
                            style = EarslateTheme.textStyles.h3,
                            color = EarslateTheme.colors.cream,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "Tap to change",
                            style = EarslateTheme.textStyles.meta,
                            color = EarslateTheme.colors.textTertiary,
                        )
                    }
                    // BCP-47 chip — pill, surfaceSoft, mono uppercase, creamSoft.
                    Box(
                        modifier = Modifier
                            .background(
                                color = EarslateTheme.colors.surfaceSoft,
                                shape = EarslateTheme.shapes.pill,
                            )
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = selectedLanguage.bcp47.uppercase(),
                            style = EarslateTheme.textStyles.meta,
                            color = EarslateTheme.colors.creamSoft,
                        )
                    }
                }
            }

            FramedPanel {
                OnboardingStep(
                    index = "01",
                    title = "Microphone access",
                    body = "earslate listens through your microphone and streams that audio to Google's Gemini service to translate it in real time. Android asks for mic access once, and you'll confirm the audio handling before the first session.",
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

            // Prominent audio-egress disclosure (Google Play User Data policy).
            val context = LocalContext.current
            FramedPanel {
                SectionHeader(
                    kicker = "Your audio",
                    headline = "Sent to Google to translate.",
                    support = "earslate streams the audio it captures to Google's Gemini service over an encrypted connection to translate it in real time. Google processes that audio and may retain it under Google's terms. earslate stores no audio of its own.",
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Read our privacy policy →",
                    style = EarslateTheme.textStyles.body,
                    color = EarslateTheme.colors.ember,
                    modifier = Modifier.clickable {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse("https://classeve.com/privacy"))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    },
                )
            }

            Spacer(Modifier.height(8.dp))

            // Continue — ember pill, onEmber text. Full-width primary CTA.
            Button(
                onClick = onContinue,
                colors = ButtonDefaults.buttonColors(
                    containerColor = EarslateTheme.colors.ember,
                    contentColor = EarslateTheme.colors.onEmber,
                ),
                shape = EarslateTheme.shapes.pill,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "CONTINUE",
                    style = EarslateTheme.textStyles.meta.copy(fontWeight = FontWeight.SemiBold),
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
    // Each step uses the ember section-counter motif: ember mono index +
    // display-weight title + body copy.
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = index,
            style = EarslateTheme.textStyles.meta,
            color = EarslateTheme.colors.ember,
        )
        Text(
            text = title,
            style = EarslateTheme.textStyles.h3,
            color = EarslateTheme.colors.textPrimary,
        )
        Text(
            text = body,
            style = EarslateTheme.textStyles.body,
            color = EarslateTheme.colors.textSecondary,
        )
    }
}
