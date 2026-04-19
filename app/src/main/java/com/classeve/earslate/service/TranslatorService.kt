package com.classeve.earslate.service

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import com.classeve.earslate.EarslateRuntime
import com.classeve.earslate.session.RuntimeState
import com.classeve.earslate.session.SupportedLanguages
import com.classeve.earslate.session.isActive
import com.classeve.earslate.settings.toTranslatorPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that owns the live translator runtime lifecycle.
 *
 * Responsibilities:
 *   - promote to foreground with a microphone notification on first start
 *   - route ACTION_START / ACTION_STOP into the SessionCoordinator
 *   - mirror runtime state into the ongoing notification
 *   - demote + stopSelf when the coordinator transitions back to IDLE
 *
 * The service holds no business logic — it is a thin controller over
 * [EarslateRuntime.sessionCoordinator]. If the process is killed, Android
 * re-creates the service with START_STICKY and the runtime rebuilds from
 * scratch (no in-memory-only state assumed — blueprint §25 rule).
 */
class TranslatorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateJob: Job? = null
    private var sawActive = false

    override fun onCreate() {
        super.onCreate()
        NotificationFactory.ensureChannel(this)
        val notification = NotificationFactory.buildTranslatorNotification(
            this, RuntimeState.IDLE, currentLanguageName(),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NotificationFactory.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NotificationFactory.NOTIFICATION_ID, notification)
        }
        // Warm up the audio device monitor so the UI route indicator populates immediately.
        EarslateRuntime.deviceMonitor(this)

        stateJob = scope.launch {
            EarslateRuntime.stateStore.state.collect { state ->
                val manager = getSystemService(NotificationManager::class.java)
                manager?.notify(
                    NotificationFactory.NOTIFICATION_ID,
                    NotificationFactory.buildTranslatorNotification(
                        this@TranslatorService, state, currentLanguageName(),
                    ),
                )
                if (state.isActive) {
                    sawActive = true
                } else if (sawActive && state == RuntimeState.IDLE) {
                    Log.i(TAG, "runtime back to IDLE — stopping service")
                    stopForegroundSmart()
                    stopSelf()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val policy = EarslateRuntime.settingsRepository(this)
                    .settings.value
                    .toTranslatorPolicy()
                EarslateRuntime.sessionCoordinator(this).start(policy)
            }
            ACTION_STOP -> {
                val active = EarslateRuntime.stateStore.state.value.isActive
                if (active) {
                    EarslateRuntime.sessionCoordinator(this).stop()
                } else {
                    stopForegroundSmart()
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stateJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * If [NotificationControlService] is running (persistent notification enabled),
     * use DETACH so the shared notification stays alive for the control service.
     * Otherwise, REMOVE to clean up fully.
     */
    private fun stopForegroundSmart() {
        val controlEnabled = runCatching {
            EarslateRuntime.settingsRepository(this).settings.value.persistentNotification
        }.getOrDefault(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(
                if (controlEnabled) STOP_FOREGROUND_DETACH else STOP_FOREGROUND_REMOVE,
            )
        } else {
            @Suppress("DEPRECATION")
            stopForeground(!controlEnabled)
        }
    }

    /** Reads the current target language display name from persisted settings. */
    private fun currentLanguageName(): String {
        val settings = EarslateRuntime.settingsRepository(this).settings.value
        return SupportedLanguages
            .firstOrNull { it.bcp47 == settings.targetLanguageBcp47 }
            ?.displayName ?: "English"
    }

    companion object {
        private const val TAG = "TranslatorService"

        const val ACTION_START = "com.classeve.earslate.action.START"
        const val ACTION_STOP = "com.classeve.earslate.action.STOP"
        const val ACTION_MUTE = "com.classeve.earslate.action.MUTE"
        const val ACTION_UNMUTE = "com.classeve.earslate.action.UNMUTE"

        fun start(context: Context) {
            val intent = Intent(context, TranslatorService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, TranslatorService::class.java).setAction(ACTION_STOP)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
