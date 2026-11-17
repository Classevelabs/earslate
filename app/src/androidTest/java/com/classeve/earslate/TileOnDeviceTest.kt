package com.classeve.earslate

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.classeve.earslate.security.ProviderKeyStore
import com.classeve.earslate.service.TranslatorService
import com.classeve.earslate.session.RuntimeState
import com.classeve.earslate.settings.OnboardingPrefs
import com.classeve.earslate.testing.OneKey
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Quick Settings tile as a person uses it: from the home screen, it starts a
 * live session that hears, and stops it again.
 *
 * Under instrumentation the app may start activities from the background, and so
 * a service it starts there gets the microphone anyway: the rule this tile works
 * around is shown by `cmd statusbar click-tile` from a shell (BEHAVIOUR-DECISIONS).
 *
 * Skipped unless the run supplies a key (`-e geminiKey …`). The key is held in
 * memory for the length of the run and is never stored.
 */
@RunWith(AndroidJUnit4::class)
class TileOnDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val tile = "${context.packageName}/.service.TranslatorTileService"

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .use { it.readBytes().toString(Charsets.UTF_8) }

    private val seen = HashSet<RuntimeState>()

    private fun await(what: String, timeoutMs: Long, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            seen += EarslateRuntime.stateStore.state.value
            check(System.currentTimeMillis() < deadline) {
                "timed out waiting for $what (state ${EarslateRuntime.stateStore.state.value}, " +
                    "error ${EarslateRuntime.stateStore.lastError.value?.message})"
            }
            Thread.sleep(50)
        }
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
    fun aSessionStartedFromTheTileHasTheMicrophone() {
        val key = InstrumentationRegistry.getArguments().getString("geminiKey").orEmpty()
        assumeTrue("no key supplied to the run", key.isNotEmpty())
        val automation = instrumentation.uiAutomation
        automation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) {
            automation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        replace("keyStore", ProviderKeyStore(OneKey(key)))
        replace("credentialSource", null)
        replace("sessionCoord", null)
        OnboardingPrefs.markCompleted(context)
        OnboardingPrefs.markLanguageChosen(context)
        OnboardingPrefs.markAudioDisclosureAccepted(context)

        shell("cmd statusbar add-tile $tile")
        shell("input keyevent KEYCODE_HOME")
        Thread.sleep(1_500)
        try {
            shell("cmd statusbar click-tile $tile")
            await("the session to come up", 40_000) {
                EarslateRuntime.stateStore.state.value.let { it == RuntimeState.LISTENING || it == RuntimeState.MICROPHONE_TAKEN }
            }
            assertTrue("the service is running", serviceRunning())
            // A silenced microphone is reported a moment after recording starts.
            val until = System.currentTimeMillis() + 6_000
            while (System.currentTimeMillis() < until) {
                seen += EarslateRuntime.stateStore.state.value
                Thread.sleep(50)
            }
            assertFalse("the session heard silence: it went through $seen", RuntimeState.MICROPHONE_TAKEN in seen)
            assertNull(EarslateRuntime.stateStore.lastError.value)

            // The tile stops what it started.
            shell("cmd statusbar click-tile $tile")
            await("the session to end", 15_000) { EarslateRuntime.stateStore.state.value == RuntimeState.IDLE }
            await("the service to stop", 10_000) { !serviceRunning() }
        } finally {
            TranslatorService.stop(context)
            shell("cmd statusbar remove-tile $tile")
        }
    }
}
