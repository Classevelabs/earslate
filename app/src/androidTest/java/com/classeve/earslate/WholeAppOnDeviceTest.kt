package com.classeve.earslate

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.classeve.earslate.audio.AndroidAudioCaptureEngine
import com.classeve.earslate.audio.AudioCaptureEngine
import com.classeve.earslate.security.ProviderKeyStore
import com.classeve.earslate.service.TranslatorService
import com.classeve.earslate.session.RuntimeState
import com.classeve.earslate.settings.OnboardingPrefs
import com.classeve.earslate.testing.OneKey
import com.classeve.earslate.ui.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/**
 * The app as a person uses it: its own screen, service, microphone and
 * loudspeaker, and the real provider. Everything else in the suite puts a
 * stand-in somewhere; this is the one place the whole thing runs together.
 *
 * Skipped unless the run supplies a key (`-e geminiKey …`). The key is held in
 * memory for the length of the run and is never stored.
 *
 * An emulator has nobody to speak to it. When a recording of Spanish speech
 * (16 kHz PCM16) has been pushed to [RECORDING], it is added to what the
 * device's own microphone hears, and the run checks that it comes out in
 * English on the screen. Without one, the run checks that the app listens,
 * stays up and stops.
 */
@RunWith(AndroidJUnit4::class)
class WholeAppOnDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation = instrumentation.uiAutomation
    private val context: Context = instrumentation.targetContext

    /** The device's microphone, with a recording added to what it hears. */
    private class MicrophoneWithARecording(private val recording: ByteArray) : AudioCaptureEngine {
        private val microphone = AndroidAudioCaptureEngine()
        val frames = AtomicInteger()
        @Volatile var sampleRateHz = 0

        override fun start(sampleRateHz: Int, frameMs: Int, onFrame: (ByteArray) -> Unit, onError: () -> Unit): Boolean {
            this.sampleRateHz = sampleRateHz
            // Three seconds of the room alone, then the recording, then the room again.
            var at = -sampleRateHz * 2 * 3
            return microphone.start(sampleRateHz, frameMs, onFrame = { frame ->
                frames.incrementAndGet()
                val heard = frame.copyOf()
                for (i in 0 until heard.size / 2) {
                    val from = at + i * 2
                    if (from < 0 || from + 1 >= recording.size) continue
                    val mixed = (sample(heard, i * 2) + sample(recording, from)).coerceIn(-32768, 32767)
                    heard[i * 2] = (mixed and 0xff).toByte()
                    heard[i * 2 + 1] = ((mixed shr 8) and 0xff).toByte()
                }
                at += frame.size
                onFrame(heard)
            }, onError = onError)
        }

        override fun stop() = microphone.stop()

        private fun sample(pcm: ByteArray, at: Int): Int =
            (((pcm[at + 1].toInt() and 0xff) shl 8) or (pcm[at].toInt() and 0xff)).toShort().toInt()
    }

    private val seen = HashSet<RuntimeState>()

    private fun await(what: String, timeoutMs: Long, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            seen += EarslateRuntime.stateStore.state.value
            check(System.currentTimeMillis() < deadline) {
                val state = EarslateRuntime.stateStore
                "timed out waiting for $what (state ${state.state.value}, error ${state.lastError.value?.message}, " +
                    "notice ${state.notice.value}, captions ${EarslateRuntime.captionsStore.lines.value})"
            }
            Thread.sleep(50)
        }
    }

    private fun onScreen(matches: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun search(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (matches(node)) return node
            for (i in 0 until node.childCount) search(node.getChild(i))?.let { return it }
            return null
        }
        return search(automation.rootInActiveWindow)
    }

    private fun described(description: String) = onScreen { it.contentDescription?.toString() == description }

    private fun showing(text: String) = onScreen { it.text?.toString()?.contains(text, ignoreCase = true) == true }

    private fun labelled(text: String) = onScreen { it.text?.toString().equals(text, ignoreCase = true) }

    private fun tap(what: String, find: () -> AccessibilityNodeInfo?) {
        await("\"$what\" to be on screen", 10_000) { find() != null }
        var node = find()
        while (node != null && !node.isClickable) node = node.parent
        checkNotNull(node) { "\"$what\" cannot be tapped" }
        assertTrue("tapping \"$what\"", node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun serviceRunning(): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION") // Still answers for the app's own services, which is all that is asked.
        return manager.getRunningServices(50).any { it.service.className == TranslatorService::class.java.name }
    }

    // The app has no seam for a test to reach in by, and should not grow one.
    private fun replace(field: String, with: Any?) {
        EarslateRuntime::class.java.getDeclaredField(field).apply { isAccessible = true }.set(null, with)
    }

    @Test
    fun theAppListensTranslatesAndStops() {
        val key = InstrumentationRegistry.getArguments().getString("geminiKey").orEmpty()
        assumeTrue("no key supplied to the run", key.isNotEmpty())
        // Read through the shell: an app cannot open what adb pushed.
        val spanish = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("cat $RECORDING"))
            .use { it.readBytes() }

        automation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) {
            automation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        replace("keyStore", ProviderKeyStore(OneKey(key)))
        val microphone = MicrophoneWithARecording(spanish)
        replace("captureEngine", microphone)
        // Built from the two above the first time they are asked for.
        replace("credentialSource", null)
        replace("sessionCoord", null)
        OnboardingPrefs.markCompleted(context)
        OnboardingPrefs.markLanguageChosen(context)

        val state = EarslateRuntime.stateStore
        val captions = EarslateRuntime.captionsStore
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        try {
            tap("start") { described("Start listening and translating") }
            // The first start on an install asks before the microphone is opened.
            if (!OnboardingPrefs.isAudioDisclosureAccepted(context)) {
                tap("I agree") { labelled(context.getString(R.string.audio_disclosure_agree)) }
            }
            await("the session to listen", 40_000) { state.state.value == RuntimeState.LISTENING }
            assertTrue("the service is running", serviceRunning())
            await("the screen to offer stop", 5_000) { described("Stop translating") != null }
            await("the microphone to deliver", 5_000) { microphone.frames.get() >= 10 }
            assertEquals("Gemini is sent 16 kHz", 16_000, microphone.sampleRateHz)

            if (spanish.isNotEmpty()) {
                await("the Spanish to come out as a caption", 60_000) { captions.lines.value.isNotEmpty() }
                val english = captions.lines.value.joinToString(" ").lowercase()
                assertTrue("the Spanish was translated: $english", english.contains("train") || english.contains("station"))
                assertEquals("es-ES", state.heardLanguage.value?.bcp47)
                val shown = captions.lines.value.first().split(' ').take(3).joinToString(" ")
                await("the caption \"$shown\" to be on the screen", 5_000) { showing(shown) != null }
                // On a loudspeaker the translation waits for the pause, and is then said.
                await("the translation to be spoken and the app to listen again", 40_000) {
                    RuntimeState.PLAYING in seen && state.state.value == RuntimeState.LISTENING
                }
            } else {
                // The microphone and both connections stay up with nobody speaking.
                Thread.sleep(10_000)
                assertTrue("still running: ${state.state.value}", state.state.value != RuntimeState.IDLE)
            }
            assertNull(state.lastError.value)
            assertNull(state.notice.value)

            tap("stop") { described("Stop translating") }
            await("the session to end", 15_000) { state.state.value == RuntimeState.IDLE }
            await("the service to stop", 10_000) { !serviceRunning() }
            await("the screen to offer start again", 5_000) { described("Start listening and translating") != null }
            val framesAtStop = microphone.frames.get()
            Thread.sleep(500)
            assertEquals("the microphone is closed", framesAtStop, microphone.frames.get())
            assertNull(state.lastError.value)
        } finally {
            TranslatorService.stop(context)
            activity.finish()
        }
    }

    private companion object {
        const val RECORDING = "/data/local/tmp/es1.16k.pcm"
    }
}
