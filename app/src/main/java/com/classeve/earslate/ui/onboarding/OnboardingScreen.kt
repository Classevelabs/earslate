package com.classeve.earslate.ui.onboarding

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.classeve.earslate.session.TargetLanguage
import com.classeve.earslate.ui.components.EmberButton
import com.classeve.earslate.ui.components.FramedPanel
import com.classeve.earslate.ui.components.SectionHeader
import com.classeve.earslate.ui.settings.LanguagePickerDialog
import com.classeve.earslate.ui.theme.EarslateTheme
import com.classeve.earslate.ui.theme.MotionSlowMs
import com.classeve.earslate.ui.theme.PreciseEasing
import com.classeve.earslate.ui.theme.rememberReducedMotion

/**
 * First-run welcome. ClassEve brand v6 — matte ember, flat fills, no
 * gradient, no border, no glass.
 *
 * Short, welcoming, and honest: what earslate does (live translated speech in
 * your ear + captions on screen), that it's free and private (no account, no
 * no account), then the practical bits (mic, provider processing,
 * notification, audio disclosure). Flows straight into the main translator
 * screen via [onContinue] — there is no sign-in anywhere.
 *
 * Sections stagger in with a gentle reveal; the reveal is skipped entirely
 * when system animations are off (accessibility "remove animations").
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
            Reveal(index = 0) {
                Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    Text(
                        text = "EARSLATE / WELCOME",
                        style = EarslateTheme.textStyles.meta,
                        color = EarslateTheme.colors.textTertiary,
                    )
                    SectionHeader(
                        kicker = "Welcome",
                        headline = "Hear any language\nin yours.",
                        support = "earslate listens to the speech around you and plays it " +
                            "back translated, in real time — in your ear through earbuds, " +
                            "with live captions on screen.",
                    )
                }
            }

            Reveal(index = 1) {
                FramedPanel {
                    // Both of these described the 0.3.x broker product and were
                    // still on the first screen of the shipping app at 0.4.4.
                    // "no API key setup" was the exact opposite of the truth —
                    // the very next screen asks for a key — and the credential
                    // claim named a ClassEve server that was deleted in 0.4.0.
                    // Found by running the release APK, not by reading it.
                    ValueProp(
                        title = "Free, on your own key",
                        body = "No price, no subscription, no account. You bring a Gemini or " +
                            "OpenAI key, and sessions bill to that account at your provider's rates.",
                    )
                    ValueProp(
                        title = "Private",
                        body = "Audio goes straight from your phone to the provider you chose. " +
                            "There is no ClassEve server in the path — your key opens the session " +
                            "from the device itself, and we never receive or store your audio.",
                    )
                    ValueProp(
                        title = "Live",
                        body = "Both directions of a conversation, translated as it happens. " +
                            "Earbuds in, tap start, talk like normal.",
                    )
                }
            }

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

            Reveal(index = 2) {
                FramedPanel {
                    SectionHeader(
                        kicker = "First things first",
                        headline = "Choose your language.",
                        support = "The language you want to hear and read.",
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 56.dp)
                            .background(
                                color = EarslateTheme.colors.elev2,
                                shape = EarslateTheme.shapes.lg,
                            )
                            .clickable(
                                onClick = { showPicker = true },
                                onClickLabel = "Change your language",
                            )
                            .padding(horizontal = 18.dp, vertical = 16.dp)
                            .semantics(mergeDescendants = true) {
                                role = Role.Button
                                contentDescription =
                                    "Your language: ${selectedLanguage.displayName}"
                            },
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
            }

            Reveal(index = 3) {
                FramedPanel {
                    OnboardingStep(
                        index = "01",
                        title = "Nothing else to set",
                        body = "earslate works out what the other person is speaking and " +
                            "answers in it. Your provider is picked automatically from the " +
                            "key you save, and both can be changed later in Settings.",
                    )
                    OnboardingStep(
                        index = "02",
                        title = "Microphone access",
                        body = "earslate listens through your microphone and streams that " +
                            "audio to Gemini or OpenAI to translate it in real time. " +
                            "Android asks for mic access once, and you'll confirm the audio " +
                            "handling before the first session.",
                    )
                    OnboardingStep(
                        index = "03",
                        title = "Notifications",
                        body = "A persistent notification keeps the translator alive while " +
                            "the screen is off — Android requires this for microphone use " +
                            "in the background.",
                    )
                    OnboardingStep(
                        index = "04",
                        title = "Earbuds recommended",
                        body = "Bluetooth or wired earbuds dramatically reduce echo. Speaker " +
                            "mode works but is less reliable in noisy spaces.",
                    )
                }
            }

            // Prominent audio-egress disclosure (Google Play User Data policy).
            val context = LocalContext.current
            Reveal(index = 4) {
                FramedPanel {
                    SectionHeader(
                        kicker = "Your audio",
                        headline = "Sent to your provider to translate.",
                        support = "earslate streams captured audio over an encrypted connection " +
                            "directly to Gemini or OpenAI. The selected provider processes it " +
                            "under its terms. ClassEve never receives or stores the audio.",
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Read our privacy policy →",
                        style = EarslateTheme.textStyles.body,
                        color = EarslateTheme.colors.ember,
                        modifier = Modifier
                            .defaultMinSize(minHeight = 48.dp)
                            .clickable(
                                onClick = {
                                    runCatching {
                                        context.startActivity(
                                            Intent(
                                                Intent.ACTION_VIEW,
                                                Uri.parse("https://classeve.com/privacy"),
                                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                        )
                                    }
                                },
                                onClickLabel = "Open the privacy policy in your browser",
                            )
                            .semantics { role = Role.Button }
                            .padding(vertical = 12.dp),
                    )
                }
            }

            Reveal(index = 5) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    EmberButton(
                        label = "Continue",
                        onClick = onContinue,
                    )
                    Text(
                        text = "Next: save a provider key, then start listening.",
                        style = EarslateTheme.textStyles.bodySmall,
                        color = EarslateTheme.colors.textTertiary,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun ValueProp(title: String, body: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top,
        modifier = Modifier.semantics(mergeDescendants = true) {},
    ) {
        Box(
            modifier = Modifier
                .padding(top = 7.dp)
                .size(6.dp)
                .background(color = EarslateTheme.colors.ember, shape = CircleShape),
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
}

@Composable
private fun OnboardingStep(
    index: String,
    title: String,
    body: String,
) {
    // Each step uses the ember section-counter motif: ember mono index +
    // display-weight title + body copy.
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.semantics(mergeDescendants = true) {},
    ) {
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

/**
 * Staggered one-shot section reveal: fade + small upward slide, ~70ms apart.
 * Skipped entirely (content shown immediately) when system animations are
 * disabled.
 */
@Composable
private fun Reveal(index: Int, content: @Composable () -> Unit) {
    val reducedMotion = rememberReducedMotion()
    if (reducedMotion) {
        content()
        return
    }
    val visibleState = remember {
        MutableTransitionState(initialState = false).apply { targetState = true }
    }
    val delayMs = index * 70
    AnimatedVisibility(
        visibleState = visibleState,
        enter = fadeIn(
            tween(MotionSlowMs, delayMillis = delayMs, easing = PreciseEasing),
        ) + slideInVertically(
            animationSpec = tween(MotionSlowMs, delayMillis = delayMs, easing = PreciseEasing),
            initialOffsetY = { it / 12 },
        ),
    ) {
        content()
    }
}
