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
import com.classeve.earslate.session.RuntimeError
import com.classeve.earslate.session.RuntimeState
import com.classeve.earslate.session.SupportedLanguages
import com.classeve.earslate.settings.OnboardingPrefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * Foreground service that owns the live translator runtime lifecycle.
 *
 * Responsibilities:
 *   - promote to foreground with a microphone notification for every start
 *   - route ACTION_START / ACTION_STOP into the SessionCoordinator
 *   - mirror runtime state into the ongoing notification
 *   - demote + stop once no command is left for it to act on
 *
 * The service holds no business logic — it is a thin controller over
 * [EarslateRuntime.sessionCoordinator]. It is deliberately NOT sticky: a
 * session only ever begins from an explicit user action, and the provider
 * credential is short-lived and held only in memory, so there is nothing
 * meaningful to resume after a process kill. See [onStartCommand].
 */
class TranslatorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateJob: Job? = null

    /**
     * The in-flight settings read for a pending ACTION_START, so an ACTION_STOP
     * arriving first can cancel it instead of being overtaken by it.
     */
    private var startJob: Job? = null

    private val lifetime = ServiceLifetime()

    override fun onCreate() {
        super.onCreate()
        NotificationFactory.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lifetime.received(startId)
        when (intent?.action) {
            ACTION_START -> begin(startId)
            ACTION_STOP -> end()
            else -> if (lifetime.free) finishIfIdle()
        }
        // NOT sticky. A session is always started by an explicit user action
        // (button, QS tile, notification), so there is nothing to resume
        // automatically. Under START_STICKY the system re-created this service
        // after a process kill with a null intent and a microphone notification
        // for a session that would never run.
        return START_NOT_STICKY
    }

    private fun begin(startId: Int) {
        // Promoted before anything else, whatever follows: a service started for the
        // foreground that stops without promoting itself takes the whole app down.
        if (!promote() || !hasMicrophone(this)) {
            ask()
            if (lifetime.free) finish()
            return
        }
        // Play Prominent Disclosure & Consent gate. The QS tile and the
        // notification's Start check it before they come here, and the main
        // screen asks before it does; this is the one place every start passes
        // through, so no microphone audio reaches a provider without consent.
        // The consent dialog needs a window, which a service does not have.
        if (!OnboardingPrefs.isAudioDisclosureAccepted(this)) {
            ask()
            if (lifetime.free) finish()
            return
        }
        lifetime.opening(startId)
        // The policy is AWAITED, not read from settings.value.
        //
        // This service is reachable with a cold process — the QS tile and
        // the notification's Start action both call it directly — and the
        // settings StateFlow is seeded with the default myLanguage of
        // "en-US" until DataStore's first disk read lands. Reading .value
        // here would build a session that translates into English rather
        // than the user's real language, connected and listening and
        // producing the wrong output, with nothing reporting an error.
        startJob?.cancel()
        startJob = scope.launch {
            // Reading from disk can fail where reading a StateFlow could
            // not. Starting anyway is not the fallback: a policy built from
            // defaults is the exact defect this await exists to prevent. So it
            // says so and stops.
            val policy = try {
                EarslateRuntime.settingsRepository(this@TranslatorService).translatorPolicy()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                Log.e(TAG, "could not read settings", t)
                EarslateRuntime.stateStore.setError(
                    RuntimeError(
                        kind = RuntimeError.Kind.UNKNOWN,
                        message = "Could not read your settings. Try again.",
                    ),
                )
                if (lifetime.ended(startId)) finishIfIdle()
                return@launch
            }
            val coordinator = EarslateRuntime.sessionCoordinator(this@TranslatorService)
            // A session still closing after a stop finishes first; a start made in
            // that moment was otherwise taken for the session being closed.
            coordinator.awaitClosed()
            // A STOP can arrive while that read is in flight; without this the
            // session would start immediately after being stopped.
            coroutineContext.ensureActive()
            // The service lasts exactly as long as the session. Watching the
            // state for IDLE is not enough: a session that fails at once goes
            // there and back between two looks, and the microphone notification
            // would then never be taken down.
            coordinator.start(policy).invokeOnCompletion {
                scope.launch { if (lifetime.ended(startId)) finishIfIdle() }
            }
        }
    }

    private fun end() {
        startJob?.cancel()
        startJob = null
        lifetime.stopped()
        // Ask the coordinator, which owns the session, rather than the state
        // store, which only mirrors it. Its teardown counts: a stop arriving in it
        // otherwise found nothing running and left the service up for good.
        val coordinator = EarslateRuntime.sessionCoordinator(this)
        coordinator.stop()
        val closing = coordinator.lastSession
        if (closing == null) {
            finishIfIdle()
        } else {
            closing.invokeOnCompletion { scope.launch { if (lifetime.free) finishIfIdle() } }
        }
    }

    /**
     * Into the foreground with the microphone type, as Android requires before
     * capture. False when Android refuses: no microphone permission (Android 14+)
     * or a start it does not allow from where the app is.
     */
    private fun promote(): Boolean {
        val notification = NotificationFactory.buildTranslatorNotification(
            this, EarslateRuntime.stateStore.state.value, currentLanguageName(),
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
        } catch (t: Exception) {
            Log.e(TAG, "startForeground failed", t)
            return false
        }
        if (stateJob == null) {
            stateJob = scope.launch {
                EarslateRuntime.stateStore.state.collect { state ->
                    // Guarded like NotificationControlService's: an OEM channel quirk
                    // here would otherwise end the app in the middle of a session.
                    runCatching {
                        getSystemService(NotificationManager::class.java)?.notify(
                            NotificationFactory.NOTIFICATION_ID,
                            NotificationFactory.buildTranslatorNotification(
                                this@TranslatorService, state, currentLanguageName(),
                            ),
                        )
                    }.onFailure { Log.w(TAG, "notification update failed: ${it.message}") }
                }
            }
        }
        // Warm up the audio device monitor so the UI route indicator populates immediately.
        EarslateRuntime.deviceMonitor(this)
        return true
    }

    /** The app asks for what a service cannot: the microphone permission, or the consent. */
    private fun ask() {
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .setAction(MainActivity.ACTION_REQUEST_START)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    private fun finishIfIdle() {
        if (EarslateRuntime.stateStore.state.value == RuntimeState.IDLE) finish()
    }

    // By the newest command's id: a command that arrived after it keeps the service.
    private fun finish() {
        stopForegroundSmart()
        stopSelfResult(lifetime.newest)
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
        stopForeground(if (controlEnabled) STOP_FOREGROUND_DETACH else STOP_FOREGROUND_REMOVE)
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

        fun hasMicrophone(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

        /**
         * True when a start from outside the app can go straight to the service.
         * Otherwise the app has to ask first, and a service started from a
         * notification or a tile may not open it (Android 12+): such a tap did nothing.
         */
        fun readyToStart(context: Context): Boolean =
            hasMicrophone(context) && OnboardingPrefs.isAudioDisclosureAccepted(context)

        fun start(context: Context) {
            val intent = Intent(context, TranslatorService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            // A plain start, never a foreground one: there is no microphone to promote
            // for, and a foreground start that ends unpromoted brings the app down.
            val sent = runCatching {
                context.startService(Intent(context, TranslatorService::class.java).setAction(ACTION_STOP))
            }.isSuccess
            // Refused only in the background, where no service of ours is running.
            if (!sent) EarslateRuntime.sessionCoordinator(context).stop()
        }
    }
}
