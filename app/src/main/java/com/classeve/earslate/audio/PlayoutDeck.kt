package com.classeve.earslate.audio

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Every lane of a session, and whether they play as they are fed or wait for
 * the floor.
 *
 * A lane that joins late takes the answer the others already have. One that
 * started out waiting after the floor had been given would wait for ever, and
 * the microphone would stay closed while it did.
 */
class PlayoutDeck(
    private val now: () -> Long,
    /** How long speech is still in the output path, and the room, after it is handed over. */
    private val audibleForMs: Long,
) {

    /** One lane, for whatever plays it out. */
    inner class Slot internal constructor(val id: Int, val sampleRateHz: Int) {
        internal val lane = PlayoutLane(sampleRateHz)
        @Volatile internal var retired = false
        @Volatile private var lastSpokeAtMs = NEVER

        /** The next audio for the loudspeaker; true when it carries speech. */
        fun pull(out: ByteArray): Boolean {
            val spoke = lane.pull(out, now())
            if (spoke) lastSpokeAtMs = now()
            return spoke
        }

        /** True once nothing more is coming and everything queued has been said. */
        val finished: Boolean get() = retired && lane.snapshot().queuedVoicedMs == 0

        val held: Boolean get() = lane.snapshot().held

        internal val audible: Boolean get() = lastSpokeAtMs.let { it != NEVER && now() - it < audibleForMs }
    }

    private val lock = Any()
    private val slots = CopyOnWriteArrayList<Slot>()
    private var consecutive = false
    private var holding = false

    /**
     * Queue audio on lane [id].
     * @return the lane's slot when it had to be made, for the caller to start playing.
     */
    fun write(id: Int, pcm: ByteArray, sampleRateHz: Int, voiced: Boolean): Slot? {
        var made: Slot? = null
        val slot = synchronized(lock) {
            var current = slots.lastOrNull { it.id == id && !it.retired }
            // A change of rate needs a new output; the old one finishes what it holds.
            if (current != null && current.sampleRateHz != sampleRateHz) {
                current.retired = true
                current = null
            }
            current ?: Slot(id, sampleRateHz).also {
                it.lane.setConsecutive(consecutive, now())
                if (consecutive && !holding) it.lane.release(now())
                slots += it
                made = it
            }
        }
        slot.lane.offer(pcm, voiced, now())
        return made
    }

    fun muteQueued(id: Int) {
        for (slot in slots) if (slot.id == id) slot.lane.muteQueued()
    }

    /** Nothing more is coming for lane [id]: it finishes what it holds. */
    fun retire(id: Int) = synchronized(lock) {
        for (slot in slots) if (slot.id == id) slot.retired = true
    }

    fun retireAll() = synchronized(lock) {
        for (slot in slots) slot.retired = true
    }

    /** Whatever was playing [slot] has stopped. */
    fun remove(slot: Slot) {
        slots.remove(slot)
    }

    /** True holds speech until [release]; false plays it as it arrives. */
    fun setConsecutive(enabled: Boolean) = synchronized(lock) {
        consecutive = enabled
        holding = enabled
        for (slot in slots) slot.lane.setConsecutive(enabled, now())
    }

    fun release() = synchronized(lock) {
        holding = false
        for (slot in slots) slot.lane.release(now())
    }

    fun hold() = synchronized(lock) {
        if (!consecutive) return@synchronized
        holding = true
        for (slot in slots) slot.lane.hold()
    }

    fun snapshot(running: Boolean): PlaybackSnapshot {
        val live = slots.toList()
        val lanes = live.map { it.lane.snapshot() }
        return PlaybackSnapshot(
            running = running,
            lanes = lanes.size,
            waitingMs = lanes.sumOf { it.queuedVoicedMs },
            audible = live.any { it.audible },
            cushionMs = lanes.maxOfOrNull { it.cushionMs } ?: 0,
            underruns = lanes.sumOf { it.underruns },
            droppedMs = lanes.sumOf { it.droppedMs },
        )
    }

    private companion object {
        const val NEVER = -1L
    }
}
