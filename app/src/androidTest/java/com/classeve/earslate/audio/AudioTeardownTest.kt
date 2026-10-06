package com.classeve.earslate.audio

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/**
 * The audio engines against the real framework. `AudioTrack` and `AudioRecord`
 * are stubs off-device, so none of this can live in the JVM suite.
 *
 * The teardown tests are repetition tests: a release racing a blocking read or
 * write shows up as a native crash from a thread with no handler, which takes
 * the whole test process down. A green run means the process survived.
 */
@RunWith(AndroidJUnit4::class)
class AudioTeardownTest {

    @get:Rule
    val permission: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    private fun speech(ms: Int = 250, rateHz: Int = 24_000) =
        ByteArray(rateHz * ms / 1000 * 2) { ((it % 97) * 2).toByte() }

    private fun await(what: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for $what" }
            Thread.sleep(10)
        }
    }

    // ── playback ────────────────────────────────────────────────────────

    @Test
    fun speechWrittenToALaneIsPlayedOut() {
        val engine = AndroidAudioPlaybackEngine()
        engine.start()
        repeat(8) { engine.write(lane = 1, speech(), 24_000, voiced = true) }
        assertTrue("speech is queued", engine.snapshot().waitingMs > 0)
        await("the lane to play it out") { engine.snapshot().waitingMs == 0 }
        engine.stop(graceful = false)
    }

    @Test
    fun twoDirectionsPlayOnTheirOwnLanes() {
        val engine = AndroidAudioPlaybackEngine()
        engine.start()
        repeat(4) {
            engine.write(lane = 1, speech(), 24_000, voiced = true)
            engine.write(lane = 2, speech(), 24_000, voiced = false)
        }
        assertEquals(2, engine.snapshot().lanes)
        await("both lanes to drain") { engine.snapshot().waitingMs == 0 }
        assertEquals("a steady stream never ran dry", 0, engine.snapshot().underruns)
        engine.stop(graceful = false)
    }

    @Test
    fun heldSpeechWaitsForItsRelease() {
        val engine = AndroidAudioPlaybackEngine()
        engine.start()
        engine.setConsecutive(true)
        repeat(4) { engine.write(lane = 1, speech(), 24_000, voiced = true) }
        Thread.sleep(600)
        assertEquals("nothing is played while held", 1_000, engine.snapshot().waitingMs)
        assertFalse(engine.snapshot().audible)

        engine.release()
        await("the held speech to be heard") { engine.snapshot().audible }
        await("it to finish") { engine.snapshot().waitingMs == 0 }
        engine.stop(graceful = false)
    }

    // It was made waiting, after the word to speak had already been given,
    // and waited for ever with the microphone closed.
    @Test
    fun aLaneThatJoinsWhileTheOthersAreSpeakingIsHeardWithThem() {
        val engine = AndroidAudioPlaybackEngine()
        engine.start()
        engine.setConsecutive(true)
        repeat(4) { engine.write(lane = 1, speech(), 24_000, voiced = true) }
        engine.release()
        await("the first lane to be heard") { engine.snapshot().audible }

        repeat(4) { engine.write(lane = 2, speech(), 24_000, voiced = true) }
        await("both lanes to say everything", 8_000) { engine.snapshot().waitingMs == 0 }
        await("the loudspeaker to fall silent") { !engine.snapshot().audible }

        // Holding again is what the next sentence waits behind.
        engine.hold()
        repeat(4) { engine.write(lane = 2, speech(), 24_000, voiced = true) }
        Thread.sleep(600)
        assertEquals("held again until the next release", 1_000, engine.snapshot().waitingMs)
        engine.stop(graceful = false)
    }

    // A restart with no pause between is the reconnect path.
    @Test
    fun playbackSurvivesImmediateRestartAfterGracefulStop() {
        val engine = AndroidAudioPlaybackEngine()
        repeat(25) { round ->
            engine.start()
            repeat(4) { engine.write(lane = round * 2, speech(), 24_000, voiced = true) }
            engine.stop(graceful = true)
            engine.start()
            repeat(4) { engine.write(lane = round * 2 + 1, speech(), 24_000, voiced = true) }
            assertTrue(
                "the arriving session's audio was lost to the departing one",
                engine.snapshot().waitingMs > 0,
            )
            engine.stop(graceful = false)
        }
    }

    @Test
    fun playbackSurvivesRapidImmediateStopStart() {
        val engine = AndroidAudioPlaybackEngine()
        repeat(50) { round ->
            engine.start()
            engine.write(lane = round, speech(), 24_000, voiced = true)
            engine.stop(graceful = false)
        }
        engine.start()
        engine.write(lane = 1_000, speech(), 24_000, voiced = true)
        assertTrue("engine unusable after rapid cycling", engine.snapshot().running)
        engine.stop(graceful = false)
        await("every lane to be released") { engine.snapshot().lanes == 0 }
    }

    @Test
    fun aRetiredLaneFinishesAndIsFreed() {
        val engine = AndroidAudioPlaybackEngine()
        engine.start()
        repeat(4) { engine.write(lane = 7, speech(), 24_000, voiced = true) }
        engine.retire(7)
        await("the retired lane to finish and go") { engine.snapshot().lanes == 0 }
        engine.stop(graceful = false)
    }

    @Test
    fun aLaneFollowsAChangeOfSampleRate() {
        val engine = AndroidAudioPlaybackEngine()
        engine.start()
        repeat(3) { engine.write(lane = 1, speech(rateHz = 24_000), 24_000, voiced = true) }
        repeat(3) { engine.write(lane = 1, speech(rateHz = 16_000), 16_000, voiced = true) }
        assertTrue(engine.snapshot().running)
        await("both rates to play out") { engine.snapshot().waitingMs == 0 }
        engine.stop(graceful = false)
    }

    // ── capture ─────────────────────────────────────────────────────────

    private fun framesAt(sampleRateHz: Int, frameMs: Int): Pair<Int, Int> {
        val engine = AndroidAudioCaptureEngine()
        val frames = AtomicInteger()
        val bytes = AtomicInteger()
        val started = engine.start(sampleRateHz, frameMs, onFrame = {
            frames.incrementAndGet()
            bytes.set(it.size)
        }, onError = {})
        assumeTrue("no usable microphone on this image", started)
        Thread.sleep(1_200)
        engine.stop()
        return frames.get() to bytes.get()
    }

    // Gemini takes 16 kHz in 100 ms frames.
    @Test
    fun theMicrophoneDeliversGeminiFrames() {
        val (frames, bytes) = framesAt(16_000, 100)
        assertEquals("100 ms of 16 kHz PCM16", 3_200, bytes)
        assertTrue("about ten a second: $frames", frames in 8..14)
    }

    // OpenAI takes 24 kHz only, so the microphone is opened at 24 kHz.
    @Test
    fun theMicrophoneDeliversOpenAiFrames() {
        val (frames, bytes) = framesAt(24_000, 200)
        assertEquals("200 ms of 24 kHz PCM16", 9_600, bytes)
        assertTrue("about five a second: $frames", frames in 4..7)
    }

    @Test
    fun captureStopDoesNotReleaseUnderAnInFlightRead() {
        val frames = AtomicInteger()
        repeat(40) {
            val engine = AndroidAudioCaptureEngine()
            val started = engine.start(16_000, 100, onFrame = { frames.incrementAndGet() }, onError = {})
            assumeTrue("no usable microphone on this image", started)
            // A read is in flight for most of this window.
            Thread.sleep(25)
            engine.stop()
        }
        Thread.sleep(300)
    }

    @Test
    fun startingAgainReplacesTheRunningCapture() {
        val engine = AndroidAudioCaptureEngine()
        val first = AtomicInteger()
        val second = AtomicInteger()
        assumeTrue(engine.start(16_000, 100, onFrame = { first.incrementAndGet() }, onError = {}))
        Thread.sleep(400)
        assertTrue(engine.start(24_000, 200, onFrame = { second.incrementAndGet() }, onError = {}))
        val firstSoFar = first.get()
        Thread.sleep(900)
        engine.stop()

        assertTrue("the second capture delivers: ${second.get()}", second.get() >= 2)
        assertTrue("and the first has stopped", first.get() - firstSoFar <= 1)
    }
}
