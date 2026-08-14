package com.classeve.earslate.audio

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The two teardown races fixed in 0.4.4, exercised against the real framework.
 *
 * These cannot live in the JVM suite. `AudioTrack` and `AudioRecord` are stubs
 * off-device — `getMinBufferSize` returns 0 and every call is a no-op — so both
 * fixes shipped reasoning-verified only, which is exactly the gap this closes.
 *
 * Both tests are **repetition** tests. A race is not disproved by one clean
 * pass, and both of these fire only when a teardown overlaps a call already in
 * flight. The loops are sized so that the pre-fix code has many chances to lose
 * the race; the failure mode is a native crash or an IllegalStateException from
 * a thread with no handler, which takes the whole test process down rather than
 * failing an assertion. A green run here means the process survived, which is
 * the actual claim being made.
 */
@RunWith(AndroidJUnit4::class)
class AudioTeardownTest {

    @get:Rule
    val permission: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    private fun chunk(bytes: Int = 12_000) = ByteArray(bytes) { (it % 251).toByte() }

    /**
     * The reconnect pattern: a graceful stop hands the tail to a coroutine that
     * drains for up to 1.5 s, and reconnect attempt 1 has a **0 ms** backoff, so
     * the next session can start while that coroutine is still running.
     *
     * Before the fix both sessions shared one JitterBuffer, so the departing
     * drain called `clear()` on the arriving session's audio, and the old
     * AudioTrack was left playing alongside the new one. The assertion is that
     * audio enqueued AFTER a restart is still there — i.e. nobody cleared it.
     */
    @Test
    fun playbackSurvivesImmediateRestartAfterGracefulStop() {
        val engine = AndroidAudioPlaybackEngine()
        repeat(25) {
            engine.start()
            repeat(4) { engine.enqueue(chunk(), 24_000) }
            engine.stop(graceful = true)
            // No delay: this is the 0 ms reconnect backoff.
            engine.start()
            repeat(4) { engine.enqueue(chunk(), 24_000) }

            val snapshot = engine.snapshot()
            assertTrue(
                "the arriving session's audio was cleared by the departing drain",
                snapshot.bufferedMs > 0,
            )
            engine.stop(graceful = false)
        }
    }

    /**
     * Capture teardown while a blocking `AudioRecord.read()` is in flight.
     *
     * Cancelling a coroutine does not interrupt a blocking native call, so the
     * old `stop()` could free the AudioRecord from the caller's thread while the
     * capture loop was still inside `read()`. Freeing a native object under an
     * in-flight read is undefined; in practice it surfaces as an
     * IllegalStateException or a native abort from a thread with no handler.
     *
     * The loop starts capture, waits just long enough to be parked inside a
     * read, then stops — repeatedly, to give the race room to happen.
     */
    @Test
    fun captureStopDoesNotReleaseUnderAnInFlightRead() {
        var batches = 0
        repeat(40) {
            val engine = AndroidAudioCaptureEngine(framesPerBatch = 5)
            val sessionId = engine.start(onBatch = { batches++ })
            if (sessionId == 0) {
                // No usable mic on this image; nothing to prove here.
                engine.stop()
                return
            }
            // A 20 ms frame read is in flight for most of this window.
            Thread.sleep(25)
            engine.stop()
        }
        assertTrue("capture never delivered a batch; the test proved nothing", batches > 0)
    }

    /**
     * Rapid stop/start with no drain in between — the immediate teardown path,
     * which now also joins the loop before releasing the track.
     */
    @Test
    fun playbackSurvivesRapidImmediateStopStart() {
        val engine = AndroidAudioPlaybackEngine()
        repeat(50) {
            engine.start()
            engine.enqueue(chunk(), 24_000)
            engine.stop(graceful = false)
        }
        engine.start()
        engine.enqueue(chunk(), 24_000)
        val snapshot = engine.snapshot()
        assertTrue("engine unusable after rapid cycling", snapshot.running)
        engine.stop(graceful = false)
    }

    /**
     * A mid-stream sample-rate change rebuilds the track. The buffer must be
     * re-expressed in the new rate's bytes, or every threshold silently means a
     * different duration.
     */
    @Test
    fun playbackRebuildsForANewSampleRateWithoutLosingTheStream() {
        val engine = AndroidAudioPlaybackEngine()
        engine.start()
        repeat(3) { engine.enqueue(chunk(), 24_000) }
        assertEquals(24_000, engine.snapshot().sampleRateHz)

        repeat(3) { engine.enqueue(chunk(), 16_000) }
        val after = engine.snapshot()
        assertEquals("track did not rebuild at the new rate", 16_000, after.sampleRateHz)
        assertTrue("stream lost across the rebuild", after.running)
        engine.stop(graceful = false)
    }
}
