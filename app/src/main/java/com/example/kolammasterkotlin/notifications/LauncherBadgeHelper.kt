package com.kolammaster.app.notifications

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import me.leolin.shortcutbadger.ShortcutBadger
import me.leolin.shortcutbadger.ShortcutBadgeException

internal object LauncherBadgeHelper {
    const val TAG = "kolam_master_badge_notification"
    const val ID = 73104

    fun postActualNotification(
        context: Context,
        builder: NotificationCompat.Builder,
        unreadCount: Int
    ) {
        applyLauncherBadge(context, unreadCount)
        val notification = builder
            .setNumber(unreadCount.coerceAtLeast(0))
            .build()
        NotificationManagerCompat.from(context).notify(TAG, ID, notification)
    }

    fun updateExistingNotification(context: Context, unreadCount: Int) {
        Log.i(DIAGNOSTIC_TAG, "Badge update started; combined count=$unreadCount")
        applyLauncherBadge(context, unreadCount)
        val manager = context.getSystemService(NotificationManager::class.java)
        if (unreadCount <= 0) {
            manager.cancel(TAG, ID)
            return
        }
        val active = manager.activeNotifications.firstOrNull {
            it.tag == TAG && it.id == ID
        } ?: return
        val notification = Notification.Builder
            .recoverBuilder(context, active.notification)
            .setNumber(unreadCount)
            .build()
        manager.notify(TAG, ID, notification)
    }

    fun clear(context: Context) {
        applyLauncherBadge(context, 0)
        NotificationManagerCompat.from(context).cancel(TAG, ID)
    }

    private fun applyLauncherBadge(context: Context, unreadCount: Int) {
        Log.i(
            DIAGNOSTIC_TAG,
            "ShortcutBadger request; count=${unreadCount.coerceAtLeast(0)}, action=${
                if (unreadCount > 0) "apply" else "remove"
            }"
        )
        try {
            if (unreadCount > 0) {
                ShortcutBadger.applyCountOrThrow(context, unreadCount)
            } else {
                ShortcutBadger.removeCountOrThrow(context)
            }
            Log.i(DIAGNOSTIC_TAG, "ShortcutBadger request succeeded")
        } catch (exception: ShortcutBadgeException) {
            Log.w(
                DIAGNOSTIC_TAG,
                "ShortcutBadger threw ${exception::class.java.simpleName}: ${exception.message}"
            )
            Log.w(TAG, "Could not apply the launcher badge count.", exception)
        }
    }

    private const val DIAGNOSTIC_TAG = "ANNOUNCEMENT_BADGE_DEBUG"
}
