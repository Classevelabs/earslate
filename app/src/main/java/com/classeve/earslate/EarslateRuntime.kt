package com.classeve.earslate

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import androidx.core.content.ContextCompat
import com.classeve.earslate.audio.AndroidAudioCaptureEngine
import com.classeve.earslate.audio.AndroidAudioPlaybackEngine
import com.classeve.earslate.audio.AudioCaptureEngine
import com.classeve.earslate.audio.AudioDeviceMonitor
import com.classeve.earslate.audio.AudioPlaybackEngine
import com.classeve.earslate.bootstrap.InstallationId
import com.classeve.earslate.bootstrap.LocalKeyBootstrapRepository
import com.classeve.earslate.bootstrap.ProviderKeyVerifier
import com.classeve.earslate.bootstrap.ProviderSessionMinter
import com.classeve.earslate.bootstrap.SessionCredentialSource
import com.classeve.earslate.live.LiveSocketClient
import com.classeve.earslate.live.OkHttpLiveSocketClient
import com.classeve.earslate.security.ProviderKeyStore
import com.classeve.earslate.session.AudioFocus
import com.classeve.earslate.session.RuntimeStateStore
import com.classeve.earslate.session.SessionCoordinator
import com.classeve.earslate.settings.SettingsRepository
import com.classeve.earslate.settings.earslateDataStore
import com.classeve.earslate.ui.captions.CaptionsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import okhttp3.OkHttpClient

/**
 * Process-wide singletons. Not a DI container — a holder so UI, service, and
 * coordinator all reach the same instances without pulling in Hilt. Matches
 * Lven-Android's singleton-object convention.
 */
object EarslateRuntime {

    private val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val stateStore: RuntimeStateStore by lazy { RuntimeStateStore() }

    val captionsStore: CaptionsStore by lazy { CaptionsStore() }

    // One client for every socket and every credential request, so they share
    // a connection pool and one set of threads.
    private val http: OkHttpClient by lazy { OkHttpLiveSocketClient.newHttpClient() }

    @Volatile private var settingsRepo: SettingsRepository? = null

    fun settingsRepository(context: Context): SettingsRepository {
        return settingsRepo ?: synchronized(this) {
            settingsRepo ?: SettingsRepository(
                dataStore = context.applicationContext.earslateDataStore,
                scope = processScope,
            ).also { settingsRepo = it }
        }
    }

    @Volatile private var keyStore: ProviderKeyStore? = null

    /** The user's own provider API keys, sealed by the platform keystore. */
    fun providerKeys(context: Context): ProviderKeyStore {
        return keyStore ?: synchronized(this) {
            keyStore ?: ProviderKeyStore(context.applicationContext).also { keyStore = it }
        }
    }

    @Volatile private var sessionMinter: ProviderSessionMinter? = null

    private fun minter(context: Context): ProviderSessionMinter {
        return sessionMinter ?: synchronized(this) {
            sessionMinter ?: ProviderSessionMinter(
                http = http,
                installId = InstallationId.loadOrCreate(context.applicationContext),
            ).also { sessionMinter = it }
        }
    }

    // A factory: every direction of every session is its own socket.
    private val socketFactory: () -> LiveSocketClient = { OkHttpLiveSocketClient(http) }

    /** Proves a pasted key works before it is saved. */
    fun keyVerifier(context: Context): ProviderKeyVerifier =
        ProviderKeyVerifier(minter(context), socketFactory)

    @Volatile private var credentialSource: SessionCredentialSource? = null

    /**
     * Mints session credentials on-device from the user's own API key. There is
     * no ClassEve server in this path, or in any other.
     */
    private fun credentials(context: Context): SessionCredentialSource {
        return credentialSource ?: synchronized(this) {
            credentialSource ?: LocalKeyBootstrapRepository(
                keys = providerKeys(context),
                minter = minter(context),
            ).also { credentialSource = it }
        }
    }

    @Volatile private var captureEngine: AudioCaptureEngine? = null

    private fun captureEngine(context: Context): AudioCaptureEngine {
        val appContext = context.applicationContext
        return captureEngine ?: synchronized(this) {
            captureEngine ?: AndroidAudioCaptureEngine(
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
                credentials = credentials(context),
                socketFactory = socketFactory,
                captureEngine = captureEngine(context),
                playbackEngine = playbackEngine,
                captionsStore = captionsStore,
                stateStore = stateStore,
                audioFocus = PlatformAudioFocus(
                    context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager,
                ),
                route = deviceMonitor(context).route,
                networkChanged = NetworkChanges(context.applicationContext).changed,
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

/** Says when the phone's default network becomes a different one, as on leaving Wi-Fi. */
private class NetworkChanges(context: Context) {

    private val _changed = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val changed: SharedFlow<Unit> = _changed

    @Volatile private var current: Network? = null

    init {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        runCatching {
            connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val previous = current
                    current = network
                    if (previous != null && previous != network) _changed.tryEmit(Unit)
                }
            })
        }
    }
}

// Media focus, not call mode: call mode switches on telephony voice
// processing, which mangles speech meant for a recogniser.
private class PlatformAudioFocus(private val audioManager: AudioManager) : AudioFocus {

    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        .build()

    override fun acquire() {
        runCatching { audioManager.requestAudioFocus(request) }
    }

    override fun release() {
        runCatching { audioManager.abandonAudioFocusRequest(request) }
    }
}
