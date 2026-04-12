package com.classeve.earslate.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.classeve.earslate.R
import com.classeve.earslate.session.RuntimeState
import com.classeve.earslate.session.isActive
import com.classeve.earslate.ui.MainActivity

/**
 * Builds the persistent foreground notification shown while the translator is
 * active. Blueprint §16 — notification shows runtime state and exposes Stop as
 * a quick action so the user never has to hunt for the app to pause it.
 */
object NotificationFactory {

    const val CHANNEL_ID = "earslate_translator"
    const val NOTIFICATION_ID = 7_401

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notification_channel_description)
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun buildTranslatorNotification(
        context: Context,
        state: RuntimeState,
        languageName: String = "English",
    ): android.app.Notification {
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val stopIntent = PendingIntent.getService(
            context,
            1,
            Intent(context, TranslatorService::class.java).setAction(TranslatorService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val startIntent = PendingIntent.getService(
            context,
            2,
            Intent(context, TranslatorService::class.java).setAction(TranslatorService.ACTION_START),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val statusRes = when (state) {
            RuntimeState.IDLE -> R.string.status_idle
            RuntimeState.BOOTSTRAPPING -> R.string.status_bootstrapping
            RuntimeState.CONNECTING -> R.string.status_connecting
            RuntimeState.READY -> R.string.status_ready
            RuntimeState.LISTENING -> R.string.status_listening
            RuntimeState.PLAYING -> R.string.status_playing
            RuntimeState.RECONNECTING -> R.string.status_reconnecting
            RuntimeState.RESUMING -> R.string.status_resuming
            RuntimeState.DEGRADED -> R.string.status_degraded
            RuntimeState.STOPPING -> R.string.status_stopping
        }

        val statusText = context.getString(statusRes)
        val contentText = "$statusText \u00b7 $languageName"
        val bigText = if (state.isActive) {
            "Translating to $languageName. Tap to open earslate."
        } else {
            "$statusText \u00b7 $languageName"
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        if (state.isActive) {
            builder.addAction(
                NotificationCompat.Action.Builder(
                    R.drawable.ic_stop,
                    context.getString(R.string.notification_action_stop),
                    stopIntent,
                ).build(),
            )
        } else {
            builder.addAction(
                NotificationCompat.Action.Builder(
                    R.drawable.ic_notification,
                    context.getString(R.string.action_start),
                    startIntent,
                ).build(),
            )
        }

        return builder.build()
    }

}
