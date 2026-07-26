package com.classeve.earslate.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Owns the translated-audio playback path.
 *
 *   - Mono PCM16, at whatever rate the provider is actually sending (24 kHz for
 *     both Gemini Live and OpenAI Realtime today; the track rebuilds itself if
 *     that changes mid-stream).
 *   - AudioTrack in STREAM mode, USAGE_MEDIA so audio routes to earbuds at full
 *     clarity. No hardware AEC coupling: capture deliberately takes raw ambient
 *     audio, and echo is handled by the half-duplex mic gate in the session
 *     coordinator rather than by the platform reference mix.
 *   - An adaptive [JitterBuffer] that starts at 40 ms and only buys more
 *     latency when the network makes it necessary.
 *   - A genuinely graceful stop that lets the last word finish.
 */
interface AudioPlaybackEngine {
    fun start(audioSessionId: Int = AudioManager.AUDIO_SESSION_ID_GENERATE)
    fun enqueue(pcm: ByteArray, sampleRateHz: Int = 24_000)
    fun stop(graceful: Boolean = true)

    /** Live buffer health for the diagnostics screen. */
    fun snapshot(): PlaybackSnapshot
}

/** What the playback path is actually doing right now. All measured, never assumed. */
data class PlaybackSnapshot(
    val running: Boolean,
    val sampleRateHz: Int,
    val bufferedMs: Int,
    val targetLatencyMs: Int,
    val underruns: Int,
    val droppedChunks: Int,
)

