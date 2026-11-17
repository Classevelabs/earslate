package com.classeve.earslate.testing

import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry

/**
 * A phone call an emulator makes to nobody. On a real phone it would ring a
 * real number, so a call is placed only on an emulator, and only in a run that
 * asks for one (`-e phoneCall true`).
 */
object PhoneCall {

    val wanted: Boolean
        get() = InstrumentationRegistry.getArguments().getString("phoneCall") == "true" && Build.HARDWARE == "ranchu"

    fun place() {
        shell("am start -a android.intent.action.CALL -d tel:$NOBODY")
    }

    /**
     * Ends every call there is, and waits until it is gone: a call left up by
     * one test is a second call in the next, and ending one of two gives
     * nothing back.
     *
     * The key is pressed only for a call that is up. Pressed with no call to
     * end, or a second time for one already going, it puts the phone to sleep.
     */
    fun end() {
        var pressedAt = 0L
        repeat(60) {
            val calls = shell("dumpsys telecom")
            // No answer at all is not an answer that there is no call.
            if ("mCalls:" in calls && "[Call id=" !in calls) return
            if (UP.containsMatchIn(calls) && SystemClock.uptimeMillis() - pressedAt > PRESS_AGAIN_MS) {
                shell("input keyevent KEYCODE_ENDCALL")
                pressedAt = SystemClock.uptimeMillis()
            }
            Thread.sleep(250)
        }
    }

    // Read to its end: the command has run once its output is closed.
    private fun shell(command: String): String {
        val output = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(output).use { it.readBytes().decodeToString() }
    }

    /** From the range kept for numbers that belong to no one. */
    private const val NOBODY = "5550100"

    /** A call still listed and not already on its way out. */
    private val UP = Regex("""\[Call id=[^,]*, state=(?!DISCONNECT|ABORTED)""")
    private const val PRESS_AGAIN_MS = 5_000L
}
