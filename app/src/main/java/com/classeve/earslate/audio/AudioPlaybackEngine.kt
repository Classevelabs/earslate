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

    /**
     * The provider has finished speaking. Tells the jitter buffer that running dry
     * next is expected, so end-of-turn silence is not mistaken for a network
     * stutter and charged as extra latency.
     */
    fun notifyTurnEnd()

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
     * Floor for the adaptive buffer.
     *
     * This was 40 ms, and that single number was the reason playback sounded like
     * a bad phone call. The provider streams audio in chunks of ~100 ms, over
     * mobile data, so 40 ms of cushion is less than half of one chunk: any chunk
     * that arrives even slightly late finds the buffer already empty. The result
     * was an underrun on virtually every utterance, and because each underrun
     * disarms the buffer until the target refills, every one of them was an
     * audible gap.
     *
     * 180 ms is a little under two chunk periods — enough that a single late or
     * bursty chunk is absorbed silently. It costs nothing perceptible: the model
     * itself takes on the order of a second to produce a translation, so 180 ms
     * is well inside the noise of that. Smooth beats theoretically-snappy.
     */
    private val startupLatencyMs: Int = 180,
) : AudioPlaybackEngine {

    private val bytesPerSample = 2

    private val buffer = JitterBuffer(
        startupBytes = startupBytesFor(defaultSampleRateHz),
        maxTargetBytes = bytesFor(defaultSampleRateHz, MAX_LATENCY_MS),
        growthStepBytes = bytesFor(defaultSampleRateHz, GROWTH_STEP_MS),
        maxBufferedBytes = bytesFor(defaultSampleRateHz, MAX_BACKLOG_MS),
        recoveryBytes = bytesFor(defaultSampleRateHz, RECOVERY_QUIET_MS),
    )

    /**
     * One frame of digital silence, written to keep the track's clock running
     * through a buffer gap. See the drain loop.
     */
    @Volatile private var silenceFrame: ByteArray = ByteArray(0)

    /** Consecutive silence frames written during the current gap. */
    private var silenceRun = 0
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
        // Rate-dependent, so it is rebuilt with the track.
        silenceFrame = ByteArray(bytesFor(rate, SILENCE_FRAME_MS))
        silenceRun = 0
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
                    // Nothing ready. If we were mid-utterance, keep the track fed
                    // with silence instead of letting it starve: a starved
                    // AudioTrack underruns in hardware, which is heard as a click
                    // or a rasp at the seam and is exactly what made a gap sound
                    // like a dropped phone call. Writing comfort silence turns the
                    // same gap into an inaudible pause and keeps the timeline
                    // continuous.
                    //
                    // Bounded by MAX_SILENCE_RUN so a genuine end-of-turn does not
                    // sit here forever adding latency — after that we fall back to
                    // idle polling and let the buffer re-arm properly.
                    if (silenceRun < MAX_SILENCE_RUN && silenceFrame.isNotEmpty()) {
                        silenceRun++
                        active.write(silenceFrame, 0, silenceFrame.size, AudioTrack.WRITE_BLOCKING)
                    } else {
                        delay(IDLE_POLL_MS)
                    }
                    continue
                }
                silenceRun = 0
                val written = active.write(chunk, 0, chunk.size, AudioTrack.WRITE_BLOCKING)
                if (written < 0) {
                    Log.w(TAG, "AudioTrack.write error: $written")
                }
            }
        }
    }

    override fun notifyTurnEnd() {
        buffer.markTurnEnd()
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
                recoveryTargetBytes = bytesFor(newRate, RECOVERY_QUIET_MS),
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

        /**
         * Ceiling for the adaptive buffer. 240 ms was not enough headroom for a
         * congested mobile link — the buffer would peg at the ceiling and keep
         * underrunning with nowhere left to grow. 600 ms is still comfortably
         * below the point where a listener notices added delay in a translated
         * conversation.
         */
        private const val MAX_LATENCY_MS = 600

        /**
         * How much cushion one underrun buys. Deliberately coarse: at 20 ms it
         * took ten separate audible gaps to climb from the floor to the ceiling.
         * At 60 ms a bad link is absorbed within one or two.
         */
        private const val GROWTH_STEP_MS = 60

        /** Backlog cap. Beyond this the speaker has moved on and old audio is noise. */
        private const val MAX_BACKLOG_MS = 1_200

        /**
         * Clean audio required before the buffer gives back one [GROWTH_STEP_MS].
         * Long on purpose — latency earned by a real stutter should not be
         * surrendered after a couple of seconds of calm, because that is what
         * makes the target oscillate and the stream stutter all over again.
         */
        private const val RECOVERY_QUIET_MS = 12_000

        /** Duration of one comfort-silence frame written during a buffer gap. */
        private const val SILENCE_FRAME_MS = 20

        /**
         * Cap on consecutive comfort-silence frames — 5 × 20 ms = 100 ms.
         *
         * Sized from measurement, not taste: arrival gaps on-device were 248 ms
         * mean against a 291 ms worst case, so ~43 ms of lateness is what actually
         * needs covering. 100 ms is a bit over double that. Keeping the cap tight
         * matters because silence written here sits *ahead* of the next real
         * utterance in the track — a generous cap would trade a click for
         * permanent added delay, which is a worse bargain.
         */
        private const val MAX_SILENCE_RUN = 5

        private const val IDLE_POLL_MS = 5L
        private const val REBUILD_PARK_MS = 2L
        private const val DRAIN_TIMEOUT_MS = 1_500L
    }
}
