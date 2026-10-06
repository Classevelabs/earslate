package com.classeve.earslate.audio

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
}
