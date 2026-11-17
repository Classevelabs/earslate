package com.classeve.earslate.testing

import android.app.UiAutomation
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo

/**
 * The screen as it is now. UiAutomation keeps a copy of it that is not always
 * brought up to date: a mark that was on the screen for a second and a half
 * was missing from the copy from start to finish, and the test looking for it
 * failed more often than it passed.
 */
fun UiAutomation.screenNow(): AccessibilityNodeInfo? {
    if (Build.VERSION.SDK_INT >= 34) clearCache()
    return rootInActiveWindow
}
