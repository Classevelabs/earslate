package com.classeve.earslate.ui.onboarding

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.animation.animateColorAsState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.foundation.selection.selectable
import com.classeve.earslate.session.SupportedLanguages
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
import com.classeve.earslate.ui.theme.MotionBaseMs
import com.classeve.earslate.ui.theme.MotionSlowMs
import com.classeve.earslate.ui.theme.PreciseEasing
import com.classeve.earslate.ui.theme.rememberReducedMotion

/**
 * First-run setup.
 *
 * This used to be one long scroll: two walls of prose, the language picker
 * somewhere in the middle, four policy notices, and a Continue button at the
 * bottom. Scrolling past the picker was the easy path and cost nothing visible,
 * so the language a user ended up with was whatever the device locale guessed —
 * a default wearing a choice's clothes. That mattered little when the main
 * screen still had pickers on it. It matters completely now that it does not:
 * the only remaining route to fix it is Settings → Advanced.
 *
 * So it is a setup, with steps, and the one question the app genuinely cannot
 * answer by listening is a step of its own. The other person's language is not
 * asked about anywhere, because asking would be the setup step the product
 * exists to remove.
 *
 * The third step is how to use it. An app that opens the microphone, streams
 * audio to a third party and speaks into someone's ear owes them a plain
 * account of what it is about to do, before it does it.
 */
private enum class SetupStep { WELCOME, LANGUAGE, HOW, KEY }

