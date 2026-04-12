package com.classeve.earslate.session

/**
 * State machine for the translator runtime.
 *
 * IDLE is the only terminal resting state. Every active session moves through
 * BOOTSTRAPPING → CONNECTING → READY → LISTENING, with PLAYING interleaved while
 * translated audio is draining. Failure branches go through RECONNECTING /
 * RESUMING / DEGRADED without tearing down the service.
 *
 * Blueprint §14.
 */
enum class RuntimeState {
    IDLE,
    BOOTSTRAPPING,
    CONNECTING,
    READY,
    LISTENING,
    PLAYING,
    RECONNECTING,
    RESUMING,
    DEGRADED,
    STOPPING,
}

val RuntimeState.isActive: Boolean
    get() = when (this) {
        RuntimeState.IDLE, RuntimeState.STOPPING -> false
        else -> true
    }

val RuntimeState.isRecovering: Boolean
    get() = this == RuntimeState.RECONNECTING ||
        this == RuntimeState.RESUMING ||
        this == RuntimeState.DEGRADED
