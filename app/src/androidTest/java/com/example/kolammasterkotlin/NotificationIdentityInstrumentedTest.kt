package com.kolammaster.app.notifications

import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kolammaster.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationIdentityInstrumentedTest {
    @Test
    fun announcementAndSupportNotificationsCoexistAndShareCombinedUnreadCount() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = NotificationManagerCompat.from(context)
        val notificationManager =
            context.getSystemService(android.app.NotificationManager::class.java)
        assumeTrue(manager.areNotificationsEnabled())
        SupportNotificationChannel.create(context)
        try {
            manager.notify(
                LauncherBadgeHelper.TAG,
                LauncherBadgeHelper.ID,
                NotificationCompat.Builder(context, SupportNotificationChannel.CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_stat_support)
                    .setContentTitle("Legacy")
                    .setContentText("Old shared identity")
                    .build()
            )
            LauncherBadgeHelper.postActualNotification(
                context,
                NotificationCompat.Builder(context, SupportNotificationChannel.CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_stat_support)
                    .setContentTitle("Announcement")
                    .setContentText("An announcement"),
                unreadCount = 2,
                tag = LauncherBadgeHelper.ANNOUNCEMENT_TAG,
                id = LauncherBadgeHelper.ANNOUNCEMENT_ID
            )
            LauncherBadgeHelper.postActualNotification(
                context,
                NotificationCompat.Builder(context, SupportNotificationChannel.CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_stat_support)
                    .setContentTitle("Support")
                    .setContentText("A support message"),
                unreadCount = 3,
                tag = LauncherBadgeHelper.SUPPORT_TAG,
                id = LauncherBadgeHelper.SUPPORT_ID
            )

            var active = notificationManager.activeNotifications
            assertTrue(active.any {
                it.tag == LauncherBadgeHelper.ANNOUNCEMENT_TAG &&
                    it.id == LauncherBadgeHelper.ANNOUNCEMENT_ID
            })
            assertTrue(active.any {
                it.tag == LauncherBadgeHelper.SUPPORT_TAG &&
                    it.id == LauncherBadgeHelper.SUPPORT_ID
            })
            assertTrue(active.none {
                it.tag == LauncherBadgeHelper.TAG && it.id == LauncherBadgeHelper.ID
            })

            LauncherBadgeHelper.updateExistingNotification(context, 5)

            active = notificationManager.activeNotifications
            assertEquals(
                5,
                active.single {
                    it.tag == LauncherBadgeHelper.ANNOUNCEMENT_TAG &&
                        it.id == LauncherBadgeHelper.ANNOUNCEMENT_ID
                }.notification.number
            )
            assertEquals(
                5,
                active.single {
                    it.tag == LauncherBadgeHelper.SUPPORT_TAG &&
                        it.id == LauncherBadgeHelper.SUPPORT_ID
                }.notification.number
            )

            LauncherBadgeHelper.updateExistingNotification(context, 0)
            assertTrue(
                notificationManager.activeNotifications.none {
                    (it.tag == LauncherBadgeHelper.ANNOUNCEMENT_TAG &&
                        it.id == LauncherBadgeHelper.ANNOUNCEMENT_ID) ||
                        (it.tag == LauncherBadgeHelper.SUPPORT_TAG &&
                            it.id == LauncherBadgeHelper.SUPPORT_ID) ||
                        (it.tag == LauncherBadgeHelper.TAG && it.id == LauncherBadgeHelper.ID)
                }
            )
        } finally {
            LauncherBadgeHelper.clear(context)
        }
    }
}