@Composable
fun OnboardingScreen(
    onContinue: () -> Unit,
    initialLanguage: TargetLanguage = TargetLanguage.EnglishUS,
    onLanguageChange: (TargetLanguage) -> Unit = {},
    /**
     * True when this is a re-read from Settings rather than a first run. The
     * last step is then a summary instead of a hand-off to key setup, because
     * there is nothing left to set up.
     */
    alreadySetUp: Boolean = false,
    /** Seeded from the device locale rather than picked, so the copy can say so. */
    languageWasGuessed: Boolean = true,
    padding: PaddingValues = PaddingValues(0.dp),
) {
    var step by rememberSaveable { mutableStateOf(SetupStep.WELCOME) }
    var selectedLanguage by remember(initialLanguage) { mutableStateOf(initialLanguage) }
    var picked by rememberSaveable { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }

    val order = SetupStep.entries
    val index = order.indexOf(step)

    // System back walks the steps rather than leaving setup from the middle of
    // it. On the first step there is nothing behind us, so it stays disabled and
    // back exits the app as it always did.
    BackHandler(enabled = index > 0) { step = order[index - 1] }

    if (showPicker) {
        LanguagePickerDialog(
            currentLanguage = selectedLanguage,
            onSelect = { lang ->
                selectedLanguage = lang
                picked = true
                onLanguageChange(lang)
                showPicker = false
            },
            onDismiss = { showPicker = false },
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
                .padding(horizontal = 24.dp, vertical = 40.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            StepBar(current = index, total = order.size)

            Crossfade(
                targetState = step,
                animationSpec = tween(MotionBaseMs, easing = PreciseEasing),
                label = "setup-step",
            ) { current ->
                Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    when (current) {
                        SetupStep.WELCOME -> WelcomeStep()
                        SetupStep.LANGUAGE -> LanguageStep(
                            language = selectedLanguage,
                            guessed = languageWasGuessed && !picked,
                            onPick = { showPicker = true },
                            onSelect = { lang ->
                                selectedLanguage = lang
                                picked = true
                                onLanguageChange(lang)
                            },
                        )
                        SetupStep.HOW -> HowStep(language = selectedLanguage)
                        SetupStep.KEY -> KeyStep(alreadySetUp = alreadySetUp)
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                EmberButton(
                    label = when (step) {
                        SetupStep.WELCOME -> "Set up earslate"
                        SetupStep.LANGUAGE -> "Continue"
                        SetupStep.HOW -> "Continue"
                        SetupStep.KEY -> if (alreadySetUp) "Done" else "Add my key"
                    },
                    onClick = {
                        if (step == SetupStep.KEY) {
                            // The language is written through on every pick, but
                            // a user who agreed with the guess never picked. Save
                            // it here so what they were shown for three screens is
                            // what actually ends up stored.
                            onLanguageChange(selectedLanguage)
                            onContinue()
                        } else {
                            step = order[index + 1]
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (index > 0) {
                    EmberButton(
                        label = "Back",
                        onClick = { step = order[index - 1] },
                        modifier = Modifier.fillMaxWidth(),
                        primary = false,
                    )
                }
            }
        }
    }
}

@Composable
private fun StepBar(current: Int, total: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = "Setup step ${current + 1} of $total"
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "EARSLATE / SETUP",
            style = EarslateTheme.textStyles.meta,
            color = EarslateTheme.colors.textTertiary,
        )
        Spacer(Modifier.weight(1f))
        for (i in 0 until total) {
            val active = i <= current
            val width by animateDpAsState(
                targetValue = if (i == current) 20.dp else 6.dp,
                animationSpec = tween(MotionBaseMs, easing = PreciseEasing),
                label = "step-dot-$i",
            )
            Box(
                modifier = Modifier
                    .height(6.dp)
                    .width(width)
                    .background(
                        color = if (active) {
                            EarslateTheme.colors.ember
                        } else {
                            EarslateTheme.colors.surfaceSoft
                        },
                        shape = EarslateTheme.shapes.pill,
                    ),
            )
        }
    }
}

@Composable
private fun WelcomeStep() {
    Reveal(index = 0) {
        SectionHeader(
            kicker = "Welcome",
            headline = "Hear any language\nin yours.",
            support = "earslate listens to the speech around you and plays it back " +
                "translated, as it happens — in your ear through earbuds, with live " +
                "captions on screen.",
        )
    }
    Reveal(index = 1) {
        FramedPanel {
            // Both of these described the 0.3.x broker product and were still on
            // the first screen of the shipping app at 0.4.4. "no API key setup"
            // was the exact opposite of the truth — setup asks for one — and the
            // credential claim named a ClassEve server deleted in 0.4.0. Found by
            // running the release APK, not by reading it.
            ValueProp(
                title = "Free, on your own key",
                body = "No price, no subscription, no account. You bring a Gemini or " +
                    "OpenAI key, and sessions bill to that account at your provider's rates.",
            )
            ValueProp(
                title = "Private",
                body = "Audio goes straight from your phone to the provider you chose. " +
                    "There is no ClassEve server in the path, and we never receive or " +
                    "store your audio.",
            )
            ValueProp(
                title = "Nothing to configure",
                body = "Tell it which language you speak. It works the other person's " +
                    "out by listening, and answers them in it.",
            )
        }
    }
    Reveal(index = 2) {
        Text(
            text = "Three short steps, about a minute.",
            style = EarslateTheme.textStyles.bodySmall,
            color = EarslateTheme.colors.textTertiary,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LanguageStep(
    language: TargetLanguage,
    guessed: Boolean,
    onPick: () -> Unit,
    onSelect: (TargetLanguage) -> Unit,
) {
    SectionHeader(
        kicker = "Step 1 of 3",
        headline = "Which language\ndo you speak?",
        support = "Everything said around you arrives in this one. It is the only thing " +
            "earslate cannot work out by listening — the other person's language is " +
            "detected on its own, so there is nothing to set for them.",
    )
    // A field of soft pills, not a box with a value in it. The first draft put
    // the choice in a hard rectangle with the name and a code chip, which read
    // as a form to be filled rather than a thing to touch. The common
    // languages sit here in the open; the rest are one tap away.
    val shown = remember(language) {
        val top = SupportedLanguages.take(SHOWN_PILLS).toMutableList()
        if (top.none { it.bcp47 == language.bcp47 }) top[top.lastIndex] = language
        top
    }
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        for (option in shown) {
            LanguagePill(
                label = option.displayName,
                selected = option.bcp47 == language.bcp47,
                onClick = { onSelect(option) },
            )
        }
        LanguagePill(label = "More…", selected = false, onClick = onPick, quiet = true)
    }
    Text(
        text = if (guessed) {
            "${language.displayName} is a guess from your phone's language. Tap another if that is wrong — you can change it later in Settings."
        } else {
            "You can change this later in Settings."
        },
        style = EarslateTheme.textStyles.bodySmall,
        color = EarslateTheme.colors.textTertiary,
    )
}

private const val SHOWN_PILLS = 12

@Composable
private fun LanguagePill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    quiet: Boolean = false,
) {
    val round = RoundedCornerShape(50)
    val bg by animateColorAsState(
        targetValue = when {
            selected -> EarslateTheme.colors.ember
            quiet -> EarslateTheme.colors.canvas
            else -> EarslateTheme.colors.elev1
        },
        animationSpec = tween(MotionBaseMs, easing = PreciseEasing),
        label = "pill-bg",
    )
    val fg by animateColorAsState(
        targetValue = when {
            selected -> EarslateTheme.colors.onEmber
            quiet -> EarslateTheme.colors.textTertiary
            else -> EarslateTheme.colors.textPrimary
        },
        animationSpec = tween(MotionBaseMs, easing = PreciseEasing),
        label = "pill-fg",
    )
    Box(
        modifier = Modifier
            .defaultMinSize(minHeight = 44.dp)
            .background(color = bg, shape = round)
            .then(
                if (quiet) Modifier.border(1.dp, EarslateTheme.colors.borderSubtle, round) else Modifier,
            )
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = EarslateTheme.textStyles.body,
            color = fg,
        )
    }
}

@Composable
private fun HowStep(language: TargetLanguage) {
    val context = LocalContext.current
    SectionHeader(
        kicker = "Step 2 of 3",
        headline = "How to use it.",
        support = "Three things, and none of them happen while you are talking.",
    )
    FramedPanel {
        OnboardingStep(
            index = "01",
            title = "Put earbuds in",
            body = "Speaker works, but the microphone then hears the translation and " +
                "translates it again. Wired or Bluetooth earbuds remove that entirely.",
        )
        OnboardingStep(
            index = "02",
            title = "Tap START",
            body = "On the main screen, or from the Quick Settings tile. earslate begins " +
                "listening to everyone around you.",
        )
        OnboardingStep(
            index = "03",
            title = "Just talk",
            body = "Anything spoken in another language arrives in ${language.displayName}. " +
                "Anything you say goes out in whatever they were last heard speaking. " +
                "Nobody has to press anything to take a turn.",
        )
    }
    SectionHeader(
        kicker = "What it needs",
        headline = "And what it does with it.",
    )
    FramedPanel {
        ValueProp(
            title = "The microphone",
            body = "Android asks once. Without it there is nothing to translate.",
        )
        ValueProp(
            title = "A notification while it runs",
            body = "Android requires one for microphone use in the background. It doubles " +
                "as a stop button.",
        )
        ValueProp(
            title = "Your audio, sent to your provider",
            body = "Captured speech is streamed over an encrypted connection straight to " +
                "Gemini or OpenAI to be translated, under that provider's terms. You will " +
                "be asked to confirm this once, before the first session.",
        )
        Spacer(Modifier.height(4.dp))
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

@Composable
private fun KeyStep(alreadySetUp: Boolean) {
    if (alreadySetUp) {
        SectionHeader(
            kicker = "Step 3 of 3",
            headline = "You are set up.",
            support = "Your key is saved and your language is set. Earbuds in, tap START, " +
                "and talk.",
        )
        return
    }
    SectionHeader(
        kicker = "Step 3 of 3",
        headline = "One key, and\nyou're done.",
        support = "earslate has no servers of its own, so translation runs directly between " +
            "your phone and a provider you pay. Bring a Gemini or OpenAI key and sessions " +
            "bill to that account at their rates.",
    )
    FramedPanel {
        ValueProp(
            title = "It takes about a minute",
            body = "The next screen has the steps for whichever provider you pick, and a " +
                "button that opens the right console.",
        )
        ValueProp(
            title = "Checked before it is saved",
            body = "We open a real session with it first, so a bad key fails here rather " +
                "than in the middle of a conversation.",
        )
        ValueProp(
            title = "Sealed to this phone",
            body = "Stored in the hardware keystore, excluded from backups and device " +
                "transfer, and never sent to ClassEve.",
        )
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
