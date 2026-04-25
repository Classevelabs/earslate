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
 *   - USAGE_VOICE_COMMUNICATION so this output becomes part of the HAL's
 *     AEC reference mix — paired with the capture engine's VOICE_COMMUNICATION
 *     source, the platform can subtract this signal from the mic stream.
 *   - Optional session-id coupling with the capture AudioRecord so hardware
 *     AEC on OEM HALs can correlate reference and error signals.
 *   - JitterBuffer with ~120 ms startup target
 *   - Graceful drain on stop so the last played word is not cut
 */
interface AudioPlaybackEngine {
    fun start(audioSessionId: Int = AudioManager.AUDIO_SESSION_ID_GENERATE)
    fun enqueue(pcm24k: ByteArray)
    fun stop(graceful: Boolean = true)
}

class AndroidAudioPlaybackEngine(
    private val sampleRateHz: Int = 24_000,
    private val startupLatencyMs: Int = 60,
) : AudioPlaybackEngine {

    private val bytesPerSample = 2
    private val startupBytes = (sampleRateHz * startupLatencyMs / 1000) * bytesPerSample

    private val buffer = JitterBuffer(startupBytes)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var track: AudioTrack? = null
    @Volatile private var loopJob: Job? = null

    override fun start(audioSessionId: Int) {
        if (track != null) {
            Log.i(TAG, "start called while already running; ignoring")
            return
        }

        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRateHz,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            Log.w(TAG, "AudioTrack.getMinBufferSize failed: $minBuffer")
            return
        }
        val bufferBytes = maxOf(minBuffer * 2, startupBytes * 2)

        val t = try {
            val builder = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
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
                .setBufferSizeInBytes(bufferBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
            if (audioSessionId > 0) {
                builder.setSessionId(audioSessionId)
            }
            builder.build()
        } catch (ex: Throwable) {
            Log.e(TAG, "AudioTrack build failed: ${ex.message}")
            return
        }

        if (t.state != AudioTrack.STATE_INITIALIZED) {
            Log.w(TAG, "AudioTrack not initialized (state=${t.state})")
            runCatching { t.release() }
            return
        }

        t.play()
        track = t

        loopJob = scope.launch {
            while (isActive) {
                val chunk = buffer.drain()
                if (chunk == null) {
                    delay(10)
                    continue
                }
                val written = t.write(chunk, 0, chunk.size, AudioTrack.WRITE_BLOCKING)
                if (written < 0) {
                    Log.w(TAG, "AudioTrack.write error: $written")
                }
            }
        }
    }

    override fun enqueue(pcm24k: ByteArray) {
        buffer.enqueue(pcm24k)
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
        buffer.clear()
    }

    companion object {
        private const val TAG = "AudioPlayback"
    }
}
