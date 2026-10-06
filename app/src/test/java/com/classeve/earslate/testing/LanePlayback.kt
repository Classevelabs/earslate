package com.classeve.earslate.testing

import com.classeve.earslate.audio.AudioPlaybackEngine
import com.classeve.earslate.audio.PlaybackSnapshot
import com.classeve.earslate.audio.PlayoutDeck
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * A loudspeaker made of the app's own lanes and played at real time, without
 * Android: everything the phone's playback does except the sound itself.
 */
class LanePlayback(now: () -> Long = { System.nanoTime() / 1_000_000 }) : AudioPlaybackEngine {

    private val deck = PlayoutDeck(now, audibleForMs = 120)
    private val slots = CopyOnWriteArrayList<PlayoutDeck.Slot>()
    private val heard = ConcurrentHashMap<Int, AtomicInteger>()
    @Volatile private var running = false
    @Volatile private var alive = true

    /** Called with each 20 ms that comes out, and whether it carried speech. */
    @Volatile var onSound: ((ByteArray, Boolean) -> Unit)? = null

    private val player = Thread({
        var next = System.nanoTime()
        while (alive) {
            var spoke = false
            var sound: ByteArray? = null
            for (slot in slots) {
                val frame = ByteArray(slot.sampleRateHz / 50 * 2)
                if (slot.pull(frame)) {
                    heard.getOrPut(slot.id) { AtomicInteger() }.addAndGet(20)
                    spoke = true
                    sound = frame
                }
                if (slot.finished) {
                    slots.remove(slot)
                    deck.remove(slot)
                }
            }
            onSound?.invoke(sound ?: ByteArray(960), spoke)
            next += 20_000_000
            val wait = (next - System.nanoTime()) / 1_000_000
            if (wait > 0) Thread.sleep(wait)
        }
    }, "lane-playback").apply {
        isDaemon = true
        start()
    }

    /** Speech that actually came out of lane [lane], in milliseconds. */
    fun heardMs(lane: Int): Int = heard[lane]?.get() ?: 0

    val totalHeardMs: Int get() = heard.values.sumOf { it.get() }

    fun shutDown() {
        alive = false
    }

    override fun start() {
        running = true
    }

    override fun write(lane: Int, pcm: ByteArray, sampleRateHz: Int, voiced: Boolean, begins: Boolean) {
        if (!running) return
        deck.write(lane, pcm, sampleRateHz, voiced, begins)?.let { slots += it }
    }

    override fun muteQueued(lane: Int) = deck.muteQueued(lane)

    override fun retire(lane: Int) = deck.retire(lane)

    override fun setConsecutive(enabled: Boolean) = deck.setConsecutive(enabled)

    override fun release() = deck.release()

    override fun hold() = deck.hold()

    override fun snapshot(): PlaybackSnapshot = deck.snapshot(running)

    override fun stop(graceful: Boolean) {
        running = false
        deck.retireAll()
    }
}
