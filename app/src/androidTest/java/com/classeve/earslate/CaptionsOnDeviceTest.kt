package com.classeve.earslate

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Rect
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
import com.classeve.earslate.testing.screenNow
import com.classeve.earslate.ui.MainActivity
import com.classeve.earslate.ui.captions.CaptionSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The captions on a real screen. Which row the list aims at is arithmetic and
 * is tested off the device; whether the newest caption then ends up where a
 * person can see it, on the side of whoever said it, and under a finger that
 * can copy it, is not.
 *
 * Only what is on the screen is looked at: a caption the list holds below the
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
        walk(instrumentation.uiAutomation.screenNow())
        return found
    }

    private fun showing(text: String): AccessibilityNodeInfo? {
        fun search(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.text?.toString() == text) return node
            for (i in 0 until node.childCount) search(node.getChild(i))?.let { return it }
            return null
        }
        return search(instrumentation.uiAutomation.screenNow())
    }

    private fun whereIs(text: String): Rect? = showing(text)?.where()

    private fun AccessibilityNodeInfo.where() = Rect().also(::getBoundsInScreen)

    /**
     * What is written in [part] of the screen now, that part alone being asked.
     * Reading the whole screen is fifty questions, and on a slow device they
     * took longer to answer than a caption says "copied" for.
     */
    private fun writtenIn(part: AccessibilityNodeInfo): List<String> {
        val found = ArrayList<String>()
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null || !node.refresh()) return
            node.text?.toString()?.let { found += it }
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(part)
        return found
    }

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for $what; on screen: ${onScreen().takeLast(12)}" }
            Thread.sleep(50)
        }
    }

    /**
     * Whether [part] came to say "COPIED" after the tap at [tappedAt]. The word
     * is up for a second and a half. A device too slow to be asked while it was
     * up has shown nothing either way, and the test is left out, not failed.
     */
    private fun saidCopied(part: AccessibilityNodeInfo, tappedAt: Long): Boolean {
        var askedInTime = false
        while (true) {
            val began = SystemClock.uptimeMillis() - tappedAt
            if ("COPIED" in writtenIn(part)) return true
            val ended = SystemClock.uptimeMillis() - tappedAt
            if (began >= 400 && ended <= 1_400) askedInTime = true
            if (ended > 3_000) break
            Thread.sleep(20)
        }
        assumeTrue("this device was too slow to be asked while the word was up", askedInTime)
        return false
    }

    /** What [look] sees once the screen has had time to stop moving. */
    private fun <T> afterAMoment(look: () -> T): T {
        Thread.sleep(1_500)
        return look()
    }

    private fun touch(downAt: Long, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        instrumentation.sendPointerSync(event)
        event.recycle()
    }

    /** A finger drawn down or up the screen, slowly enough to be a drag. */
    private fun drag(x: Float, fromY: Float, toY: Float) {
        val down = SystemClock.uptimeMillis()
        touch(down, MotionEvent.ACTION_DOWN, x, fromY)
        for (step in 1..16) {
            Thread.sleep(16)
            touch(down, MotionEvent.ACTION_MOVE, x, fromY + (toY - fromY) * step / 16)
        }
        Thread.sleep(120)
        touch(down, MotionEvent.ACTION_UP, x, toY)
        // Time, and not the screen coming to rest: while a session runs
        // something on it is always moving.
        Thread.sleep(700)
    }

    private fun tap(where: Rect) {
        val down = SystemClock.uptimeMillis()
        touch(down, MotionEvent.ACTION_DOWN, where.exactCenterX(), where.exactCenterY())
        Thread.sleep(40)
        touch(down, MotionEvent.ACTION_UP, where.exactCenterX(), where.exactCenterY())
    }

    private fun copied(): String? {
        var text: String? = null
        instrumentation.runOnMainSync {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            text = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
        }
        return text
    }

    /** Something else on the clipboard, so that what an earlier run copied is not taken for this one's. */
    private fun clearWhatWasCopied() {
        instrumentation.runOnMainSync {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("", "nothing copied yet"))
        }
    }

    private fun said(n: Int) = "Line $n of the conversation."

    // Faster than anything can scroll: each caption is overtaken before the
    // list has reached it. Odd ones are theirs, even ones mine.
    private fun say(lines: IntRange) {
        for (n in lines) {
            val source = 1 + (n + 1) % 2
            val side = if (source == 1) CaptionSide.THEIRS else CaptionSide.MINE
            captions.appendDelta(source, side, said(n).take(8))
            Thread.sleep(60)
            captions.appendDelta(source, side, said(n).drop(8))
            Thread.sleep(60)
            captions.commitLine(source)
            Thread.sleep(80)
        }
    }

    /** The screen of a running session, with the captions fed by hand. */
    private fun onTheMainScreen(body: () -> Unit) {
        // Any key at all: the main screen is only shown once there is one.
        EarslateRuntime::class.java.getDeclaredField("keyStore").apply { isAccessible = true }
            .set(null, ProviderKeyStore(OneKey("not-a-key")))
        OnboardingPrefs.markCompleted(context)
        captions.clear()
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        try {
            EarslateRuntime.stateStore.set(RuntimeState.LISTENING)
            body()
        } finally {
            EarslateRuntime.stateStore.set(RuntimeState.IDLE)
            captions.clear()
            activity.finish()
        }
    }

    @Test
    fun theNewestLineIsKeptOnTheScreenUntilAHandMovesItAway() = onTheMainScreen {
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
        repeat(6) { drag(middle, fromY = newest.top - 100f, toY = newest.top - 700f) }
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
    }

    @Test
    fun whatTheySaidIsOnTheLeftAndWhatISaidOnTheRight() = onTheMainScreen {
        say(1..4)
        await("the conversation to be on the screen") { whereIs(said(4)) != null }
        val theirs = whereIs(said(3))!!
        val mine = whereIs(said(4))!!

        assertTrue("theirs $theirs begins left of mine $mine", theirs.left < mine.left)
        assertTrue("and ends left of it", theirs.right < mine.right)
        assertTrue("mine is below it: the conversation reads downwards", mine.top >= theirs.bottom)
    }

    // Nothing I say is translated until their language is known. Before they
    // have spoken, this is the only way to be understood first.
    @Test
    fun theirLanguageCanBeSetByHandBeforeTheyHaveBeenHeard() = onTheMainScreen {
        await("the screen to say they have not been heard") { whereIs("Not heard yet") != null }
        tap(afterAMoment { whereIs("SET") }!!)
        await("the list of languages to choose theirs from") { whereIs("They are speaking") != null }
    }

    @Test
    fun aCaptionIsCopiedByTappingItAndTheWholeConversationByItsButton() = onTheMainScreen {
        clearWhatWasCopied()
        say(1..4)
        await("the conversation to be on the screen") { whereIs(said(4)) != null }

        // Where it is once the page has stopped moving to show it.
        val caption = afterAMoment { showing(said(4)) }!!
        val bubble = caption.parent
        tap(caption.where())
        val captionSaidSo = saidCopied(bubble, tappedAt = SystemClock.uptimeMillis())
        assertTrue("the caption says it was copied (on the clipboard: ${copied()})", captionSaidSo)
        assertEquals(said(4), copied())
        await("the mark to be taken away again") { "COPIED" !in writtenIn(bubble) }
        await("the caption to be as it was") { whereIs(said(4)) != null }

        val copyAll = afterAMoment { showing("COPY ALL") }!!
        tap(copyAll.where())
        val buttonSaidSo = saidCopied(copyAll, tappedAt = SystemClock.uptimeMillis())
        assertTrue("the button says it was copied (on the clipboard: ${copied()})", buttonSaidSo)
        assertEquals(
            "Them: ${said(1)}\nMe: ${said(2)}\nThem: ${said(3)}\nMe: ${said(4)}",
            copied(),
        )
    }
}
