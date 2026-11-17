package com.classeve.earslate.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import com.classeve.earslate.EarslateRuntime
import com.classeve.earslate.R
import com.classeve.earslate.session.RuntimeState
import com.classeve.earslate.session.SupportedLanguages
import com.classeve.earslate.session.isActive
import com.classeve.earslate.ui.StartActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Quick Settings tile — one tap start/stop.
 *
 * Every public callback is wrapped in runCatching because TileService callbacks
 * run in unpredictable lifecycle states. A crash here kills the SystemUI
 * process on some OEMs — never acceptable.
 */
class TranslatorTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        // Cancel any previous observer to prevent scope leak if called twice
        // without onStopListening (observed on some OEMs).
        observer?.cancel()
        runCatching {
            refresh(EarslateRuntime.stateStore.state.value)
        }
        observer = scope.launch {
            EarslateRuntime.stateStore.state.collect { state ->
                runCatching { refresh(state) }
                    .onFailure { Log.w(TAG, "refresh failed: ${it.message}") }
            }
        }
    }

    override fun onStopListening() {
        observer?.cancel()
        observer = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        runCatching {
            val active = EarslateRuntime.stateStore.state.value.isActive
            if (active) {
                safeStop()
            } else {
                safeStart()
            }
        }.onFailure { Log.e(TAG, "onClick failed: ${it.message}", it) }
    }

    /**
     * Starts the translator through [StartActivity]. Started from here, the
     * service ran with no microphone (Android 11+: "can not have ... microphone
     * access") and translated silence; StartActivity also opens the app when the
     * permission or the consent is still missing.
     */
    private fun safeStart() {
        open(Intent(this, StartActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /**
     * Attempts to stop the translator. Catches all exceptions — a failed stop
     * is always better than a crashed SystemUI.
     */
    private fun safeStop() {
        try {
            TranslatorService.stop(this)
        } catch (e: Exception) {
            Log.w(TAG, "service stop failed: ${e.message}")
        }
    }

    /** Opens [intent]'s activity and closes the shade, the only way a tile may open one. */
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun open(intent: Intent) {
        if (Build.VERSION.SDK_INT >= 34) {
            val pi = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun refresh(state: RuntimeState) {
        val tile = qsTile ?: return
        tile.state = if (state.isActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.app_name)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_notification)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                val settings = EarslateRuntime.settingsRepository(this).settings.value
                tile.subtitle = SupportedLanguages
                    .firstOrNull { it.bcp47 == settings.myLanguageBcp47 }
                    ?.displayName ?: "English"
            }
        }
        tile.contentDescription = getString(R.string.app_tagline)
        runCatching { tile.updateTile() }
            .onFailure { Log.w(TAG, "updateTile failed: ${it.message}") }
    }

    companion object {
        private const val TAG = "TranslatorTile"
    }
}
