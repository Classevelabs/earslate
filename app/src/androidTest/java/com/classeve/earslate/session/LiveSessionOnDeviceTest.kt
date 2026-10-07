package com.classeve.earslate.session

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.classeve.earslate.audio.AndroidAudioPlaybackEngine
import com.classeve.earslate.audio.AudioCaptureEngine
import com.classeve.earslate.audio.AudioRoute
import com.classeve.earslate.bootstrap.LocalKeyBootstrapRepository
import com.classeve.earslate.bootstrap.ProviderSessionMinter
import com.classeve.earslate.live.OkHttpLiveSocketClient
import com.classeve.earslate.security.ProviderKeyStore
import com.classeve.earslate.testing.OneKey
import com.classeve.earslate.ui.captions.CaptionsStore
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A real translation session on the device: Android's own network stack and
 * audio output, the real provider, and a recording played in as the
 * microphone.
 *
 * Skipped unless the run supplies a key (`-e geminiKey …`) and a recording of
 * Spanish speech (16 kHz PCM16) has been pushed to [RECORDING]. Neither is ever
 * part of the build.
 */
@RunWith(AndroidJUnit4::class)
class LiveSessionOnDeviceTest {

    /** Plays a recording in as the microphone, at real time. */
    private class RecordedMicrophone : AudioCaptureEngine {
        @Volatile private var onFrame: ((ByteArray) -> Unit)? = null
        @Volatile var sampleRateHz = 0

        override fun start(
            sampleRateHz: Int,
            frameMs: Int,
            onFrame: (ByteArray) -> Unit,
            onError: () -> Unit,
            onTaken: (Boolean) -> Unit,
        ): Boolean {
            this.sampleRateHz = sampleRateHz
            this.onFrame = onFrame
            return true
        }

        override fun stop() {
            onFrame = null
        }

        val listening get() = onFrame != null

        fun play(pcm16k: ByteArray) {
            val began = System.nanoTime()
            var sent = 0
            var offset = 0
            while (offset < pcm16k.size) {
                onFrame?.invoke(pcm16k.copyOfRange(offset, minOf(pcm16k.size, offset + 3_200)).copyOf(3_200))
                offset += 3_200
                sent++
                val wait = (began + sent * 100_000_000L - System.nanoTime()) / 1_000_000
                if (wait > 0) Thread.sleep(wait)
            }
        }
    }

    @Test
    fun aRealSessionTranslatesOnThisDevice() {
        val key = InstrumentationRegistry.getArguments().getString("geminiKey").orEmpty()
        assumeTrue("no key supplied to the run", key.isNotEmpty())
        // Read through the shell: an app cannot open what adb pushed.
        val spanish = ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cat $RECORDING"),
        ).use { it.readBytes() }
        assumeTrue("no recording at $RECORDING", spanish.isNotEmpty())

        val http = OkHttpLiveSocketClient.newHttpClient()
        val microphone = RecordedMicrophone()
        val playback = AndroidAudioPlaybackEngine()
        val captions = CaptionsStore()
        val state = RuntimeStateStore()
        val coordinator = SessionCoordinator(
            credentials = LocalKeyBootstrapRepository(
                keys = ProviderKeyStore(OneKey(key)),
                minter = ProviderSessionMinter(http, installId = "device-test"),
            ),
            socketFactory = { OkHttpLiveSocketClient(http) },
            captureEngine = microphone,
            playbackEngine = playback,
            captionsStore = captions,
            stateStore = state,
            audioFocus = object : AudioFocus {
                override fun acquire() = Unit
                override fun release() = Unit
            },
            route = MutableStateFlow(AudioRoute.BLUETOOTH),
        )

        fun await(what: String, timeoutMs: Long, condition: () -> Boolean) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (!condition()) {
                check(System.currentTimeMillis() < deadline) {
                    "timed out waiting for $what (state ${state.state.value}, error ${state.lastError.value?.message})"
                }
                Thread.sleep(20)
            }
        }

        coordinator.start(TranslatorPolicy(TargetLanguage("English", "en-US")))
        try {
            await("the session to listen", 30_000) { state.state.value == RuntimeState.LISTENING && microphone.listening }
            assertEquals(16_000, microphone.sampleRateHz)

            var heardSomething = false
            val watcher = Thread {
                while (microphone.listening) {
                    if (playback.snapshot().audible) heardSomething = true
                    Thread.sleep(20)
                }
            }.also { it.start() }

            microphone.play(ByteArray(32_000))
            microphone.play(spanish)
            // The room stays silent until the model, a few seconds behind, has said all of it.
            // The first caption may be no more than "Hi, good morning."
            fun english() = captions.settled().joinToString(" ").lowercase()
            val deadline = System.currentTimeMillis() + 25_000
            while (!english().let { it.contains("train") || it.contains("station") }) {
                check(System.currentTimeMillis() < deadline) {
                    "the Spanish did not come out in English: \"${english()}\" " +
                        "(state ${state.state.value}, error ${state.lastError.value?.message})"
                }
                microphone.play(ByteArray(3_200))
            }
            assertTrue("translated speech came out of the device's audio output", heardSomething)
            assertEquals("es-ES", state.heardLanguage.value?.bcp47)
            assertNull(state.lastError.value)
            assertEquals("the lane never dropped speech", 0, playback.snapshot().droppedMs)
            coordinator.stop()
            watcher.join(2_000)
        } finally {
            coordinator.stop()
            await("the session to end", 10_000) { state.state.value == RuntimeState.IDLE }
        }
    }

    private companion object {
        const val RECORDING = "/data/local/tmp/es1.16k.pcm"
    }
}
