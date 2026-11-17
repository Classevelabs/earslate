package com.classeve.earslate.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.classeve.earslate.service.TranslatorService

/**
 * Opened by the Quick Settings tile for no longer than it takes to start a
 * session, and never seen. Android gives no microphone to a service started
 * from the background (Android 11+), so a session the tile started itself ran
 * on silence; started while an activity of the app is in front, it hears.
 */
class StartActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            val started = TranslatorService.readyToStart(this) &&
                runCatching { TranslatorService.start(this) }.isSuccess
            // The app asks for what is missing: the microphone, or the consent.
            if (!started) {
                runCatching {
                    startActivity(
                        Intent(this, MainActivity::class.java)
                            .setAction(MainActivity.ACTION_REQUEST_START)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
        }
        finish()
    }
}
