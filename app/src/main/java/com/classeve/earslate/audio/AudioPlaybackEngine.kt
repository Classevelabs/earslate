package com.classeve.earslate.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Plays translated speech. Each translate session gets a lane of its own — its
 * own queue and its own output stream — and the platform mixes them, so two
 * directions never have to share, or fight over, one buffer.
 */
interface AudioPlaybackEngine {

    fun start()

    /** Queue audio on [lane]. [voiced] false keeps the lane's timing but plays silence. */
    fun write(lane: Int, pcm: ByteArray, sampleRateHz: Int, voiced: Boolean)

    /** What is queued on [lane] should not be heard after all. */
    fun muteQueued(lane: Int)

    /** Nothing more is coming for [lane]: let it finish, then free it. */
    fun retire(lane: Int)

    /** True holds speech until [release]; false plays it as it arrives. */
    fun setConsecutive(enabled: Boolean)

    /** Consecutive mode: play out what has been held. */
    fun release()

    /** Consecutive mode: go back to holding. */
    fun hold()

    fun snapshot(): PlaybackSnapshot

    /** [graceful] lets the last words finish. */
    fun stop(graceful: Boolean = true)
}

/** What the playback path is doing right now, measured. */
data class PlaybackSnapshot(
    val running: Boolean,
    val lanes: Int,
    /** Speech queued and not yet played, across every lane. */
    val waitingMs: Int,
    /** True while speech is still coming out of the loudspeaker. */
    val audible: Boolean,
    val cushionMs: Int,
    val underruns: Int,
    val droppedMs: Int,
)

class AndroidAudioPlaybackEngine(
    private val now: () -> Long = SystemClock::elapsedRealtime,
) : AudioPlaybackEngine {

    private val deck = PlayoutDeck(now, AUDIBLE_FOR_MS)
    private val players = ConcurrentHashMap<PlayoutDeck.Slot, Player>()
    @Volatile private var running = false

    /** One lane's output stream and the thread that feeds it. */
    private inner class Player(val slot: PlayoutDeck.Slot, private val track: AudioTrack) {
        @Volatile private var abandoned = false
        @Volatile private var deadlineMs = Long.MAX_VALUE

        private val frame = ByteArray(slot.sampleRateHz * FRAME_MS / 1000 * 2)

        // The thread that writes is the only one that ever releases the track:
        // freeing it under a blocking write crashes the process.
        private val thread = Thread({
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }
            try {
                track.play()
                while (!abandoned && !slot.finished && now() < deadlineMs) {
                    slot.pull(frame)
                    // A track that is always fed never has to restart, which
                    // is what makes the start of each sentence immediate.
                    val written = track.write(frame, 0, frame.size, AudioTrack.WRITE_BLOCKING)
                    if (written < 0) break
                    // A track that takes nothing is not playing; do not spin on it.
                    if (written == 0) Thread.sleep(FRAME_MS.toLong())
                }
                // The last words are still inside the track. Silence pushes
                // them out before the track is let go.
                if (!abandoned) {
                    frame.fill(0)
                    var tail = TRACK_BUFFER_MS / FRAME_MS + 1
                    while (tail-- > 0 && !abandoned && track.write(frame, 0, frame.size, AudioTrack.WRITE_BLOCKING) > 0) Unit
                }
            } catch (t: IllegalStateException) {
                Log.w(TAG, "playout stopped: ${t.message}")
            } catch (_: InterruptedException) {
                // abandoned
            } finally {
                players.remove(slot)
                deck.remove(slot)
                runCatching { if (abandoned) track.pause() else track.stop() }
                runCatching { track.release() }
            }
        }, "earslate-playout-${slot.id}")

        fun begin() = thread.start()

        fun finishWithin(ms: Long) {
            deadlineMs = now() + ms
        }

        fun abandon() {
            abandoned = true
            // Makes a write that is blocked in the framework return.
            runCatching { track.pause() }
        }
    }

    override fun start() {
        running = true
    }

    override fun write(lane: Int, pcm: ByteArray, sampleRateHz: Int, voiced: Boolean) {
        if (!running || sampleRateHz <= 0) return
        val made = deck.write(lane, pcm, sampleRateHz, voiced) ?: return
        val track = open(sampleRateHz)
        if (track == null) {
            deck.remove(made)
            return
        }
        val player = Player(made, track)
        players[made] = player
        player.begin()
        // A stop that ran while this lane was being built did not see it.
        if (!running) player.abandon()
    }

    private fun open(sampleRateHz: Int): AudioTrack? {
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRateHz,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            Log.w(TAG, "no output at $sampleRateHz Hz")
            return null
        }
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRateHz)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                // Small on purpose: the lane holds the cushion, where it can
                // be measured and adjusted. This is only the hand-off.
                .setBufferSizeInBytes(maxOf(minBuffer, sampleRateHz * TRACK_BUFFER_MS / 1000 * 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (t: Exception) {
            Log.e(TAG, "AudioTrack build failed: ${t.message}")
            return null
        }
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            runCatching { track.release() }
            return null
        }
        return track
    }

    override fun muteQueued(lane: Int) = deck.muteQueued(lane)

    // No deadline: speech held for the floor on a lane whose session has been
    // replaced is still said, however long the floor takes to come.
    override fun retire(lane: Int) = deck.retire(lane)

    override fun setConsecutive(enabled: Boolean) = deck.setConsecutive(enabled)

    override fun release() = deck.release()

    override fun hold() = deck.hold()

    override fun snapshot(): PlaybackSnapshot = deck.snapshot(running)

    override fun stop(graceful: Boolean) {
        running = false
        deck.retireAll()
        for (player in players.values.toList()) {
            // Speech still being held was never going to be said now.
            if (graceful && !player.slot.held) player.finishWithin(DRAIN_WITHIN_MS) else player.abandon()
        }
    }

    companion object {
        private const val TAG = "AudioPlayback"
        private const val FRAME_MS = 20
        private const val TRACK_BUFFER_MS = 80

        /** Speech written this recently is still in the track and the room. */
        private const val AUDIBLE_FOR_MS = TRACK_BUFFER_MS + 2L * FRAME_MS

        private const val DRAIN_WITHIN_MS = 1_500L
    }
}
