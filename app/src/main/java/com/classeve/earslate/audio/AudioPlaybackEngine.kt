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

/**
 * Owns the translated-audio playback path.
 *
 *   - 24 kHz mono PCM16 stream (Gemini Live native audio output rate)
 *   - AudioTrack STREAM mode
 *   - USAGE_MEDIA playback; no hardware-AEC coupling — the capture engine
 *     deliberately disables AEC/NS (raw ambient capture is the product), so
 *     echo control relies on the earbuds-recommended listening setup, not
 *     the platform reference mix.
 *   - Accepts an optional audio-session id, but callers pass GENERATE by
 *     default; there is no cross-engine session correlation in practice.
 *   - JitterBuffer with ~120 ms startup target
 *   - Graceful drain on stop so the last played word is not cut
 */
interface AudioPlaybackEngine {
    fun start(audioSessionId: Int = AudioManager.AUDIO_SESSION_ID_GENERATE)
    fun enqueue(pcm24k: ByteArray, sampleRateHz: Int = 24_000)
    fun stop(graceful: Boolean = true)
}

class AndroidAudioPlaybackEngine(
    private val defaultSampleRateHz: Int = 24_000,
    private val startupLatencyMs: Int = 60,
) : AudioPlaybackEngine {

    private val bytesPerSample = 2

    private val buffer = JitterBuffer(startupBytesFor(defaultSampleRateHz))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var track: AudioTrack? = null
    @Volatile private var loopJob: Job? = null
    /** Rate the live AudioTrack is currently running at. 0 until [start]. */
    @Volatile private var activeRateHz: Int = 0
    /** Session id captured at [start] so a runtime rate change can rebuild with the same AEC coupling. */
    @Volatile private var sessionId: Int = AudioManager.AUDIO_SESSION_ID_GENERATE

    private fun startupBytesFor(rate: Int): Int =
        (rate * startupLatencyMs / 1000) * bytesPerSample

    /**
     * Builds + starts an AudioTrack at [rate] and publishes it to [track]. The
     * drain loop reads the [track] field each iteration so swapping it here is
     * picked up without restarting the loop. Returns true on success.
     */
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
        val startupBytes = startupBytesFor(rate)
        val bufferBytes = maxOf(minBuffer * 2, startupBytes * 2)

        val t = try {
            val builder = AudioTrack.Builder()
                .setAudioAttributes(
                    // USAGE_MEDIA: route translated audio through the normal media
                    // path (earbuds via A2DP, or the speaker) at full clarity. We
                    // are NOT in call mode and run no AEC, so the old
                    // USAGE_VOICE_COMMUNICATION (earpiece/telephony) routing would
                    // only make playback quiet and oddly-routed.
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
            if (audioSessionId > 0) {
                builder.setSessionId(audioSessionId)
            }
            builder.build()
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
        if (!buildAndPlay(defaultSampleRateHz, audioSessionId)) return

        loopJob = scope.launch {
            while (isActive) {
                val chunk = buffer.drain()
                if (chunk == null) {
                    delay(10)
                    continue
                }
                // Read the field each tick so a mid-stream rate rebuild is picked up.
                val active = track ?: continue
                val written = active.write(chunk, 0, chunk.size, AudioTrack.WRITE_BLOCKING)
                if (written < 0) {
                    Log.w(TAG, "AudioTrack.write error: $written")
                }
            }
        }
    }

    override fun enqueue(pcm24k: ByteArray, sampleRateHz: Int) {
        // Robust to the real Gemini output rate: rebuild the AudioTrack if the
        // header advertises a different (valid) rate than we're playing at. A
        // 0/invalid rate is ignored — we keep the current track rather than crash.
        if (sampleRateHz > 0 && track != null && sampleRateHz != activeRateHz) {
            maybeRebuildForRate(sampleRateHz)
        }
        buffer.enqueue(pcm24k)
    }

    @Synchronized
    private fun maybeRebuildForRate(rate: Int) {
        // Re-check under lock: another enqueue may have already rebuilt.
        if (track == null || rate <= 0 || rate == activeRateHz) return
        Log.i(TAG, "playback rate $activeRateHz → $rate; rebuilding AudioTrack")
        val old = track
        track = null // pause the drain loop's writes while we swap
        runCatching {
            if (old != null) {
                old.pause()
                old.flush()
                old.release()
            }
        }
        if (!buildAndPlay(rate, sessionId)) {
            // Fall back to the previous rate so playback isn't lost permanently.
            Log.w(TAG, "rebuild at $rate failed; restoring $activeRateHz")
            buildAndPlay(if (activeRateHz > 0) activeRateHz else defaultSampleRateHz, sessionId)
        }
    }

    override fun stop(graceful: Boolean) {
        loopJob?.cancel()
        loopJob = null
        track?.let {
            runCatching {
                if (graceful) it.stop() else it.pause()
                it.flush()
                it.release()
            }
        }
        track = null
        activeRateHz = 0
        buffer.clear()
    }

    companion object {
        private const val TAG = "AudioPlayback"
    }
}
