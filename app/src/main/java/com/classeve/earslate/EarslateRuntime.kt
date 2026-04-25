package com.classeve.earslate

import android.content.Context
import android.media.AudioManager
import com.classeve.earslate.audio.AndroidAudioCaptureEngine
import com.classeve.earslate.audio.AndroidAudioPlaybackEngine
import com.classeve.earslate.audio.AudioCaptureEngine
import com.classeve.earslate.audio.AudioDeviceMonitor
import com.classeve.earslate.audio.AudioPlaybackEngine
import com.classeve.earslate.audio.EnergyVadGate
import com.classeve.earslate.bootstrap.LocalDevBootstrapRepository
import com.classeve.earslate.bootstrap.RemoteBootstrapRepository
import com.classeve.earslate.bootstrap.SessionBootstrapRepository
import com.classeve.earslate.live.LiveSocketClient
import com.classeve.earslate.live.OkHttpLiveSocketClient
import com.classeve.earslate.network.TranslateUsageReporter
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

    /**
     * Release builds must route through the ClassEve Worker so the
     * long-lived Gemini key never ends up in a shipped APK. Debug builds
     * use the local-properties key path so developers can iterate
     * without network auth. The switch happens here, not in
     * SessionCoordinator, so the coordinator stays transport-agnostic.
     */
    fun bootstrapRepository(context: Context): SessionBootstrapRepository {
        return bootstrapRepo ?: synchronized(this) {
            bootstrapRepo ?: run {
                val repo: SessionBootstrapRepository = if (BuildConfig.DEBUG) {
                    LocalDevBootstrapRepository()
                } else {
                    RemoteBootstrapRepository(context.applicationContext)
                }
                bootstrapRepo = repo
                repo
            }
        }
    }

    @Volatile private var usageReporter: TranslateUsageReporter? = null

    /**
     * Live-session usage reporter. Returns null in debug builds where the
     * bootstrap repo is [LocalDevBootstrapRepository] — the worker would
     * 401 a heartbeat from a dev session anyway, and we don't want spurious
     * AUTH_REQUIRED bounces during local iteration.
     *
     * In release we keep a single process-wide instance so the
     * [TranslateUsageReporter.dailyLimitReached] flow can be observed by both
     * the coordinator and the UI.
     */
    fun translateUsageReporterOrNull(context: Context): TranslateUsageReporter? {
        if (BuildConfig.DEBUG) return null
        return usageReporter ?: synchronized(this) {
            usageReporter ?: run {
                val remote = bootstrapRepository(context) as? RemoteBootstrapRepository
                    ?: return@run null
                TranslateUsageReporter(
                    appContext = context.applicationContext,
                    bootstrap = remote,
                ).also { usageReporter = it }
            }
        }
    }

    private val socketClient: LiveSocketClient by lazy { OkHttpLiveSocketClient() }

    private val captureEngine: AudioCaptureEngine by lazy {
        AndroidAudioCaptureEngine(vadGate = EnergyVadGate())
    }

    private val playbackEngine: AudioPlaybackEngine by lazy {
        AndroidAudioPlaybackEngine()
    }

    @Volatile private var sessionCoord: SessionCoordinator? = null

    fun sessionCoordinator(context: Context): SessionCoordinator {
        return sessionCoord ?: synchronized(this) {
            sessionCoord ?: SessionCoordinator(
                bootstrapRepository = bootstrapRepository(context),
                socketClient = socketClient,
                captureEngine = captureEngine,
                playbackEngine = playbackEngine,
                captionsStore = captionsStore,
                stateStore = stateStore,
                audioManager = context.applicationContext
                    .getSystemService(Context.AUDIO_SERVICE) as AudioManager,
                deviceMonitor = deviceMonitor(context),
                usageReporter = translateUsageReporterOrNull(context),
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
