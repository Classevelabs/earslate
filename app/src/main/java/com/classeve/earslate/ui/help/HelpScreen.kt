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
                    body = "The translator automatically detects the language being spoken and translates it into your chosen language in real-time.",
                )
                HelpEntry(
                    title = "Audio output",
                    body = "Translated speech plays through your earbuds or speaker as it arrives. There is a brief delay while the translation processes.",
                )
                HelpEntry(
                    title = "Captions",
                    body = "Enable captions in Settings to see translated text alongside the audio. Useful in noisy environments.",
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
                    body = "Bluetooth or wired earbuds give the best experience. They eliminate echo from the speaker feeding back into the microphone.",
                )
                HelpEntry(
                    title = "Speaker mode",
                    body = "Works but less reliable. The microphone may pick up translated audio and re-translate it. The app uses a playback gate to reduce this, but earbuds are always better.",
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
                HelpEntry(
                    title = "Conversation mode",
                    body = "Enable this in Settings for two-way translation. Your speech translates to a secondary language, and foreign speech translates to your language.",
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
                    body = "Check that microphone permission is granted. Make sure someone is speaking a language different from your target language. The translator stays silent when it hears your own language.",
                )
                HelpEntry(
                    title = "Echo or repeated translations",
                    body = "Switch to earbuds. If using speaker mode, enable External Only in Settings to suppress re-translation of the device\u2019s own playback.",
                )
                HelpEntry(
                    title = "High latency",
                    body = "Translation speed depends on your network connection. Wi-Fi typically gives lower latency than mobile data. earbuds also reduce processing overhead.",
                )
                HelpEntry(
                    title = "Connection drops",
                    body = "The translator automatically reconnects up to 4 times with increasing delays. If it fails, tap Start again. Check your internet connection.",
                )
            }
        }
    }
}

@Composable
private fun HelpEntry(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            style = EarslateTheme.textStyles.body,
            color = EarslateTheme.colors.textPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = body,
            style = EarslateTheme.textStyles.bodySmall,
            color = EarslateTheme.colors.textSecondary,
        )
    }
}
