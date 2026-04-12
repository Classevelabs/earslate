package com.classeve.earslate.session

import kotlin.math.min
import kotlin.random.Random

/**
 * Bounded exponential backoff with jitter. Blueprint §15.1.
 *
 *   attempt 1: immediate (0 ms)
 *   attempt 2: 500 ms  ± jitter
 *   attempt 3: 1000 ms ± jitter
 *   attempt 4: 2000 ms ± jitter
 *   attempt 5+: cap at 5000 ms ± jitter
 *
 * Pure value object — no coroutines, no state outside the caller. The
 * SessionCoordinator is responsible for honoring the delay and respecting the
 * ConnectivityManager availability callback.
 */
class ReconnectManager {

    private var attempt: Int = 0

    fun nextDelayMs(): Long {
        attempt++
        val base = when (attempt) {
            1 -> 0L
            2 -> 500L
            3 -> 1_000L
            4 -> 2_000L
            else -> 5_000L
        }
        if (base == 0L) return 0L
        val jitter = Random.nextLong(0, min(base / 4, 500L))
        return base + jitter
    }

    fun reset() {
        attempt = 0
    }

    val attemptNumber: Int get() = attempt
}
