package com.classeve.earslate.audio

/**
 * One provider's translated speech on its way to the loudspeaker: a queue the
 * socket fills and the audio thread empties at exactly real time.
 *
 * The provider sends a continuous stream — silence included — so the lane
 * plays it as one. A small cushion of queued audio absorbs late arrivals: it
 * grows when a block arrives too late to play, and gives the delay back, by
 * skipping silence, once arrivals have been steady for a while.
 *
 * In consecutive mode (a loudspeaker both people share) the lane holds speech
 * until it is released, then plays it out in one piece.
 */
class PlayoutLane(private val sampleRateHz: Int) {

    data class Snapshot(
        val held: Boolean,
        val queuedMs: Int,
        val queuedVoicedMs: Int,
        val cushionMs: Int,
        val underruns: Int,
        val droppedMs: Int,
    )

    private class Segment(val pcm: ByteArray?, val length: Int, val utterance: Int) {
        var offset = 0
        val voiced get() = pcm != null
        val remaining get() = length - offset
    }

    private val lock = Any()
    private val queue = ArrayDeque<Segment>()
    private var utterance = 0
    private var queuedBytes = 0
    private var queuedVoicedBytes = 0

    private var consecutive = false
    private var held = false
    private var flowing = false
    private var primeUntilMs = UNSET
    private var dryAtMs = UNSET
    private var cushionMs = START_CUSHION_MS
    private var lastBlockBytes = 0

    // Only a stream that carries its own silence can be called late when the
    // lane runs dry; one that stops between sentences is simply pausing.
    private var continuous = false
    private var lastLateAtMs = UNSET

    private var windowStartMs = UNSET
    private var windowMinBytes = Int.MAX_VALUE
    private var pendingTrimBytes = 0
    private var troughBytes = 0

    private var halfSample: Byte? = null

    private var underruns = 0
    private var droppedBytes = 0

    private fun bytesFor(ms: Int): Int = (sampleRateHz.toLong() * ms / 1000).toInt() * 2
    private fun msFor(bytes: Int): Int = (bytes.toLong() * 1000 / (sampleRateHz * 2L)).toInt()

    /**
     * Queue one block. [voiced] false queues silence of the same length;
     * [begins] marks the first block of an utterance.
     */
    fun offer(pcm: ByteArray, voiced: Boolean, nowMs: Long, begins: Boolean = false) {
        synchronized(lock) {
            if (begins) utterance++
            val block = wholeSamples(pcm) ?: return
            if (!voiced) continuous = true
            if (held) {
                offerHeld(block, voiced)
                return
            }
            append(Segment(if (voiced) block else null, block.size, utterance))
            lastBlockBytes = block.size
            if (!flowing) {
                if (continuous && dryAtMs != UNSET && nowMs - dryAtMs <= UNDERRUN_MAX_MS) {
                    // A late block, not a new stream: remember how late, and
                    // start again with a little in hand.
                    underruns++
                    lastLateAtMs = nowMs
                    cushionMs = minOf(MAX_CUSHION_MS, cushionMs + (nowMs - dryAtMs).toInt() + UNDERRUN_MARGIN_MS)
                    primeUntilMs = nowMs + UNDERRUN_MARGIN_MS
                    pendingTrimBytes = 0
                } else if (primeUntilMs == UNSET) {
                    settleCushion(nowMs)
                    primeUntilMs = nowMs + cushionMs
                }
                dryAtMs = UNSET
            }
            trimBacklog()
        }
    }

    // A sample split across two blocks is put back together: half a sample
    // would turn everything after it into noise.
    private fun wholeSamples(pcm: ByteArray): ByteArray? {
        val carried = halfSample
        if (carried == null && pcm.size % 2 == 0) return pcm.takeIf { it.size >= 2 }
        val joined = if (carried != null) byteArrayOf(carried) + pcm else pcm
        halfSample = if (joined.size % 2 == 1) joined[joined.size - 1] else null
        val whole = joined.size and 1.inv()
        return if (whole >= 2) joined.copyOf(whole) else null
    }

