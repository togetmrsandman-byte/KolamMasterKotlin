package com.kolammaster.app.notifications

import com.kolammaster.app.R
import com.kolammaster.app.withSelectedAppLanguage
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build

internal object SupportNotificationChannel {
    const val CHANNEL_ID = "default"

    fun create(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val localizedContext = context.withSelectedAppLanguage()
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            localizedContext.getString(R.string.notifications_channel),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = localizedContext.getString(R.string.notifications_channel_description)
            setShowBadge(true)
            enableVibration(true)
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
        }
        manager.createNotificationChannel(channel)
    }
}
