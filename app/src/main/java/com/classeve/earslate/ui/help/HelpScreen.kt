package com.classeve.earslate.ui.help

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.classeve.earslate.ui.components.BackRow
import com.classeve.earslate.ui.components.FramedPanel
import com.classeve.earslate.ui.components.SectionHeader
import com.classeve.earslate.ui.theme.EarslateTheme

/**
 * In-app user guide. Accessible from Settings. Static content — no networking,
 * no state. Answers the questions users actually ask after first launch.
 */
@Composable
fun HelpScreen(
    onBack: () -> Unit,
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
            BackRow(onBack = onBack)

            SectionHeader(
                kicker = "Guide",
                headline = "How earslate works.",
                support = "Everything you need to know in one page.",
            )

            /* ---- Section 1: How it works ---- */

            FramedPanel {
                HelpEntry(
                    title = "Start a session",
                    body = "Tap the Start button or use the Quick Settings tile. earslate begins listening to ambient speech through your microphone.",
                )
                HelpEntry(
                    title = "Automatic detection",
                    body = "The translator works out which language is being spoken and puts it in yours as it happens. There is nothing to choose.",
                )
                HelpEntry(
                    title = "Speaking back",
                    body = "What you say goes out in the language the other person was last heard speaking. Until they have spoken, nothing is said for you; to speak first, tap THEY SPEAK on the main screen and choose their language.",
                )
                HelpEntry(
                    title = "Audio output",
                    body = "In earbuds, translated speech plays while the other person is still talking, a few seconds behind them. On the phone's speaker it plays when they pause.",
                )
                HelpEntry(
                    title = "Captions",
                    body = "The conversation appears under the button as it is spoken: what they said on the left, what you said on the right. Tap a caption to copy it, or COPY ALL for the whole conversation.",
                )
            }

            /* ---- Section 2: Earbuds vs speaker ---- */

            SectionHeader(
                kicker = "Audio",
                headline = "Earbuds vs speaker.",
            )

            FramedPanel {
                HelpEntry(
                    title = "Earbuds recommended",
                    body = "Bluetooth or wired earbuds give the best experience: you hear the translation while the other person is still speaking.",
                )
                HelpEntry(
                    title = "Speaker mode",
                    body = "The phone waits until the person pauses, then speaks the translation. The microphone is closed while the phone is speaking, so let it finish before you talk.",
                )
            }

            /* ---- Section 3: Quick access ---- */

            SectionHeader(
                kicker = "Controls",
                headline = "Quick access.",
            )

            FramedPanel {
                HelpEntry(
                    title = "Quick Settings tile",
                    body = "Swipe down twice from the top of your screen, tap the pencil icon, and drag the earslate tile into your active tiles. One tap to start or stop from anywhere.",
                )
                HelpEntry(
                    title = "Notification controls",
                    body = "Enable notification controls in Settings to keep a start/stop toggle in the notification shade even when the app is in the background.",
                )
                // There is no "Conversation mode" setting, and there never can
                // be: the product is ALWAYS bidirectional (see TranslatorPolicy
                // — "there are no modes"). This entry told users to go and
                // enable something Settings does not contain, which reads as a
                // broken app rather than as stale help.
                HelpEntry(
                    title = "Two-way translation",
                    body = "Always on, and both directions are decided by listening — foreign speech arrives in your language, and yours goes out in theirs. If you would rather fix their side yourself, set Other language in Settings.",
                )
                HelpEntry(
                    title = "Changing your own language",
                    body = "Settings → Language, at the top. It is the only language earslate cannot work out on its own, so it is the only one it asks about.",
                )
            }

            /* ---- Section 4: Common issues ---- */

            SectionHeader(
                kicker = "Troubleshooting",
                headline = "Common issues.",
            )

            FramedPanel {
                HelpEntry(
                    title = "No translation happening",
                    body = "Check that microphone permission is granted, and that someone is speaking a language other than yours. Your own speech is never repeated back to you — it goes out in the language the other person was last heard speaking, so until somebody else has been heard there is nothing for it to go out in.",
                )
                HelpEntry(
                    title = "Echo or repeated translations",
                    body = "Your own words are never played back to you. A separate Bluetooth speaker is the exception: the microphone can hear it and translate the translation. Use earbuds, or the phone's own speaker.",
                )
                HelpEntry(
                    title = "High latency",
                    body = "The translation follows the speaker by a few seconds, because the model waits to hear enough of a sentence to translate it. A weak connection adds to that.",
                )
                HelpEntry(
                    title = "Connection drops",
                    body = "The translator reconnects by itself, with increasing delays. If it gives up, check your internet connection and tap Start again.",
                )
            }
        }
    }
}

@Composable
private fun HelpEntry(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            style = EarslateTheme.textStyles.h3,
            color = EarslateTheme.colors.textPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = body,
            style = EarslateTheme.textStyles.body,
            color = EarslateTheme.colors.textSecondary,
        )
    }
}
