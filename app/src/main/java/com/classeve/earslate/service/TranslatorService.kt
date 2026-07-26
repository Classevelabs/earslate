package com.classeve.earslate.service

import android.Manifest
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import com.classeve.earslate.EarslateRuntime
import com.classeve.earslate.ui.MainActivity
import com.classeve.earslate.session.RuntimeState
import com.classeve.earslate.session.SupportedLanguages
import com.classeve.earslate.session.isActive
import com.classeve.earslate.settings.OnboardingPrefs
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
 * [EarslateRuntime.sessionCoordinator]. It is deliberately NOT sticky: a
 * session only ever begins from an explicit user action, and the provider
 * credential is single-use and short-lived, so there is nothing meaningful to
 * resume after a process kill. See [onStartCommand].
 */
class TranslatorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateJob: Job? = null
    private var sawActive = false

    override fun onCreate() {
        super.onCreate()
        // RECORD_AUDIO can be revoked while the ongoing notification (or a QS tile)
        // still offers a "Start" action. On Android 14+ a microphone-typed
        // startForeground without the permission throws SecurityException, which
        // would crash-loop the service under START_STICKY. Bounce to MainActivity
        // to re-request the permission and bail out cleanly instead.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            runCatching {
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .setAction(MainActivity.ACTION_REQUEST_START)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            stopSelf()
            return
        }
        NotificationFactory.ensureChannel(this)
        val notification = NotificationFactory.buildTranslatorNotification(
            this, RuntimeState.IDLE, currentLanguageName(),
        )
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NotificationFactory.NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                )
            } else {
                startForeground(NotificationFactory.NOTIFICATION_ID, notification)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "startForeground failed", t)
            stopSelf()
            return
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
                // Play Prominent Disclosure & Consent gate. MainActivity.requestStart()
                // is not the only entry point that can reach here — the QS tile
                // (TranslatorTileService.safeStart()) and the idle notification's
                // "Start" action both call TranslatorService.start() directly,
                // bypassing MainActivity entirely when RECORD_AUDIO is already
                // granted. This is the single choke point every ACTION_START passes
                // through before a capture session begins, so the consent check
                // belongs here, not just in the activity. If the user hasn't
                // accepted the audio-egress disclosure yet, don't start capture —
                // bounce to MainActivity (which owns the AlertDialog UI; a Service
                // has no window to show it in) so requestStart() can show the
                // disclosure and the user must explicitly agree before any mic
                // audio reaches Gemini.
                if (!OnboardingPrefs.isAudioDisclosureAccepted(this)) {
                    runCatching {
                        startActivity(
                            Intent(this, MainActivity::class.java)
                                .setAction(MainActivity.ACTION_REQUEST_START)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                    stopForegroundSmart()
                    stopSelf()
                    return START_NOT_STICKY
                }
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
        // NOT sticky. A session is always started by an explicit user action
        // (button, QS tile, notification), so there is nothing to resume
        // automatically. Under START_STICKY the system re-created this service
        // after a process kill with a null intent: no branch above ran, but
        // onCreate had already posted the microphone-typed foreground
        // notification, leaving a permanent "mic in use" notification attached
        // to a service that would never translate anything and never stop
        // itself (the IDLE watchdog in onCreate only fires once a session has
        // actually been seen active).
        return START_NOT_STICKY
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
            .firstOrNull { it.bcp47 == settings.myLanguageBcp47 }
            ?.displayName ?: "English"
    }

    companion object {
        private const val TAG = "TranslatorService"

        const val ACTION_START = "com.classeve.earslate.action.START"
        const val ACTION_STOP = "com.classeve.earslate.action.STOP"

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
