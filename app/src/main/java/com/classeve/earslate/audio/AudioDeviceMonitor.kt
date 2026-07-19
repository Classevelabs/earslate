package com.classeve.earslate.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Watches the active audio output device and surfaces the route (Bluetooth,
 * wired, speaker). Blueprint §27 — Bluetooth / wired earbuds are preferred
 * because they dramatically reduce self-capture echo in a single-mic device.
 */
class AudioDeviceMonitor(context: Context) {

    private val _route = MutableStateFlow(AudioRoute.UNKNOWN)
    val route: StateFlow<AudioRoute> = _route.asStateFlow()

    private val audioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

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
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        var hasBluetooth = false
        var hasWired = false
        var hasSpeaker = false
        for (d in outputs) {
            when (d.type) {
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                AudioDeviceInfo.TYPE_BLE_SPEAKER -> hasBluetooth = true
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_USB_HEADSET -> hasWired = true
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> hasSpeaker = true
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
