package com.classeve.earslate.session

/**
 * What the translator is doing. IDLE is the only resting state; an active
 * session moves BOOTSTRAPPING → CONNECTING → LISTENING, with PLAYING while a
 * translation is being heard, MICROPHONE_TAKEN while a phone call or another
 * app has the microphone, and RECONNECTING while a lost connection is being
 * replaced.
 */
enum class RuntimeState {
    IDLE,
    BOOTSTRAPPING,
    CONNECTING,
    LISTENING,
    PLAYING,
    MICROPHONE_TAKEN,
    RECONNECTING,
}

val RuntimeState.isActive: Boolean
    get() = this != RuntimeState.IDLE
