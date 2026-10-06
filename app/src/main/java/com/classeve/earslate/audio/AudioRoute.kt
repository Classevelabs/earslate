package com.classeve.earslate.audio

import android.media.AudioDeviceInfo

/**
 * Where translated speech comes out, in preference order: Bluetooth earbuds,
 * wired headset, then a loudspeaker.
 */
enum class AudioRoute {
    BLUETOOTH,
    WIRED,
    SPEAKER,
    UNKNOWN;

    /**
     * True when the microphone can hear what is played. The translation then
     * waits for a pause and the microphone closes while it plays; in an ear,
     * neither is needed. Anything not known to be in an ear counts as heard.
     */
    val sharedWithMicrophone: Boolean get() = this == SPEAKER || this == UNKNOWN

    companion object {
        /** The route for media played on outputs of these [AudioDeviceInfo] types. */
        fun of(outputTypes: Collection<Int>): AudioRoute {
            var bluetooth = false
            var wired = false
            var speaker = false
            for (type in outputTypes) {
                when (type) {
                    // Not SCO: that is the telephone link, and a watch or a car
                    // kit connected for calls plays no translation in anyone's ear.
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    AudioDeviceInfo.TYPE_BLE_HEADSET,
                    AudioDeviceInfo.TYPE_HEARING_AID -> bluetooth = true
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                    AudioDeviceInfo.TYPE_USB_HEADSET,
                    AudioDeviceInfo.TYPE_USB_DEVICE -> wired = true
                    // A Bluetooth speaker fills the room like the built-in one.
                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                    AudioDeviceInfo.TYPE_BLE_SPEAKER -> speaker = true
                }
            }
            return when {
                bluetooth -> BLUETOOTH
                wired -> WIRED
                speaker -> SPEAKER
                else -> UNKNOWN
            }
        }
    }
}
