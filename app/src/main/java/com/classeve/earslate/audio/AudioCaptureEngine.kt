package com.classeve.earslate.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import java.util.concurrent.Executor

/**
 * Owns the microphone. Every frame is delivered: the translate model does its
 * own voice detection, and gating here clips quiet and far-away speech.
 */
interface AudioCaptureEngine {

    /**
     * Opens the microphone at [sampleRateHz] and delivers PCM16 mono frames of
     * [frameMs] to [onFrame], on the capture thread.
     *
     * [onError] fires at most once, if the microphone is lost after a
     * successful start.
     *
     * [onTaken] says true when the system starts handing this app silence in
     * place of the microphone, as it does for a phone call, and false when the
     * microphone is given back. Frames keep arriving all the while.
     *
     * @return false when the microphone could not be opened.
     */
    fun start(
        sampleRateHz: Int,
        frameMs: Int,
        onFrame: (ByteArray) -> Unit,
        onError: () -> Unit,
        onTaken: (Boolean) -> Unit = {},
    ): Boolean

    fun stop()
}

class AndroidAudioCaptureEngine(
    private val hasRecordAudioPermission: () -> Boolean = { true },
) : AudioCaptureEngine {

    private class Capture(val record: AudioRecord) {
        @Volatile var active = true
    }

    @Volatile private var capture: Capture? = null

    @SuppressLint("MissingPermission")
    override fun start(
        sampleRateHz: Int,
        frameMs: Int,
        onFrame: (ByteArray) -> Unit,
        onError: () -> Unit,
        onTaken: (Boolean) -> Unit,
    ): Boolean {
        stop()
        if (!hasRecordAudioPermission()) {
            Log.w(TAG, "RECORD_AUDIO permission missing")
            return false
        }
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            Log.w(TAG, "no input at $sampleRateHz Hz ($minBuffer)")
            return false
        }
        val frameBytes = sampleRateHz * frameMs / 1000 * 2

        val record = try {
            AudioRecord(
                // The source meant for feeding a recogniser: no call-grade gain
                // control or noise suppression, which mangle far-away speech.
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRateHz,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer * 2, frameBytes * 4),
            )
        } catch (t: Exception) {
            Log.e(TAG, "AudioRecord construction failed: ${t.message}")
            return false
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }

        val session = Capture(record)
        // A phone call does not stop the record: it goes on delivering frames,
        // of silence. Asked for before recording starts, so that a session
        // begun in the middle of a call is told as well.
        val taken = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
                val mine = configs.firstOrNull { it.clientAudioSessionId == record.audioSessionId } ?: return
                if (session.active) runCatching { onTaken(mine.isClientSilenced) }
            }
        }
        runCatching { record.registerAudioRecordingCallback(Executor(Runnable::run), taken) }
        fun release() {
            runCatching { record.unregisterAudioRecordingCallback(taken) }
            runCatching { record.release() }
        }

        try {
            record.startRecording()
        } catch (t: Exception) {
            Log.e(TAG, "startRecording failed: ${t.message}")
            release()
            return false
        }
        // Another app holding the microphone leaves the record idle, silently.
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            release()
            return false
        }

        capture = session
        Thread({
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }
            val frame = ByteArray(frameBytes)
            var filled = 0
            try {
                while (session.active) {
                    val read = record.read(frame, filled, frameBytes - filled)
                    if (read < 0) {
                        if (session.active) {
                            Log.w(TAG, "AudioRecord.read error $read")
                            runCatching { onError() }
                        }
                        break
                    }
                    filled += read
                    if (filled == frameBytes) {
                        runCatching { onFrame(frame.copyOf()) }
                        filled = 0
                    }
                }
            } finally {
                // Released here, by the thread that reads: freeing the record
                // under a read still in flight crashes the process.
                runCatching { record.stop() }
                release()
            }
        }, "earslate-capture").start()
        return true
    }

    override fun stop() {
        val session = capture ?: return
        capture = null
        session.active = false
        // Safe from here, and it is what makes a blocked read return.
        runCatching { session.record.stop() }
    }

    companion object {
        private const val TAG = "AudioCapture"
    }
}
