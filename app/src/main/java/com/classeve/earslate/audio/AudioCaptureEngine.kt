package com.classeve.earslate.audio

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
 * Owns mic capture. Blueprint §8.3:
 *   - VOICE_RECOGNITION source
 *   - 16 kHz mono PCM16
 *   - 20 ms internal frames, 60 ms send batches
 *   - Dedicated high-priority IO coroutine
 *
 * VAD gating is optional — pass a [VadGate] to enable it, pass null to send
 * every captured batch. V1 ships with the gate wired so we do not flood Gemini
 * with silence.
 */
interface AudioCaptureEngine {
    fun start(onBatch: (ByteArray) -> Unit)
    fun stop()
}

class AndroidAudioCaptureEngine(
    private val sampleRateHz: Int = 16_000,
    private val frameMs: Int = 20,
    private val framesPerBatch: Int = 2,
    private val vadGate: VadGate? = null,
) : AudioCaptureEngine {

    private val samplesPerFrame = (sampleRateHz * frameMs) / 1000
    private val bytesPerFrame = samplesPerFrame * 2
    private val bytesPerBatch = bytesPerFrame * framesPerBatch

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var record: AudioRecord? = null
    @Volatile private var loopJob: Job? = null

    override fun start(onBatch: (ByteArray) -> Unit) {
        if (loopJob != null) {
            Log.i(TAG, "start called while already capturing; ignoring")
            return
        }

        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            Log.w(TAG, "AudioRecord.getMinBufferSize failed: $minBuffer")
            return
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
            return
        }

        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "AudioRecord not initialized (state=${rec.state})")
            rec.release()
            return
        }

        try {
            rec.startRecording()
        } catch (t: Throwable) {
            Log.e(TAG, "AudioRecord.startRecording failed: ${t.message}")
            rec.release()
            return
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
    }

    override fun stop() {
        loopJob?.cancel()
        loopJob = null
        record?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        record = null
    }

    companion object {
        private const val TAG = "AudioCapture"
    }
}
