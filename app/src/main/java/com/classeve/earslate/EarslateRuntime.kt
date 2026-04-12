package com.classeve.earslate

import android.content.Context
import com.classeve.earslate.audio.AndroidAudioCaptureEngine
import com.classeve.earslate.audio.AndroidAudioPlaybackEngine
import com.classeve.earslate.audio.AudioCaptureEngine
import com.classeve.earslate.audio.AudioDeviceMonitor
import com.classeve.earslate.audio.AudioPlaybackEngine
import com.classeve.earslate.audio.EnergyVadGate
import com.classeve.earslate.bootstrap.LocalDevBootstrapRepository
import com.classeve.earslate.bootstrap.SessionBootstrapRepository
import com.classeve.earslate.live.LiveSocketClient
import com.classeve.earslate.live.OkHttpLiveSocketClient
import com.classeve.earslate.session.RuntimeStateStore
import com.classeve.earslate.session.SessionCoordinator
import com.classeve.earslate.settings.SettingsRepository
import com.classeve.earslate.settings.earslateDataStore
import com.classeve.earslate.ui.captions.CaptionsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Process-wide singletons. Not a DI container — a holder so UI, service, and
 * coordinator all reach the same instances without pulling in Hilt. Matches
 * Lven-Android's singleton-object convention.
 */
object EarslateRuntime {

    private val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val stateStore: RuntimeStateStore by lazy { RuntimeStateStore() }

    val captionsStore: CaptionsStore by lazy { CaptionsStore() }

    @Volatile private var settingsRepo: SettingsRepository? = null

    fun settingsRepository(context: Context): SettingsRepository {
        return settingsRepo ?: synchronized(this) {
            settingsRepo ?: SettingsRepository(
                dataStore = context.applicationContext.earslateDataStore,
                scope = processScope,
            ).also { settingsRepo = it }
        }
    }

    private val bootstrapRepository: SessionBootstrapRepository by lazy {
        // Task 8: release builds swap this for RemoteBootstrapRepository once
        // the ClassEve Worker /v1/earslate/bootstrap endpoint is deployed.
        LocalDevBootstrapRepository()
    }

    private val socketClient: LiveSocketClient by lazy { OkHttpLiveSocketClient() }

    private val captureEngine: AudioCaptureEngine by lazy {
        AndroidAudioCaptureEngine(vadGate = EnergyVadGate())
    }

    private val playbackEngine: AudioPlaybackEngine by lazy {
        AndroidAudioPlaybackEngine()
    }

    val sessionCoordinator: SessionCoordinator by lazy {
        SessionCoordinator(
            bootstrapRepository = bootstrapRepository,
            socketClient = socketClient,
            captureEngine = captureEngine,
            playbackEngine = playbackEngine,
            captionsStore = captionsStore,
            stateStore = stateStore,
        )
    }

    @Volatile private var deviceMonitor: AudioDeviceMonitor? = null

    fun deviceMonitor(context: Context): AudioDeviceMonitor {
        return deviceMonitor ?: synchronized(this) {
            deviceMonitor ?: AudioDeviceMonitor(context.applicationContext).also {
                deviceMonitor = it
                it.start()
            }
        }
    }
}