class AndroidAudioPlaybackEngine(
    private val defaultSampleRateHz: Int = 24_000,
    /**
     * Floor for the adaptive buffer. 40 ms is about two provider frames — low
     * enough that a reply feels immediate, high enough to absorb ordinary
     * scheduling noise. The buffer raises this itself when the network needs it.
     */
    private val startupLatencyMs: Int = 40,
) : AudioPlaybackEngine {

    private val bytesPerSample = 2

    private val buffer = JitterBuffer(
        startupBytes = startupBytesFor(defaultSampleRateHz),
        maxTargetBytes = bytesFor(defaultSampleRateHz, MAX_LATENCY_MS),
        growthStepBytes = bytesFor(defaultSampleRateHz, GROWTH_STEP_MS),
        maxBufferedBytes = bytesFor(defaultSampleRateHz, MAX_BACKLOG_MS),
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var track: AudioTrack? = null
    @Volatile private var loopJob: Job? = null
    @Volatile private var activeRateHz: Int = 0
    @Volatile private var sessionId: Int = AudioManager.AUDIO_SESSION_ID_GENERATE

    /**
     * Held across a mid-stream rate rebuild. The drain loop parks on this
     * instead of spinning, and no audio is pulled from the buffer while it is
     * set — the previous implementation dequeued chunks during a rebuild and
     * dropped them on the floor.
     */
    @Volatile private var rebuilding = false

    private fun bytesFor(rate: Int, ms: Int): Int = (rate * ms / 1000) * bytesPerSample

    private fun startupBytesFor(rate: Int): Int = bytesFor(rate, startupLatencyMs)

    private fun buildAndPlay(rate: Int, audioSessionId: Int): Boolean {
        val minBuffer = AudioTrack.getMinBufferSize(
            rate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            Log.w(TAG, "AudioTrack.getMinBufferSize failed: $minBuffer (rate=$rate)")
            return false
        }
        val bufferBytes = maxOf(minBuffer * 2, startupBytesFor(rate) * 2)

        val t = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setBufferSizeInBytes(bufferBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .apply { if (audioSessionId > 0) setSessionId(audioSessionId) }
                .build()
        } catch (ex: Throwable) {
            Log.e(TAG, "AudioTrack build failed: ${ex.message}")
            return false
        }

        if (t.state != AudioTrack.STATE_INITIALIZED) {
            Log.w(TAG, "AudioTrack not initialized (state=${t.state})")
            runCatching { t.release() }
            return false
        }

        t.play()
        track = t
        activeRateHz = rate
        return true
    }

    override fun start(audioSessionId: Int) {
        if (track != null) {
            Log.i(TAG, "start called while already running; ignoring")
            return
        }
        sessionId = audioSessionId
        buffer.reset(startupBytesFor(defaultSampleRateHz))
        if (!buildAndPlay(defaultSampleRateHz, audioSessionId)) return

        loopJob = scope.launch {
            while (isActive) {
                // Park during a rate rebuild rather than spinning on `continue`,
                // and crucially without touching the buffer — audio pulled here
                // would have nowhere to go.
                if (rebuilding) {
                    delay(REBUILD_PARK_MS)
                    continue
                }
                val active = track
                if (active == null) {
                    delay(IDLE_POLL_MS)
                    continue
                }
                val chunk = buffer.drain()
                if (chunk == null) {
                    delay(IDLE_POLL_MS)
                    continue
                }
                val written = active.write(chunk, 0, chunk.size, AudioTrack.WRITE_BLOCKING)
                if (written < 0) {
                    Log.w(TAG, "AudioTrack.write error: $written")
                }
            }
        }
    }

    override fun enqueue(pcm: ByteArray, sampleRateHz: Int) {
        if (sampleRateHz > 0 && track != null && sampleRateHz != activeRateHz && !rebuilding) {
            maybeRebuildForRate(sampleRateHz)
        }
        buffer.enqueue(pcm)
    }

    @Synchronized
    private fun maybeRebuildForRate(rate: Int) {
        if (track == null || rate <= 0 || rate == activeRateHz) return
        Log.i(TAG, "playback rate $activeRateHz → $rate; rebuilding AudioTrack")
        rebuilding = true
        try {
            val old = track
            track = null
            runCatching {
                old?.pause()
                old?.flush()
                old?.release()
            }
            val rebuilt = buildAndPlay(rate, sessionId)
            if (!rebuilt) {
                Log.w(TAG, "rebuild at $rate failed; restoring $activeRateHz")
                buildAndPlay(if (activeRateHz > 0) activeRateHz else defaultSampleRateHz, sessionId)
            }
            // Re-express the buffer's thresholds in the new rate's bytes. Without
            // this every threshold silently means a different duration and the
            // adaptation logic drifts.
            val newRate = activeRateHz.takeIf { it > 0 } ?: defaultSampleRateHz
            buffer.retarget(
                startupBytes = startupBytesFor(newRate),
                maxBytes = bytesFor(newRate, MAX_LATENCY_MS),
                stepBytes = bytesFor(newRate, GROWTH_STEP_MS),
                capBytes = bytesFor(newRate, MAX_BACKLOG_MS),
            )
        } finally {
            rebuilding = false
        }
    }

    /**
     * Stops playback. When [graceful] the buffered tail is played out first, so
     * the last translated word is never clipped.
     *
     * The previous implementation called `AudioTrack.stop()` and then `flush()`
     * immediately — and `flush()` discards exactly the audio `stop()` was
     * letting drain, so the "graceful" path cut the tail every time. Here we
     * drain the jitter buffer into the track, call `stop()` (which plays out
     * what the track already holds), and only then release.
     */
    override fun stop(graceful: Boolean) {
        loopJob?.cancel()
        loopJob = null
        val active = track
        track = null
        activeRateHz = 0
        rebuilding = false

        if (active == null) {
            buffer.clear()
            return
        }

        if (!graceful) {
            runCatching {
                active.pause()
                active.flush()
                active.release()
            }
            buffer.clear()
            return
        }

        // Play the tail out on the IO scope so the caller — usually the main
        // thread stopping a session — is never blocked waiting for audio.
        scope.launch {
            withTimeoutOrNull(DRAIN_TIMEOUT_MS) {
                while (buffer.pendingBytes > 0) {
                    val chunk = buffer.drain() ?: break
                    active.write(chunk, 0, chunk.size, AudioTrack.WRITE_BLOCKING)
                }
            }
            runCatching {
                // stop() lets the track's own buffer finish. Deliberately no
                // flush(): flushing discards exactly the audio stop() is
                // draining, which is what used to clip the final word.
                active.stop()
                active.release()
            }
            buffer.clear()
        }
    }

    override fun snapshot(): PlaybackSnapshot {
        val rate = activeRateHz.takeIf { it > 0 } ?: defaultSampleRateHz
        val bytesPerMs = (rate * bytesPerSample) / 1000
        fun toMs(bytes: Int) = if (bytesPerMs > 0) bytes / bytesPerMs else 0
        return PlaybackSnapshot(
            running = track != null,
            sampleRateHz = rate,
            bufferedMs = toMs(buffer.pendingBytes),
            targetLatencyMs = toMs(buffer.targetLatencyBytes),
            underruns = buffer.underrunCount,
            droppedChunks = buffer.droppedChunks,
        )
    }

    companion object {
        private const val TAG = "AudioPlayback"

        /** Ceiling for the adaptive buffer. Past this, latency hurts more than gaps. */
        private const val MAX_LATENCY_MS = 240

        /** How much cushion one underrun buys. */
        private const val GROWTH_STEP_MS = 20

        /** Backlog cap. Beyond this the speaker has moved on and old audio is noise. */
        private const val MAX_BACKLOG_MS = 1_200

        private const val IDLE_POLL_MS = 5L
        private const val REBUILD_PARK_MS = 2L
        private const val DRAIN_TIMEOUT_MS = 1_500L
    }
}
