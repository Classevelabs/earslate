package com.classeve.earslate.audio

/**
 * Active playback route, in preference order: Bluetooth earbuds, wired headset,
 * then the built-in speaker.
 *
 * The route is not cosmetic — on [SPEAKER] the coordinator always half-duplex-
 * gates the microphone during playback, because translated speech coming out of
 * the speaker would otherwise be re-captured and re-translated in a loop.
 */
enum class AudioRoute {
    BLUETOOTH,
    WIRED,
    SPEAKER,
    UNKNOWN,
}