    // A cushion bought by a late block is handed back, half at a time, at a
    // pause once nothing has been late for a while.
    private fun settleCushion(nowMs: Long) {
        if (cushionMs <= START_CUSHION_MS) return
        if (lastLateAtMs != UNSET && nowMs - lastLateAtMs < SHRINK_WINDOW_MS) return
        cushionMs = START_CUSHION_MS + (cushionMs - START_CUSHION_MS) / 2
    }

    // Held speech has no real-time meaning, so the silence around it is
    // reduced to a short pause.
    private fun offerHeld(pcm: ByteArray, voiced: Boolean) {
        if (voiced) {
            append(Segment(pcm, pcm.size, utterance))
            while (queuedVoicedBytes > bytesFor(MAX_HELD_MS) && queue.size > 1) drop(queue.removeFirst())
            return
        }
        if (queue.lastOrNull()?.voiced == true) append(Segment(null, minOf(pcm.size, bytesFor(HELD_PAUSE_MS)), utterance))
    }

    /**
     * Fill [out] with the next audio to play, silence when there is none.
     * @return true when [out] carries speech.
     */
    fun pull(out: ByteArray, nowMs: Long): Boolean = synchronized(lock) {
        if (held) {
            out.fill(0)
            return false
        }
        if (!flowing) {
            // A stream that opens with a burst already has its cushion in
            // hand, over and above the block the next arrival will replace.
            val inHand = queuedBytes >= bytesFor(cushionMs) + lastBlockBytes
            if (queuedBytes == 0 || primeUntilMs == UNSET || (nowMs < primeUntilMs && !inHand)) {
                out.fill(0)
                return false
            }
            flowing = true
            windowStartMs = UNSET
        }
        if (queuedBytes == 0) {
            flowing = false
            primeUntilMs = UNSET
            dryAtMs = nowMs
            out.fill(0)
            return false
        }
        if (windowStartMs == UNSET) {
            windowStartMs = nowMs
            windowMinBytes = Int.MAX_VALUE
        }
        skipSilence()
        val voiced = copy(out)
        if (queuedBytes < windowMinBytes) windowMinBytes = queuedBytes
        if (nowMs - windowStartMs >= SHRINK_WINDOW_MS) {
            planShrink()
            windowStartMs = nowMs
            windowMinBytes = Int.MAX_VALUE
        }
        voiced
    }

    // A window whose lowest level stayed well above what is needed means the
    // lane runs further behind than this network requires. A quarter of the
    // surplus goes back; giving it all back at once just buys the next
    // underrun.
    private fun planShrink() {
        if (windowMinBytes == Int.MAX_VALUE) return
        val surplus = windowMinBytes - bytesFor(TARGET_TROUGH_MS)
        if (surplus <= bytesFor(SHRINK_HYSTERESIS_MS)) return
        pendingTrimBytes = (surplus / 4) and 1.inv()
        troughBytes = windowMinBytes
    }

    private fun skipSilence() {
        val floor = bytesFor(TARGET_TROUGH_MS)
        while (pendingTrimBytes > 0) {
            val head = queue.firstOrNull() ?: return
            if (head.voiced) return
            val take = minOf(pendingTrimBytes, head.remaining, queuedBytes - floor) and 1.inv()
            if (take <= 0) return
            head.offset += take
            queuedBytes -= take
            pendingTrimBytes -= take
            troughBytes -= take
            // The cushion is what a new stream starts with: never more than
            // the lane now runs with at its lowest.
            cushionMs = maxOf(MIN_CUSHION_MS, minOf(cushionMs, msFor(troughBytes)))
            if (head.remaining == 0) queue.removeFirst()
        }
    }

    // After a network stall a burst arrives at once and playback would run
    // that far behind for good. Pauses are shortened first; speech is cut only
    // when the lane is seconds behind. Speech held on purpose is left alone,
    // pauses and all.
    private fun trimBacklog() {
        if (consecutive) return
        var excess = queuedBytes - bytesFor(cushionMs + BACKLOG_SLACK_MS)
        if (excess <= 0) return
        val kept = ArrayDeque<Segment>(queue.size)
        for (segment in queue) {
            if (excess > 0 && !segment.voiced) {
                val take = minOf(excess, segment.remaining) and 1.inv()
                segment.offset += take
                queuedBytes -= take
                excess -= take
                if (segment.remaining == 0) continue
            }
            kept.addLast(segment)
        }
        queue.clear()
        queue.addAll(kept)
        while (queuedBytes > bytesFor(MAX_BACKLOG_MS) && queue.size > 1) drop(queue.removeFirst())
    }

