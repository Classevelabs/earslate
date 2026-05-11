package com.classeve.earslate.ui.diagnostics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.classeve.earslate.EarslateRuntime
import com.classeve.earslate.session.RuntimeSnapshot
import com.classeve.earslate.ui.components.BackRow
import com.classeve.earslate.ui.components.FramedPanel
import com.classeve.earslate.ui.components.SectionHeader
import com.classeve.earslate.ui.theme.EarslateTheme

/**
 * Local, debug-only diagnostics screen. Blueprint §31. No network — every value
 * is derived from the in-memory [RuntimeStateStore] snapshot and never leaves
 * the device.
 */
@Composable
fun DiagnosticsScreen(
    onBack: () -> Unit,
    padding: PaddingValues = PaddingValues(0.dp),
) {
    val snapshot: RuntimeSnapshot by EarslateRuntime.stateStore.metrics.collectAsState()
    val state by EarslateRuntime.stateStore.state.collectAsState()

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
                kicker = "Diagnostics",
                headline = "Current session.",
                support = "Everything here stays on the device. Nothing is logged externally.",
            )

            FramedPanel {
                StatRow("State", state.name)
                StatRow(
                    "Time to first audio",
                    snapshot.timeToFirstAudioMs?.let { "$it ms" } ?: "—",
                )
                StatRow("Reconnects", snapshot.reconnectCount.toString())
                StatRow("Resume successes", snapshot.resumeSuccessCount.toString())
                StatRow("Playback underruns", snapshot.playbackUnderrunCount.toString())
                StatRow(
                    "Last send batch",
                    snapshot.lastSendBatchMs?.let { "$it ms" } ?: "—",
                )
            }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    // Label uses the meta-label motif (mono, uppercase, +0.12em tracking,
    // textTertiary). Value renders in mono for that classeve "tickers" feel.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label.uppercase(),
            style = EarslateTheme.textStyles.meta,
            color = EarslateTheme.colors.textTertiary,
        )
        Text(
            text = value,
            style = EarslateTheme.textStyles.meta,
            color = EarslateTheme.colors.textPrimary,
        )
    }
}
