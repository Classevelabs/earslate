package com.classeve.earslate.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
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
 * VAD gating is optional — pass a [VadGate] to enable it, pass null to send
 * every captured batch (default now: the translate model has its own VAD).
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
    private val vadGate: VadGate? = null,
    private val hasRecordAudioPermission: () -> Boolean = { true },
) : AudioCaptureEngine {

    private val samplesPerFrame = (sampleRateHz * frameMs) / 1000
    private val bytesPerFrame = samplesPerFrame * 2
    private val bytesPerBatch = bytesPerFrame * framesPerBatch

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var record: AudioRecord? = null
    @Volatile private var loopJob: Job? = null
    @Volatile private var aec: AcousticEchoCanceler? = null
    @Volatile private var ns: NoiseSuppressor? = null

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
            releaseEffects()
            rec.release()
            return 0
        }

        record = rec
        vadGate?.reset()

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

                    val effective = if (read == bytesPerFrame) frame else frame.copyOf(read)

                    if (vadGate == null) {
                        append(effective)
                    } else {
                        when (val result = vadGate.process(effective)) {
                            is VadResult.Silence -> Unit
                            is VadResult.Opening -> {
                                result.preRoll.forEach(::append)
                                append(result.current)
                            }
                            is VadResult.Speaking -> append(result.frame)
                            is VadResult.Closing -> flushBatch()
                        }
                    }
                }
            } finally {
                flushBatch()
            }
        }

        return rec.audioSessionId
    }

    override fun stop() {
        loopJob?.cancel()
        loopJob = null
        releaseEffects()
        record?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        record = null
    }

    private fun attachEffects(sessionId: Int) {
        if (sessionId == 0) {
            Log.w(TAG, "invalid audio session id; skipping AEC/NS")
            return
        }
        if (AcousticEchoCanceler.isAvailable()) {
            aec = runCatching { AcousticEchoCanceler.create(sessionId) }
                .onFailure { Log.w(TAG, "AEC create failed: ${it.message}") }
                .getOrNull()
            aec?.let {
                runCatching { it.enabled = true }
                Log.i(TAG, "AEC attached enabled=${it.enabled}")
            }
        } else {
            Log.w(TAG, "AEC unavailable on this device")
        }
        if (NoiseSuppressor.isAvailable()) {
            ns = runCatching { NoiseSuppressor.create(sessionId) }
                .onFailure { Log.w(TAG, "NS create failed: ${it.message}") }
                .getOrNull()
            ns?.let {
                runCatching { it.enabled = true }
                Log.i(TAG, "NS attached enabled=${it.enabled}")
            }
        }
    }

    private fun releaseEffects() {
        aec?.let {
            runCatching { it.enabled = false }
            runCatching { it.release() }
        }
        aec = null
        ns?.let {
            runCatching { it.enabled = false }
            runCatching { it.release() }
        }
        ns = null
    }

    companion object {
        private const val TAG = "AudioCapture"
    }
}
