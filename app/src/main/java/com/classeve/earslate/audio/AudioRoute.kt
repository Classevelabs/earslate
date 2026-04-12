package com.classeve.earslate.audio

/**
 * Preferred playback route order per Blueprint §27:
 * 1. Bluetooth earbuds / headset
 * 2. Wired headset
 * 3. Speaker (only if the user accepts it — VAD gets more conservative here)
 */
enum class AudioRoute {
    BLUETOOTH,
    WIRED,
    SPEAKER,
    UNKNOWN,
}

val AudioRoute.isEarbudLike: Boolean
    get() = this == AudioRoute.BLUETOOTH || this == AudioRoute.WIRED
