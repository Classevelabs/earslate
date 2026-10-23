package com.classeve.earslate.update

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.classeve.earslate.R
import com.classeve.earslate.ui.components.EmberButton
import com.classeve.earslate.ui.theme.EarslateTheme

/** Every update earslate can show, over whatever screen is open. */
@Composable
fun UpdateLayer(play: PlayUpdates, site: SiteUpdates) {
    val playGate by play.gate.collectAsState()
    val siteGate by site.gate.collectAsState()
    val context = LocalContext.current
    val open = { url: String ->
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        Unit
    }
    val required = siteGate as? SiteGate.Required
    val offered = siteGate as? SiteGate.Available

    when {
        playGate == PlayGate.Required -> UpdateRequired(
            body = stringResource(R.string.update_required_play),
            action = stringResource(R.string.update_action_play),
            onUpdate = play::update,
        )
        required != null -> UpdateRequired(
            body = stringResource(R.string.update_required_site),
            action = stringResource(R.string.update_action_site),
            onUpdate = { open(required.url) },
        )
        playGate == PlayGate.Ready -> UpdatePrompt(
            text = stringResource(R.string.update_ready),
            action = stringResource(R.string.update_restart),
            onAction = play::restart,
            onLater = play::later,
        )
        offered != null -> UpdatePrompt(
            text = stringResource(R.string.update_available, offered.version),
            action = stringResource(R.string.update_get),
            onAction = { open(offered.url) },
            onLater = site::later,
        )
    }
}

/** Covers everything while a required update waits; back leaves the app instead of reaching what is underneath. */
@Composable
private fun UpdateRequired(body: String, action: String, onUpdate: () -> Unit) {
    val activity = LocalContext.current as? Activity
    BackHandler { activity?.finish() }
    val colors = EarslateTheme.colors
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.canvas)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .systemBarsPadding(),
    ) {
        // Scrolls like every other screen: the action row fills the height it is offered.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 40.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(text = stringResource(R.string.update_required_title), style = EarslateTheme.textStyles.h1, color = colors.textPrimary)
            Text(text = body, style = EarslateTheme.textStyles.body, color = colors.textSecondary)
            EmberButton(label = action, onClick = onUpdate, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

/** An update that is offered, not required: one card over the screen, gone with Later or a tap beside it. */
@Composable
private fun UpdatePrompt(text: String, action: String, onAction: () -> Unit, onLater: () -> Unit) {
    val colors = EarslateTheme.colors
    BackHandler(onBack = onLater)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.canvas.copy(alpha = 0.8f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onLater)
            .systemBarsPadding()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .background(colors.elev2, EarslateTheme.shapes.lg)
                .border(1.dp, colors.borderSubtle, EarslateTheme.shapes.lg)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(text = text, style = EarslateTheme.textStyles.body, color = colors.textPrimary)
            Row(
                modifier = Modifier.align(Alignment.End),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PromptAction(label = stringResource(R.string.update_later), primary = false, onClick = onLater)
                PromptAction(label = action, primary = true, onClick = onAction)
            }
        }
    }
}

@Composable
private fun PromptAction(label: String, primary: Boolean, onClick: () -> Unit) {
    val colors = EarslateTheme.colors
    Box(
        modifier = Modifier
            .clickable(onClick = onClick)
            .background(if (primary) colors.ember else colors.elev3, EarslateTheme.shapes.pill)
            .padding(horizontal = 18.dp, vertical = 12.dp),
    ) {
        Text(
            text = label,
            style = EarslateTheme.textStyles.meta.copy(fontWeight = FontWeight.SemiBold),
            color = if (primary) colors.onEmber else colors.cream,
        )
    }
}
