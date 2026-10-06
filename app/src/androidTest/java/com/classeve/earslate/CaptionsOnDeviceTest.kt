package com.classeve.earslate

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.classeve.earslate.security.ProviderKeyStore
import com.classeve.earslate.session.RuntimeState
import com.classeve.earslate.settings.OnboardingPrefs
import com.classeve.earslate.testing.OneKey
import com.classeve.earslate.ui.MainActivity
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The captions on a real screen. Which row the list aims at is arithmetic and
 * is tested off the device; whether the newest line then ends up where a
 * person can see it is not.
 *
 * Only what is on the screen is looked at: a line the list holds below the
 * screen's edge does not count.
 */
@RunWith(AndroidJUnit4::class)
class CaptionsOnDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val captions = EarslateRuntime.captionsStore

    private fun onScreen(): List<String> {
        val found = ArrayList<String>()
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null) return
            node.text?.toString()?.let { found += it }
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(instrumentation.uiAutomation.rootInActiveWindow)
        return found
    }

    private fun whereIs(text: String): Rect? {
        fun search(node: AccessibilityNodeInfo?): Rect? {
            if (node == null) return null
            if (node.text?.toString() == text) return Rect().also { node.getBoundsInScreen(it) }
            for (i in 0 until node.childCount) search(node.getChild(i))?.let { return it }
            return null
        }
        return search(instrumentation.uiAutomation.rootInActiveWindow)
    }

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for $what; on screen: ${onScreen().takeLast(12)}" }
            Thread.sleep(50)
        }
    }

    /** What [look] sees once the screen has had time to change, read afresh and not from memory. */
    private fun <T> afterAMoment(look: () -> T): T {
        Thread.sleep(1_500)
        if (Build.VERSION.SDK_INT >= 34) instrumentation.uiAutomation.clearCache()
        return look()
    }

    /** A finger drawn down or up the screen, slowly enough to be a drag. */
    private fun drag(x: Float, fromY: Float, toY: Float) {
        val down = SystemClock.uptimeMillis()
        fun touch(action: Int, y: Float) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            instrumentation.sendPointerSync(event)
            event.recycle()
        }
        touch(MotionEvent.ACTION_DOWN, fromY)
        for (step in 1..16) {
            Thread.sleep(16)
            touch(MotionEvent.ACTION_MOVE, fromY + (toY - fromY) * step / 16)
        }
        Thread.sleep(120)
        touch(MotionEvent.ACTION_UP, toY)
        instrumentation.waitForIdleSync()
        Thread.sleep(600)
    }

    private fun said(n: Int) = "Line $n of the conversation."

    // Faster than anything can scroll: each line is overtaken before the list has reached it.
    private fun say(lines: IntRange) {
        for (n in lines) {
            captions.appendDelta(1, said(n).take(8))
            Thread.sleep(60)
            captions.appendDelta(1, said(n).drop(8))
            Thread.sleep(60)
            captions.commitLine(1)
            Thread.sleep(80)
        }
    }

    @Test
    fun theNewestLineIsKeptOnTheScreenUntilAHandMovesItAway() {
        // Any key at all: the main screen is only shown once there is one.
        EarslateRuntime::class.java.getDeclaredField("keyStore").apply { isAccessible = true }
            .set(null, ProviderKeyStore(OneKey("not-a-key")))
        OnboardingPrefs.markCompleted(context)
        captions.clear()
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        try {
            // The screen of a running session, with the captions fed by hand.
            EarslateRuntime.stateStore.set(RuntimeState.LISTENING)
            val screen = context.resources.displayMetrics
            val middle = screen.widthPixels / 2f

            say(1..40)
            await("the newest line to be on the screen") { whereIs(said(40)) != null }
            assertNull("the list has left its first line behind", whereIs(said(1)))

            // Somebody pulls the list down to read what was said earlier.
            val newest = whereIs(said(40))!!
            drag(middle, fromY = newest.top - 500f, toY = newest.top - 100f)
            await("the reader to have moved away from the end") { whereIs(said(40)) == null }
            say(41..44)
            assertNull("and is not dragged back to it by what is said next", afterAMoment { whereIs(said(44)) })

            // They go back to the end, and the list follows again.
            repeat(4) { drag(middle, fromY = newest.top - 100f, toY = newest.top - 700f) }
            await("the reader to be back at the end") { whereIs(said(44)) != null }
            say(45..48)
            await("the list to follow again") { whereIs(said(48)) != null }

            // Somebody pulls the whole page down, to reach what is at the top of it.
            repeat(2) { drag(middle, fromY = screen.heightPixels * 0.15f, toY = screen.heightPixels * 0.70f) }
            await("the foot of the page to have left the screen") { whereIs(said(48)) == null }
            say(49..52)
            assertNull("and the page is not pulled back by what is said next", afterAMoment { whereIs(said(52)) })

            // They let the page back down, and it keeps its foot in view again.
            repeat(4) { drag(middle, fromY = screen.heightPixels * 0.50f, toY = screen.heightPixels * 0.15f) }
            say(53..56)
            await("the page to follow again") { whereIs(said(56)) != null }
        } finally {
            EarslateRuntime.stateStore.set(RuntimeState.IDLE)
            captions.clear()
            activity.finish()
        }
    }
}
