package com.classeve.earslate.session

import kotlin.math.min
import kotlin.random.Random

/**
 * Bounded exponential backoff with jitter.
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

    /**
     * Report how long the session that just ended stayed connected, so the
     * backoff is reset only for a session that was genuinely working.
     *
     * A session that reached READY and lasted at least [STABLE_AFTER_MS] earns
     * a fresh backoff: its drop is an isolated blip, and the next one should
     * retry promptly. A session that dropped sooner — including one a provider
     * accepts and then refuses immediately — does NOT reset. That is what
     * bounds the reconnect: without it, resetting the instant a leg reached
     * READY meant an accept-then-drop provider looped forever at the attempt-1
     * delay of 0 ms, re-minting a single-use credential on the user's own key
     * every iteration. Now a run of fast drops walks the delay up to its cap
     * and stops at [attemptNumber] == the caller's budget instead.
     */
    fun noteSessionEnded(readyDurationMs: Long) {
        if (readyDurationMs >= STABLE_AFTER_MS) reset()
    }

    val attemptNumber: Int get() = attempt

    companion object {
        /**
         * How long a session must stay connected before its death counts as
         * "was working, just blipped" rather than "flapping". Comfortably
         * longer than the 5 s backoff cap so a provider that keeps
         * accepting-then-dropping cannot keep the backoff pinned at zero.
         */
        const val STABLE_AFTER_MS = 15_000L
    }
}
