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
    const val ANNOUNCEMENT_TAG = "kolam_master_announcement_notification"
    const val ANNOUNCEMENT_ID = 73105
    const val SUPPORT_TAG = "kolam_master_support_notification"
    const val SUPPORT_ID = 73106

    private val managedNotificationIdentities = listOf(
        ANNOUNCEMENT_TAG to ANNOUNCEMENT_ID,
        SUPPORT_TAG to SUPPORT_ID
    )

    fun postActualNotification(
        context: Context,
        builder: NotificationCompat.Builder,
        unreadCount: Int,
        tag: String,
        id: Int
    ) {
        cancelLegacyNotification(context)
        applyLauncherBadge(context, unreadCount)
        val notification = builder
            .setNumber(unreadCount.coerceAtLeast(0))
            .build()
        NotificationManagerCompat.from(context).notify(tag, id, notification)
    }

    fun updateExistingNotification(context: Context, unreadCount: Int) {
        Log.i(DIAGNOSTIC_TAG, "Badge update started; combined count=$unreadCount")
        applyLauncherBadge(context, unreadCount)
        val manager = context.getSystemService(NotificationManager::class.java)
        cancelLegacyNotification(context)
        if (unreadCount <= 0) {
            managedNotificationIdentities.forEach { (tag, id) -> manager.cancel(tag, id) }
            return
        }
        val activeNotifications = manager.activeNotifications
        managedNotificationIdentities.forEach { (tag, id) ->
            val active = activeNotifications.firstOrNull {
                it.tag == tag && it.id == id
            } ?: return@forEach
            if (active.notification.number == unreadCount) return@forEach
            val notification = Notification.Builder
                .recoverBuilder(context, active.notification)
                .setNumber(unreadCount)
                .setOnlyAlertOnce(true)
                .build()
            manager.notify(tag, id, notification)
        }
    }

    fun clear(context: Context) {
        applyLauncherBadge(context, 0)
        cancelLegacyNotification(context)
        managedNotificationIdentities.forEach { (tag, id) ->
            NotificationManagerCompat.from(context).cancel(tag, id)
        }
    }

    private fun cancelLegacyNotification(context: Context) {
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
