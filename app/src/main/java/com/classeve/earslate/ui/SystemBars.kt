package com.classeve.earslate.ui

import android.os.Build
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat

/**
 * Draw behind the status and navigation bars.
 *
 * This is what androidx.activity's enableEdgeToEdge() does for a minSdk 29
 * app, without the library: the bar colours are transparent in Theme.Earslate,
 * the icons are light (windowLightStatusBar false: the UI is always dark),
 * and ALWAYS is the cutout mode Android 15 enforces anyway.
 *
 * The library helper is not called because its API 28 path writes the
 * deprecated LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES and its API 29 path
 * calls setStatusBarColor/setNavigationBarColor, both deprecated in Android 15.
 * Play reads them out of the release dex whatever the runtime guard says and
 * lists them on every release ("Your app uses deprecated APIs or parameters
 * for edge-to-edge"). Upgrading activity does not clear it; not shipping the
 * classes does. verifyReleaseHygiene fails the release if
 * androidx.activity.EdgeToEdge* is reachable again.
 */
fun ComponentActivity.drawBehindSystemBars() {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        window.attributes.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    }
}