    private fun append(segment: Segment) {
        queue.addLast(segment)
        queuedBytes += segment.length
        if (segment.voiced) queuedVoicedBytes += segment.length
    }

    private fun drop(segment: Segment) {
        queuedBytes -= segment.remaining
        if (segment.voiced) {
            queuedVoicedBytes -= segment.remaining
            droppedBytes += segment.remaining
        }
    }

    private fun copy(out: ByteArray): Boolean {
        var written = 0
        var voiced = false
        while (written < out.size) {
            val head = queue.firstOrNull() ?: break
            val take = minOf(out.size - written, head.remaining)
            val pcm = head.pcm
            if (pcm != null) {
                System.arraycopy(pcm, head.offset, out, written, take)
                queuedVoicedBytes -= take
                voiced = true
            } else {
                out.fill(0, written, written + take)
            }
            head.offset += take
            queuedBytes -= take
            written += take
            if (head.remaining == 0) queue.removeFirst()
        }
        if (written < out.size) out.fill(0, written, out.size)
        return voiced
    }

    /**
     * What is queued of the utterance being spoken should never have been:
     * keep its timing, lose its sound. A sentence still waiting ahead of it is
     * somebody's translation, and is left alone.
     */
    fun muteQueued() = synchronized(lock) {
        val kept = queue.map { if (it.voiced && it.utterance == utterance) Segment(null, it.remaining, utterance) else it }
        queue.clear()
        queue.addAll(kept)
        queuedVoicedBytes = kept.sumOf { if (it.voiced) it.remaining else 0 }
    }

    /** True holds speech until [release]; false plays it as it arrives. */
    fun setConsecutive(enabled: Boolean, nowMs: Long) = synchronized(lock) {
        if (consecutive == enabled) return@synchronized
        consecutive = enabled
        if (enabled) hold() else release(nowMs)
    }

    /** Stop playing and keep what arrives. Only meaningful in consecutive mode. */
    fun hold() = synchronized(lock) {
        if (!consecutive) return@synchronized
        held = true
        flowing = false
        primeUntilMs = UNSET
        dryAtMs = UNSET
        pendingTrimBytes = 0
        // Whatever is played next should start with speech, not a pause.
        while (queue.firstOrNull()?.voiced == false) queuedBytes -= queue.removeFirst().remaining
    }

    /** Play out what is held, and whatever is still arriving behind it. */
    fun release(nowMs: Long) = synchronized(lock) {
        if (!held) return@synchronized
        held = false
        settleCushion(nowMs)
        // With little in hand the rest is still arriving at real time, so it
        // needs the same cushion a live stream starts with.
        primeUntilMs = when {
            queuedBytes == 0 -> UNSET
            queuedBytes >= bytesFor(cushionMs + BACKLOG_SLACK_MS) -> nowMs
            else -> nowMs + cushionMs
        }
    }

    fun snapshot(): Snapshot = synchronized(lock) {
        Snapshot(
            held = held,
            queuedMs = msFor(queuedBytes),
            queuedVoicedMs = msFor(queuedVoicedBytes),
            cushionMs = cushionMs,
            underruns = underruns,
            droppedMs = msFor(droppedBytes),
        )
    }

    companion object {
        private const val UNSET = -1L

        /** Queued audio in hand when a stream starts; arrivals run up to 270 ms late. */
        const val START_CUSHION_MS = 160
        const val MIN_CUSHION_MS = 60
        const val MAX_CUSHION_MS = 600

        /** A lane dry for no longer than this was waiting on a late block, not a new utterance. */
        const val UNDERRUN_MAX_MS = 600L
        const val UNDERRUN_MARGIN_MS = 40

        const val SHRINK_WINDOW_MS = 15_000L
        const val TARGET_TROUGH_MS = 60
        const val SHRINK_HYSTERESIS_MS = 40

        const val BACKLOG_SLACK_MS = 600
        const val MAX_BACKLOG_MS = 4_000

        const val HELD_PAUSE_MS = 200
        const val MAX_HELD_MS = 90_000
    }
}
