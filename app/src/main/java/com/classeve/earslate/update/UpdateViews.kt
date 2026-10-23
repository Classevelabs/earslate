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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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

    Box(Modifier.fillMaxSize()) {
        when {
            playGate == PlayGate.Required -> UpdateRequired(
                body = stringResource(R.string.update_required_play),
                action = stringResource(R.string.update_action_play),
                onUpdate = play::update,
            )
            siteGate is SiteGate.Required -> UpdateRequired(
                body = stringResource(R.string.update_required_site),
                action = stringResource(R.string.update_action_site),
                onUpdate = { open((siteGate as SiteGate.Required).url) },
            )
            playGate == PlayGate.Ready -> UpdateBar(
                text = stringResource(R.string.update_ready),
                action = stringResource(R.string.update_restart),
                onAction = play::restart,
                onLater = play::later,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
            siteGate is SiteGate.Available -> {
                val offered = siteGate as SiteGate.Available
                UpdateBar(
                    text = stringResource(R.string.update_available, offered.version),
                    action = stringResource(R.string.update_get),
                    onAction = { open(offered.url) },
                    onLater = site::later,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
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
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 420.dp).padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(text = stringResource(R.string.update_required_title), style = EarslateTheme.textStyles.h2, color = colors.textPrimary)
            Text(text = body, style = EarslateTheme.textStyles.body, color = colors.textSecondary)
            EmberButton(label = action, onClick = onUpdate, modifier = Modifier.padding(top = 10.dp))
        }
    }
}

@Composable
private fun UpdateBar(text: String, action: String, onAction: () -> Unit, onLater: () -> Unit, modifier: Modifier = Modifier) {
    val colors = EarslateTheme.colors
    Row(
        modifier = modifier
            .navigationBarsPadding()
            .padding(16.dp)
            .fillMaxWidth()
            .background(colors.elev2, EarslateTheme.shapes.lg)
            .border(1.dp, colors.borderSubtle, EarslateTheme.shapes.lg)
            .padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(text = text, style = EarslateTheme.textStyles.bodySmall, color = colors.textPrimary, modifier = Modifier.weight(1f))
        BarAction(label = stringResource(R.string.update_later), primary = false, onClick = onLater)
        BarAction(label = action, primary = true, onClick = onAction)
    }
}

@Composable
private fun BarAction(label: String, primary: Boolean, onClick: () -> Unit) {
    val colors = EarslateTheme.colors
    Box(
        modifier = Modifier
            .clickable(onClick = onClick)
            .background(if (primary) colors.ember else colors.elev2, EarslateTheme.shapes.pill)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = label,
            style = EarslateTheme.textStyles.meta.copy(fontWeight = FontWeight.SemiBold),
            color = if (primary) colors.onEmber else colors.cream,
        )
    }
}
