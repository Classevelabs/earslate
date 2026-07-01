package com.classeve.earslate.service

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.classeve.earslate.EarslateRuntime
import com.classeve.earslate.session.RuntimeState
import com.classeve.earslate.session.SupportedLanguages
import com.classeve.earslate.session.isActive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Lightweight foreground service that keeps a persistent notification in the
 * shade with start/stop controls. Stays alive even when the app is closed —
 * like a music player notification.
 *
 * Uses the SAME notification ID as [TranslatorService] (7_401) so the user
 * only ever sees ONE earslate notification. When TranslatorService is active,
 * both services share the notification — Android supports this officially.
 * When TranslatorService stops, this service keeps the notification alive and
 * updates it back to idle state with a Start button.
 *
 * The service is started when the user enables "Notification controls" in
 * Settings, and stopped when they disable it.
 */
class NotificationControlService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        NotificationFactory.ensureChannel(this)

        val notification = NotificationFactory.buildTranslatorNotification(
            this, RuntimeState.IDLE, currentLanguageName(),
        )

        // Persistent control notification WITHOUT a foreground service. A
        // dedicated FGS here would have to be FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        // — this service does no mic/media work itself, it only mirrors start/stop
        // controls — which Google Play scrutinises heavily. The notification is
        // ongoing and outlives this lightweight started service; when a session
        // actually runs, TranslatorService (microphone FGS) owns and updates the
        // shared notification. A plain NotificationManager post is the correct,
        // policy-clean way to keep idle controls in the shade.
        val manager = getSystemService(NotificationManager::class.java)
        if (manager == null) {
            stopSelf()
            return
        }
        // Guarded like the state-collect loop's notify() below — an OEM channel
        // quirk or a not-yet-fully-registered channel throwing here would
        // otherwise crash onCreate() (and this ran unguarded while the later
        // call site, doing the same thing, already had a runCatching).
        runCatching {
            manager.notify(NotificationFactory.NOTIFICATION_ID, notification)
        }.onFailure { Log.w(TAG, "initial notification post failed: ${it.message}") }

        stateJob = scope.launch {
            EarslateRuntime.stateStore.state.collect { state ->
                runCatching {
                    val manager = getSystemService(NotificationManager::class.java) ?: return@collect
                    manager.notify(
                        NotificationFactory.NOTIFICATION_ID,
                        NotificationFactory.buildTranslatorNotification(
                            this@NotificationControlService, state, currentLanguageName(),
                        ),
                    )
                }.onFailure { Log.w(TAG, "notification update failed: ${it.message}") }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        stateJob?.cancel()
        scope.cancel()
        // Remove the notification when the service is stopped (user disabled the setting).
        runCatching {
            val state = EarslateRuntime.stateStore.state.value
            if (!state.isActive) {
                getSystemService(NotificationManager::class.java)
                    ?.cancel(NotificationFactory.NOTIFICATION_ID)
            }
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun currentLanguageName(): String {
        return runCatching {
            val settings = EarslateRuntime.settingsRepository(this).settings.value
            SupportedLanguages
                .firstOrNull { it.bcp47 == settings.myLanguageBcp47 }
                ?.displayName ?: "English"
        }.getOrDefault("English")
    }

    companion object {
        private const val TAG = "NotifControl"

        fun start(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, NotificationControlService::class.java),
                )
            }.onFailure { Log.w(TAG, "start failed: ${it.message}") }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, NotificationControlService::class.java))
            }.onFailure { Log.w(TAG, "stop failed: ${it.message}") }
        }
    }
}
