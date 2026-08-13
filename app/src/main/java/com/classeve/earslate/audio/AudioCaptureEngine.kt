package com.classeve.earslate.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Owns mic capture.
 *   - VOICE_RECOGNITION source — the documented source for feeding speech to a
 *     recognizer/translator. It gives CLEAN, minimally-processed audio: no
 *     telephony AGC, no aggressive call-grade echo cancellation. The earlier
 *     VOICE_COMMUNICATION source (+ MODE_IN_COMMUNICATION + AEC/NS) is tuned for
 *     phone CALLS and mangles/over-suppresses speech — it was why the model
 *     "couldn't recognize speech" on-device. Speaker-mode feedback is handled by
 *     the half-duplex mic gate in the SessionCoordinator, not by call-grade AEC.
 *   - 16 kHz mono PCM16
 *   - 20 ms internal frames, ~100 ms send batches
 *   - Dedicated high-priority IO coroutine
 *
 * Every captured batch is sent. There is deliberately no local VAD gate: the
 * translate model runs its own voice-activity detection, and gating locally
 * clipped quiet/far-field speech (the "it didn't hear me" failure). Billing is
 * by session wall-clock, not audio volume, so over-sending costs nothing.
 */
interface AudioCaptureEngine {
    /**
     * Returns the AudioRecord's audio session id, or 0 on construction failure.
     *
     * [onCaptureError] fires at most once, from the capture coroutine, if a
     * FATAL AudioRecord.read() error ends capture after a successful start
     * (ERROR_INVALID_OPERATION / ERROR_BAD_VALUE / ERROR_DEAD_OBJECT). Without
     * it, capture died silently: the Gemini socket stayed open, stateStore
     * stayed at READY/LISTENING, and no audio was ever sent again — the
     * caller had no way to learn the session was actually dead.
     */
    fun start(onBatch: (ByteArray) -> Unit, onCaptureError: () -> Unit = {}): Int
    fun stop()
}

class AndroidAudioCaptureEngine(
    private val sampleRateHz: Int = 16_000,
    private val frameMs: Int = 20,
    private val framesPerBatch: Int = 2,
    private val hasRecordAudioPermission: () -> Boolean = { true },
) : AudioCaptureEngine {

    private val samplesPerFrame = (sampleRateHz * frameMs) / 1000
    private val bytesPerFrame = samplesPerFrame * 2
    private val bytesPerBatch = bytesPerFrame * framesPerBatch

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var record: AudioRecord? = null
    @Volatile private var loopJob: Job? = null

    @SuppressLint("MissingPermission")
    override fun start(onBatch: (ByteArray) -> Unit, onCaptureError: () -> Unit): Int {
        if (loopJob != null) {
            Log.i(TAG, "start called while already capturing; ignoring")
            return record?.audioSessionId ?: 0
        }
        if (!hasRecordAudioPermission()) {
            Log.w(TAG, "AudioRecord start rejected: RECORD_AUDIO permission missing")
            return 0
        }

        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            Log.w(TAG, "AudioRecord.getMinBufferSize failed: $minBuffer")
            return 0
        }
        val bufferBytes = maxOf(minBuffer * 4, bytesPerBatch * 4)

        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRateHz,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "AudioRecord construction failed: ${t.message}")
            return 0
        }

        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "AudioRecord not initialized (state=${rec.state})")
            rec.release()
            return 0
        }

        // Deliberately NO AcousticEchoCanceler / NoiseSuppressor: on-device they
        // over-suppressed quiet/far speech and distorted the signal the model
        // needs. Clean audio in → correct, fast translation out.

        try {
            rec.startRecording()
        } catch (t: Throwable) {
            Log.e(TAG, "AudioRecord.startRecording failed: ${t.message}")
            rec.release()
            return 0
        }

        record = rec

        loopJob = scope.launch {
            val frame = ByteArray(bytesPerFrame)
            val batch = ByteArray(bytesPerBatch)
            var batchOffset = 0

            fun flushBatch() {
                if (batchOffset == 0) return
                onBatch(batch.copyOf(batchOffset))
                batchOffset = 0
            }

            fun append(data: ByteArray) {
                var srcOffset = 0
                while (srcOffset < data.size) {
                    val copy = minOf(bytesPerBatch - batchOffset, data.size - srcOffset)
                    System.arraycopy(data, srcOffset, batch, batchOffset, copy)
                    batchOffset += copy
                    srcOffset += copy
                    if (batchOffset >= bytesPerBatch) flushBatch()
                }
            }

            try {
                while (isActive) {
                    val read = rec.read(frame, 0, bytesPerFrame)
                    if (read <= 0) {
                        if (read == AudioRecord.ERROR_INVALID_OPERATION ||
                            read == AudioRecord.ERROR_BAD_VALUE ||
                            read == AudioRecord.ERROR_DEAD_OBJECT
                        ) {
                            Log.w(TAG, "AudioRecord.read error: $read")
                            runCatching { onCaptureError() }
                            break
                        }
                        continue
                    }

                    append(if (read == bytesPerFrame) frame else frame.copyOf(read))
                }
            } finally {
                flushBatch()
                // The capture loop owns the AudioRecord's teardown, and is the
                // ONLY place that releases it.
                //
                // stop() used to release from the caller's thread while this
                // coroutine could still be parked inside the blocking
                // rec.read() below. Cancelling a coroutine does not interrupt a
                // blocking native call, so the two raced: release() freeing the
                // native object under an in-flight read is undefined, and
                // presents as an IllegalStateException or a native crash inside
                // AudioRecord — from a thread with no handler, so it takes the
                // process with it. Releasing here means the read has provably
                // returned before the object is freed, which removes the race
                // rather than narrowing its window.
                runCatching { rec.stop() }
                runCatching { rec.release() }
            }
        }

        return rec.audioSessionId
    }

    override fun stop() {
        val job = loopJob
        loopJob = null
        val rec = record
        record = null
        // stop() is safe to call from another thread while a read is blocked —
        // it is what makes that read return — so the loop can observe
        // cancellation promptly. release() is NOT safe that way, and is left to
        // the loop's own finally above.
        runCatching { rec?.stop() }
        job?.cancel()
    }

    companion object {
        private const val TAG = "AudioCapture"
    }
}
