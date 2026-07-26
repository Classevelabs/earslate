package com.classeve.earslate.audio

/**
 * Adaptive jitter buffer for the translated-audio stream.
 *
 * The previous version held a fixed 60 ms and, on any underrun, dropped back to
 * "not draining" and waited to re-accumulate the full 60 ms before playing
 * again. On a clean network that is fine. On a real one it is a stutter
 * machine: one late packet costs a full re-buffer, which makes the next
 * underrun more likely, and the stream spends its life oscillating between
 * silence and catch-up.
 *
 * This version keeps the target latency as low as the network will actually
 * allow, and moves it rather than resetting it:
 *
 *  - **Start low.** [minTargetBytes] (40 ms) is the floor, because a live
 *    conversation is unusable if you add a fixed cushion to every sentence.
 *  - **Grow on pain.** Each underrun raises the target by [growthStepBytes]
 *    (20 ms), up to [maxTargetBytes] (240 ms). A network that stutters twice
 *    gets a bigger cushion instead of the same one, so the stutter stops.
 *  - **Shrink on calm.** After [recoveryRuns] consecutive clean drains the
 *    target eases back down one step. Latency is given back as soon as it is
 *    safe to, rather than being permanently lost to one bad moment.
 *  - **Never fully stall.** An underrun no longer flips playback off. The
 *    buffer stays in draining state and simply has nothing to give this tick,
 *    so the moment a packet lands it plays — instead of waiting for a fresh
 *    60 ms to pile up.
 *  - **Bound the backlog.** If the sender bursts, anything beyond
 *    [maxBufferedBytes] (1.2 s) is dropped from the *front*. Late audio in a
 *    live conversation is worse than missing audio: the speaker has moved on.
 *
 * All sizes are byte counts so the caller can express them in milliseconds at
 * whatever sample rate is actually in use. [retarget] exists because the
 * playback engine can rebuild its track at a new rate mid-stream; the buffer
 * has to be re-expressed in the new rate's bytes or every threshold silently
 * means a different duration.
 */
class JitterBuffer(
    startupBytes: Int,
    private var minTargetBytes: Int = startupBytes,
    private var maxTargetBytes: Int = startupBytes * 6,
    private var growthStepBytes: Int = startupBytes / 2,
    private var maxBufferedBytes: Int = startupBytes * 30,
    private val recoveryRuns: Int = 50,
) {
    private val queue = ArrayDeque<ByteArray>()
    private val lock = Any()

    private var accumulated = 0
    private var draining = false
    private var targetBytes = startupBytes
    private var cleanRuns = 0

    private var underruns = 0
    private var dropped = 0

    /** Underruns since the last [reset]. Surfaced in diagnostics as buffer health. */
    val underrunCount: Int get() = synchronized(lock) { underruns }

    /** Chunks discarded because the backlog grew past [maxBufferedBytes]. */
    val droppedChunks: Int get() = synchronized(lock) { dropped }

    fun enqueue(chunk: ByteArray) {
        if (chunk.isEmpty()) return
        synchronized(lock) {
            queue.addLast(chunk)
            accumulated += chunk.size
            // Drop the oldest audio, not the newest: the newest is what the
            // listener is waiting to hear.
            while (accumulated > maxBufferedBytes && queue.size > 1) {
                val stale = queue.removeFirst()
                accumulated -= stale.size
                dropped++
            }
            if (!draining && accumulated >= targetBytes) {
                draining = true
                cleanRuns = 0
            }
        }
    }

    /**
     * The next chunk to play, or null when there is nothing ready. Null during
     * an underrun does **not** mean playback has stopped — the buffer stays
     * armed and returns audio as soon as any arrives.
     */
    fun drain(): ByteArray? = synchronized(lock) {
        if (!draining) return null
        val next = queue.removeFirstOrNull()
        if (next == null) {
            onUnderrun()
            return null
        }
        accumulated -= next.size
        onCleanDrain()
        next
    }

    private fun onUnderrun() {
        underruns++
        cleanRuns = 0
        // Widen the cushion so the next gap is absorbed instead of heard.
        if (targetBytes < maxTargetBytes) {
            targetBytes = minOf(maxTargetBytes, targetBytes + growthStepBytes)
        }
        // Re-arm: wait for the (now larger) target before resuming, but only
        // if nothing is queued. If audio is already waiting we keep playing.
        if (accumulated < targetBytes) draining = false
    }

    private fun onCleanDrain() {
        if (targetBytes <= minTargetBytes) return
        cleanRuns++
        if (cleanRuns >= recoveryRuns) {
            cleanRuns = 0
            targetBytes = maxOf(minTargetBytes, targetBytes - growthStepBytes)
        }
    }

    /**
     * Re-express every threshold at a new sample rate, preserving the adapted
     * target as a *duration* rather than a byte count. Called when the playback
     * engine rebuilds its track because the provider changed output rate
     * mid-stream. Without this the buffer keeps old byte counts that now mean a
     * completely different number of milliseconds.
     */
    fun retarget(
        startupBytes: Int,
        minBytes: Int = startupBytes,
        maxBytes: Int = startupBytes * 6,
        stepBytes: Int = startupBytes / 2,
        capBytes: Int = startupBytes * 30,
    ) = synchronized(lock) {
        val adaptationRatio = if (minTargetBytes > 0) {
            targetBytes.toDouble() / minTargetBytes.toDouble()
        } else {
            1.0
        }
        minTargetBytes = minBytes
        maxTargetBytes = maxBytes
        growthStepBytes = stepBytes
        maxBufferedBytes = capBytes
        targetBytes = (minBytes * adaptationRatio).toInt().coerceIn(minBytes, maxBytes)
        cleanRuns = 0
    }

    fun clear() = synchronized(lock) {
        queue.clear()
        accumulated = 0
        draining = false
        cleanRuns = 0
    }

    /** Resets adaptation as well as contents. Used when a session ends. */
    fun reset(startupBytes: Int) = synchronized(lock) {
        queue.clear()
        accumulated = 0
        draining = false
        cleanRuns = 0
        targetBytes = startupBytes
        underruns = 0
        dropped = 0
    }

    val pendingBytes: Int get() = synchronized(lock) { accumulated }

    /** Current adaptive target, for diagnostics. */
    val targetLatencyBytes: Int get() = synchronized(lock) { targetBytes }
}
