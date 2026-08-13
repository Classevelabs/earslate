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
 *  - **Start above one provider chunk.** [minTargetBytes] is the floor. It must
 *    exceed the provider's chunk period, or a *single* late chunk empties the
 *    buffer — the 40 ms floor this replaces was under half a chunk, so on mobile
 *    data it underran on virtually every utterance and the stream never got a
 *    chance to settle. Speech translation already carries ~1 s of model latency;
 *    a cushion in the low hundreds of ms is inaudible next to that, and it is the
 *    difference between smooth and choppy.
 *  - **Grow on pain, fast.** Each underrun raises the target by
 *    [growthStepBytes], up to [maxTargetBytes]. The step has to be a meaningful
 *    fraction of the ceiling: climbing in 20 ms hops from 40 ms to 240 ms takes
 *    ten underruns, and every one of those is a gap the user hears.
 *  - **Shrink on calm, slowly.** After [recoveryBytes] of *clean drained audio*
 *    the target eases down one step. Measured in bytes, not drain calls, because
 *    a drain call is one provider chunk of unknown duration — counting calls made
 *    recovery arrive many times sooner than intended at large chunk sizes, which
 *    walked the cushion straight back down into the underrun zone and turned the
 *    whole thing into the oscillation it was meant to prevent.
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
    private var maxTargetBytes: Int = startupBytes * 4,
    private var growthStepBytes: Int = startupBytes / 3,
    private var maxBufferedBytes: Int = startupBytes * 8,
    /** Bytes of clean drained audio that buy back one [growthStepBytes] of latency. */
    private var recoveryBytes: Int = startupBytes * 60,
) {
    private val queue = ArrayDeque<ByteArray>()
    private val lock = Any()

    private var accumulated = 0
    private var draining = false
    private var targetBytes = startupBytes
    private var cleanBytes = 0

    /**
     * Largest chunk the provider has actually sent. Discovered, never assumed —
     * see [armThresholdBytes].
     */
    private var largestChunkBytes = 0

    private var underruns = 0
    private var dropped = 0

    /**
     * Set by [markTurnEnd] when the provider has said it has finished speaking, so
     * the buffer emptying next is expected rather than a fault. Cleared by the next
     * [enqueue].
     */
    private var turnEnded = false

    /**
     * True while the queue has come back empty mid-speech and the gap has
     * already been charged as an underrun. Cleared by the next successful
     * drain. See [onUnderrun] for why one gap must not be counted per tick.
     */
    private var starved = false

    /** Underruns since the last [reset]. Surfaced in diagnostics as buffer health. */
    val underrunCount: Int get() = synchronized(lock) { underruns }

    /** Chunks discarded because the backlog grew past [maxBufferedBytes]. */
    val droppedChunks: Int get() = synchronized(lock) { dropped }

    /**
     * How much audio must be in hand before playback starts (or restarts).
     *
     * This is the adapted [targetBytes], but never less than one and a quarter of
     * the largest chunk the provider has actually sent. That floor is the whole
     * point: a cushion smaller than one chunk cannot keep any audio in reserve —
     * it plays the chunk it just received, finds the queue empty, and starves
     * until the next one lands, once per chunk, forever.
     *
     * Measured on-device 2026-07-27: Gemini sends 12000-byte chunks, which at
     * 24 kHz mono PCM16 is **250 ms of audio each**, every ~248 ms. The old
     * configuration had a 40 ms floor and a 240 ms ceiling — so even fully
     * adapted, at its ceiling, the buffer still held less than one chunk and
     * underran on every single one. That is what the choppiness was.
     *
     * Learning the size instead of hard-coding 250 ms matters because the number
     * is the provider's to change: hard-coding it would mean pointless latency if
     * chunks get smaller, and a return of the stutter if they get bigger.
     */
    private fun armThresholdBytes(): Int {
        if (largestChunkBytes == 0) return targetBytes
        val oneChunkAndABit = largestChunkBytes + largestChunkBytes / 4
        return maxOf(targetBytes, minOf(oneChunkAndABit, maxTargetBytes))
    }

    fun enqueue(chunk: ByteArray) {
        if (chunk.isEmpty()) return
        synchronized(lock) {
            queue.addLast(chunk)
            accumulated += chunk.size
            turnEnded = false
            if (chunk.size > largestChunkBytes) largestChunkBytes = chunk.size
            // Drop the oldest audio, not the newest: the newest is what the
            // listener is waiting to hear.
            while (accumulated > maxBufferedBytes && queue.size > 1) {
                val stale = queue.removeFirst()
                accumulated -= stale.size
                dropped++
            }
            if (!draining && accumulated >= armThresholdBytes()) {
                draining = true
                cleanBytes = 0
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
        starved = false
        onCleanDrain(next.size)
        next
    }

    /**
     * The provider has finished a turn, so the buffer running dry next is the
     * expected end of speech and not a network fault.
     *
     * Without this the adaptive target ratchets upward across a normal
     * conversation: every utterance ends, the buffer legitimately empties, and
     * each of those was charged as an underrun that bought another
     * [growthStepBytes] of latency. Measured on-device 2026-07-27 — the target
     * climbed to its 600 ms ceiling within a couple of minutes of idle
     * conversation, none of it earned by an actual stutter. Recovery could not
     * undo it either, because giving latency back needs sustained clean audio and
     * a quiet conversation never supplies any.
     */
    fun markTurnEnd() = synchronized(lock) { turnEnded = true }

    private fun onUnderrun() {
        cleanBytes = 0
        if (turnEnded) {
            // Expected quiet. Re-arm for the next utterance, but buy nothing:
            // this is not evidence the network is struggling.
            draining = false
            starved = false
            return
        }
        // One continuous gap is ONE underrun, however many times the playback
        // loop polls during it. Because the buffer now stays armed (below),
        // drain() keeps arriving here every few milliseconds while the queue is
        // empty. Charging each visit would add a growth step per tick and peg
        // the target at its ceiling within a few tens of milliseconds, turning
        // a smoothness fix into a latency bug — and it would make the underrun
        // metric a measure of polling frequency rather than of network health.
        if (starved) return
        starved = true
        underruns++
        // Widen the cushion so the NEXT utterance absorbs a gap this size.
        if (targetBytes < maxTargetBytes) {
            targetBytes = minOf(maxTargetBytes, targetBytes + growthStepBytes)
        }
        // Deliberately NOT disarming mid-speech.
        //
        // This line used to read `if (accumulated < armThresholdBytes()) draining = false`,
        // whose comment claimed it only disarmed when nothing was queued. It
        // disarmed every time: drain() only reaches onUnderrun when the queue
        // came back empty, so `accumulated` is always 0 here and the condition
        // is always true.
        //
        // The cost was severe and only visible on a real network. Re-arming
        // needs armThresholdBytes — 1.25x the largest chunk the provider has
        // sent, so 15000 bytes against Gemini's 12000-byte chunks. One late
        // packet therefore silenced playback until TWO more chunks arrived: at
        // the measured ~248 ms cadence, roughly half a second of dead air in
        // the middle of a sentence, in exchange for one late packet.
        //
        // The class KDoc has promised the opposite since this buffer was
        // written — "Never fully stall... the moment a packet lands it plays".
        // The code simply did not implement its own contract. It does now: the
        // gap is bridged by the playback engine's comfort silence, which is
        // capped at 100 ms, and the next chunk plays the instant it lands.
        //
        // The grown target is not wasted. It applies at the next arm, which is
        // end-of-turn — the one moment when buying latency is free, because
        // nobody is speaking.
    }

    private fun onCleanDrain(bytes: Int) {
        if (targetBytes <= minTargetBytes) return
        cleanBytes += bytes
        if (cleanBytes >= recoveryBytes) {
            cleanBytes = 0
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
        maxBytes: Int = startupBytes * 4,
        stepBytes: Int = startupBytes / 3,
        capBytes: Int = startupBytes * 8,
        recoveryTargetBytes: Int = startupBytes * 60,
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
        recoveryBytes = recoveryTargetBytes
        targetBytes = (minBytes * adaptationRatio).toInt().coerceIn(minBytes, maxBytes)
        cleanBytes = 0
        starved = false
        // A chunk's byte size is rate-dependent, so what we learned at the old
        // rate would misstate one chunk's worth at the new one. Re-learn it.
        largestChunkBytes = 0
    }

    fun clear() = synchronized(lock) {
        queue.clear()
        accumulated = 0
        draining = false
        cleanBytes = 0
        starved = false
    }

    /** Resets adaptation as well as contents. Used when a session ends. */
    fun reset(startupBytes: Int) = synchronized(lock) {
        queue.clear()
        accumulated = 0
        draining = false
        cleanBytes = 0
        targetBytes = startupBytes
        largestChunkBytes = 0
        turnEnded = false
        starved = false
        underruns = 0
        dropped = 0
    }

    val pendingBytes: Int get() = synchronized(lock) { accumulated }

    /**
     * The cushion actually in force, for diagnostics — the adapted target after
     * the one-chunk floor is applied, which is what the listener really
     * experiences. Reporting the raw target would have understated it.
     */
    val targetLatencyBytes: Int get() = synchronized(lock) { armThresholdBytes() }

    /**
     * The raw adapted target, before the one-chunk floor. This is the value the
     * underrun/recovery logic actually moves; [targetLatencyBytes] is what it
     * amounts to in practice once the floor applies.
     */
    val adaptedTargetBytes: Int get() = synchronized(lock) { targetBytes }
}
