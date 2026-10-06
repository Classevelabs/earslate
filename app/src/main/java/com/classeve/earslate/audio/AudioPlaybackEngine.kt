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

    private val outputs = ConcurrentHashMap<Int, Output>()
    @Volatile private var running = false
    @Volatile private var consecutive = false

    /** One lane, the track it plays on, and the thread that feeds it. */
    private inner class Output(val id: Int, val sampleRateHz: Int, private val track: AudioTrack) {
        val lane = PlayoutLane(sampleRateHz)

        @Volatile private var finishing = false
        @Volatile private var abandoned = false
        @Volatile private var deadlineMs = Long.MAX_VALUE
        @Volatile private var lastVoicedAtMs = NEVER

        val audible: Boolean get() = lastVoicedAtMs.let { it != NEVER && now() - it < AUDIBLE_FOR_MS }

        private val frame = ByteArray(sampleRateHz * FRAME_MS / 1000 * 2)

        // The thread that writes is the only one that ever releases the track:
        // freeing it under a blocking write crashes the process.
        private val thread = Thread({
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }
            try {
                track.play()
                while (!abandoned) {
                    if (finishing && (lane.snapshot().queuedVoicedMs == 0 || now() >= deadlineMs)) break
                    if (lane.pull(frame, now())) lastVoicedAtMs = now()
                    // A track that is always fed never has to restart, which
                    // is what makes the start of each sentence immediate.
                    if (track.write(frame, 0, frame.size, AudioTrack.WRITE_BLOCKING) < 0) break
                }
            } catch (t: IllegalStateException) {
                Log.w(TAG, "playout stopped: ${t.message}")
            } finally {
                outputs.remove(id, this)
                runCatching { if (abandoned) track.pause() else track.stop() }
                runCatching { track.release() }
            }
        }, "earslate-playout-$id")

        fun begin() = thread.start()

        fun finish(withinMs: Long) {
            deadlineMs = now() + withinMs
            finishing = true
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
        var output = outputs[lane]
        if (output != null && output.sampleRateHz != sampleRateHz) {
            outputs.remove(lane, output)
            output.finish(RETIRE_WITHIN_MS)
            output = null
        }
        if (output == null) {
            output = open(lane, sampleRateHz) ?: return
            output.lane.setConsecutive(consecutive, now())
            outputs[lane] = output
            output.begin()
            // A stop that ran while this lane was being built did not see it.
            if (!running) {
                output.abandon()
                return
            }
        }
        output.lane.offer(pcm, voiced, now())
    }

    private fun open(lane: Int, sampleRateHz: Int): Output? {
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
        return Output(lane, sampleRateHz, track)
    }

    override fun muteQueued(lane: Int) {
        outputs[lane]?.lane?.muteQueued()
    }

    // Stays in the map until it has finished, so held speech on a lane whose
    // session has been replaced is still released with the rest.
    override fun retire(lane: Int) {
        outputs[lane]?.finish(RETIRE_WITHIN_MS)
    }

    override fun setConsecutive(enabled: Boolean) {
        consecutive = enabled
        for (output in outputs.values) output.lane.setConsecutive(enabled, now())
    }

    override fun release() {
        for (output in outputs.values) output.lane.release(now())
    }

    override fun hold() {
        for (output in outputs.values) output.lane.hold()
    }

    override fun snapshot(): PlaybackSnapshot {
        val live = outputs.values.toList()
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

    override fun stop(graceful: Boolean) {
        running = false
        for (output in outputs.values.toList()) {
            // Speech still being held was never going to be said now.
            if (graceful && !output.lane.snapshot().held) output.finish(DRAIN_WITHIN_MS) else output.abandon()
        }
    }

    companion object {
        private const val TAG = "AudioPlayback"
        private const val NEVER = -1L
        private const val FRAME_MS = 20
        private const val TRACK_BUFFER_MS = 80

        /** Speech written this recently is still in the track and the room. */
        private const val AUDIBLE_FOR_MS = TRACK_BUFFER_MS + 2 * FRAME_MS

        private const val DRAIN_WITHIN_MS = 1_500L
        private const val RETIRE_WITHIN_MS = 6_000L
    }
}
