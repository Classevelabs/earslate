package com.classeve.earslate.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Watches where translated speech will come out. The route decides how the
 * session behaves: a loudspeaker the microphone can hear needs turn-taking,
 * earbuds do not.
 */
class AudioDeviceMonitor(context: Context) {

    private val _route = MutableStateFlow(AudioRoute.UNKNOWN)
    val route: StateFlow<AudioRoute> = _route.asStateFlow()

    private val audioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // The same attributes the playback tracks are built with, so the answer is
    // about this app's audio and not the ringer's.
    private val playbackAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            _route.value = detect()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            _route.value = detect()
        }
    }

    fun start() {
        _route.value = detect()
        audioManager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
    }

    fun stop() {
        runCatching { audioManager.unregisterAudioDeviceCallback(callback) }
    }

    private fun detect(): AudioRoute {
        // Android 13 can say where this audio is actually routed. Before that,
        // the best available answer is which outputs are connected.
        val outputs: List<AudioDeviceInfo> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            audioManager.getAudioDevicesForAttributes(playbackAttributes)
        } else {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        }
        var hasBluetooth = false
        var hasWired = false
        var hasSpeaker = false
        for (d in outputs) {
            when (d.type) {
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                AudioDeviceInfo.TYPE_HEARING_AID -> hasBluetooth = true
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_USB_DEVICE -> hasWired = true
                // A Bluetooth speaker fills the room like the built-in one.
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                AudioDeviceInfo.TYPE_BLE_SPEAKER -> hasSpeaker = true
                else -> Unit
            }
        }
        return when {
            hasBluetooth -> AudioRoute.BLUETOOTH
            hasWired -> AudioRoute.WIRED
            hasSpeaker -> AudioRoute.SPEAKER
            else -> AudioRoute.UNKNOWN
        }
    }
}
