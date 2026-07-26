package com.classeve.earslate

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import androidx.core.content.ContextCompat
import com.classeve.earslate.audio.AndroidAudioCaptureEngine
import com.classeve.earslate.audio.AndroidAudioPlaybackEngine
import com.classeve.earslate.audio.AudioCaptureEngine
import com.classeve.earslate.audio.AudioDeviceMonitor
import com.classeve.earslate.audio.AudioPlaybackEngine
import com.classeve.earslate.bootstrap.SessionBootstrapRepository
import com.classeve.earslate.bootstrap.AnonymousSessionBootstrapRepository
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

    @Volatile private var bootstrapRepo: SessionBootstrapRepository? = null

    /** Account-free broker client that returns only short-lived credentials. */
    fun bootstrapRepository(context: Context): SessionBootstrapRepository {
        return bootstrapRepo ?: synchronized(this) {
            bootstrapRepo ?: AnonymousSessionBootstrapRepository(context.applicationContext)
                .also { bootstrapRepo = it }
        }
    }

    // A FACTORY, not a singleton: the conversation translator opens one socket
    // per direction (up to two legs), so each session needs its own client.
    private val socketFactory: () -> LiveSocketClient = { OkHttpLiveSocketClient() }

    @Volatile private var captureEngine: AudioCaptureEngine? = null

    private fun captureEngine(context: Context): AudioCaptureEngine {
        val appContext = context.applicationContext
        return captureEngine ?: synchronized(this) {
            captureEngine ?: AndroidAudioCaptureEngine(
                framesPerBatch = 5, // 100 ms batches — the model's recommended chunk
                hasRecordAudioPermission = {
                    ContextCompat.checkSelfPermission(
                        appContext,
                        Manifest.permission.RECORD_AUDIO,
                    ) == PackageManager.PERMISSION_GRANTED
                },
            ).also { captureEngine = it }
        }
    }

    private val playbackEngine: AudioPlaybackEngine by lazy {
        AndroidAudioPlaybackEngine()
    }

    @Volatile private var sessionCoord: SessionCoordinator? = null

    fun sessionCoordinator(context: Context): SessionCoordinator {
        return sessionCoord ?: synchronized(this) {
            sessionCoord ?: SessionCoordinator(
                bootstrapRepository = bootstrapRepository(context),
                socketFactory = socketFactory,
                captureEngine = captureEngine(context),
                playbackEngine = playbackEngine,
                captionsStore = captionsStore,
                stateStore = stateStore,
                audioManager = context.applicationContext
                    .getSystemService(Context.AUDIO_SERVICE) as AudioManager,
                deviceMonitor = deviceMonitor(context),
            ).also { sessionCoord = it }
        }
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
