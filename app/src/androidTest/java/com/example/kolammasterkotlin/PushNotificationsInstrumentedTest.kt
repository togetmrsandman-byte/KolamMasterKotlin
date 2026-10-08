package com.kolammaster.app.notifications

import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kolammaster.app.R
import com.kolammaster.app.ContactHttpResponse
import com.kolammaster.app.ContactHttpTransport
import com.kolammaster.app.auth.SupabaseAccount
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertThrows
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PushNotificationsInstrumentedTest {
    @Test
    fun registersTokenForCurrentSupabaseAccountUsingFcmProvider() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val auth = FakePushTokenAuth()
        val transport = CapturingPushHttp()
        context.getSharedPreferences("kolam_master_fcm_registration", 0)
            .edit().clear().commit()

        PushTokenRepository(context, auth, transport).registerCurrentToken("test-fcm-token")

        val request = transport.requests.single()
        val row = JSONObject(request.body!!)
        assertEquals("POST", request.method)
        assertTrue(request.url.contains("on_conflict=user_id,token"))
        assertEquals(auth.account.id, row.getString("user_id"))
        assertEquals("test-fcm-token", row.getString("token"))
        assertEquals("android", row.getString("platform"))
        assertEquals("fcm", row.getString("provider"))
        assertEquals("Bearer test-access-token", request.headers["Authorization"])
        context.getSharedPreferences("kolam_master_fcm_registration", 0)
            .edit().clear().commit()
    }

    @Test
    fun persistsMultipleUnreadConversationsAndClearsOnlyOpenedConversation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val first = "push-test-conversation-1"
        val second = "push-test-conversation-2"
        SupportUnreadStore.clearConversation(context, first)
        SupportUnreadStore.clearConversation(context, second)

        SupportUnreadStore.recordSupportMessage(context, first, "push-test-message-1")
        SupportUnreadStore.recordSupportMessage(context, second, "push-test-message-2")

        assertEquals(setOf(first, second), SupportUnreadStore.unreadConversationIds(context))
        assertTrue(SupportUnreadStore.hasUnread(context))
        SupportUnreadStore.clearConversation(context, first, "push-test-message-1")
        assertEquals(setOf(second), SupportUnreadStore.unreadConversationIds(context))
        assertFalse(SupportUnreadStore.isActiveConversation(second))
        SupportUnreadStore.clearConversation(context, second, "push-test-message-2")
    }

    @Test
    fun pushWithoutMessageIdDoesNotCreateAnUnreadConversationAndPendingIdsStillCount() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val conversationId = "push-test-empty-${UUID.randomUUID()}"
        SupportUnreadStore.clearConversation(context, conversationId)

        assertTrue(
            SupportUnreadStore.recordSupportMessage(context, conversationId, null)
        )
        assertFalse(SupportUnreadStore.hasUnread(context))
        assertFalse(conversationId in SupportUnreadStore.unreadConversationIds(context))

        val pendingMessageId = "push-test-pending-${UUID.randomUUID()}"
        SupportUnreadStore.recordSupportMessage(context, null, pendingMessageId)
        assertEquals(1, SupportUnreadStore.unreadMessageCount(context))
        SupportUnreadStore.clearPendingMessage(context, pendingMessageId)
        assertFalse(SupportUnreadStore.hasUnread(context))
    }

    @Test
    fun zeroCountCancelsOnlyTheStableBadgeNotification() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = NotificationManagerCompat.from(context)
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        assumeTrue(manager.areNotificationsEnabled())
        SupportNotificationChannel.create(context)
        val unrelatedTag = "push-test-unrelated-${UUID.randomUUID()}"
        val unrelatedId = 73105
        try {
            LauncherBadgeHelper.postActualNotification(
                context,
                NotificationCompat.Builder(context, SupportNotificationChannel.CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_stat_support)
                    .setContentTitle("Support")
                    .setContentText("A real test notification"),
                unreadCount = 3
            )
            manager.notify(
                unrelatedTag,
                unrelatedId,
                NotificationCompat.Builder(context, SupportNotificationChannel.CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_stat_support)
                    .setContentTitle("Unrelated")
                    .setContentText("Keep this notification")
                    .build()
            )
            assertEquals(
                3,
                notificationManager.activeNotifications
                    .single { it.tag == LauncherBadgeHelper.TAG }
                    .notification.number
            )

            LauncherBadgeHelper.updateExistingNotification(context, 0)

            assertTrue(
                notificationManager.activeNotifications.none {
                    it.tag == LauncherBadgeHelper.TAG
                }
            )
            assertTrue(
                notificationManager.activeNotifications.any {
                    it.tag == unrelatedTag && it.id == unrelatedId
                }
            )
        } finally {
            LauncherBadgeHelper.clear(context)
            manager.cancel(unrelatedTag, unrelatedId)
        }
    }

    @Test
    fun supportPushForActiveConversationRecordsUnreadRefreshesAndDeduplicates() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val conversationId = "push-test-active-conversation"
        val messageId = "push-test-active-${UUID.randomUUID()}"
        SupportUnreadStore.clearConversation(context, conversationId)
        SupportUnreadStore.setActiveConversation(conversationId)
        val initialRefreshSignal =
            SupportUnreadStore.conversationRefreshSignal(context, conversationId)
        SupportUnreadStore.dismissForegroundAlert(context, messageId)

        val firstDelivery = handleSupportPush(
            context,
            PushNotificationPayload.Support(conversationId, messageId),
            appForeground = true
        ) { error("Foreground push must not use the background notification path.") }

        assertTrue(firstDelivery)
        assertEquals(
            setOf(messageId),
            SupportUnreadStore.unreadMessageIds(context, conversationId)
        )
        assertEquals(
            initialRefreshSignal + 1,
            SupportUnreadStore.conversationRefreshSignal(context, conversationId)
        )
        assertEquals(
            SupportUnreadStore.ForegroundAlert(conversationId, messageId),
            SupportUnreadStore.foregroundAlert(context)
        )

        val duplicateDelivery = handleSupportPush(
            context,
            PushNotificationPayload.Support(conversationId, messageId),
            appForeground = true
        ) { error("Duplicate foreground push must not post a background notification.") }

        assertFalse(duplicateDelivery)
        assertEquals(
            setOf(messageId),
            SupportUnreadStore.unreadMessageIds(context, conversationId)
        )
        assertEquals(
            initialRefreshSignal + 1,
            SupportUnreadStore.conversationRefreshSignal(context, conversationId)
        )
        assertEquals(
            listOf(SupportUnreadStore.ForegroundAlert(conversationId, messageId)),
            SupportUnreadStore.foregroundAlerts(context)
        )
        SupportUnreadStore.dismissForegroundAlert(context, messageId)
        SupportUnreadStore.clearConversation(context, conversationId)
        assertFalse(
            handleSupportPush(
                context,
                PushNotificationPayload.Support(conversationId, messageId),
                appForeground = true
            ) { error("A duplicate push must not post a notification.") }
        )
        assertTrue(SupportUnreadStore.unreadMessageIds(context, conversationId).isEmpty())
        assertTrue(
            SupportUnreadStore.foregroundAlerts(context)
                .none { it.messageId == messageId }
        )
        SupportUnreadStore.setActiveConversation(null)
        SupportUnreadStore.clearConversation(context, conversationId)
    }

    @Test
    fun backgroundSupportPushKeepsSystemNotificationAndLocalUnreadIndependent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val conversationId = "push-test-background-conversation"
        val messageId = "push-test-background-${UUID.randomUUID()}"
        SupportUnreadStore.clearConversation(context, conversationId)
        SupportUnreadStore.dismissForegroundAlert(context, messageId)
        var backgroundNotificationShown = false

        val handled = handleSupportPush(
            context,
            PushNotificationPayload.Support(conversationId, messageId),
            appForeground = false
        ) { backgroundNotificationShown = true }

        assertTrue(handled)
        assertTrue(backgroundNotificationShown)
        assertEquals(
            setOf(messageId),
            SupportUnreadStore.unreadMessageIds(context, conversationId)
        )
        assertFalse(
            SupportUnreadStore.foregroundAlerts(context).any { it.messageId == messageId }
        )
        SupportUnreadStore.clearConversation(context, conversationId)
    }

    @Test
    fun successfulTextReplyClearsSupportUnreadState() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val conversationId = "push-test-text-reply-${UUID.randomUUID()}"
        SupportUnreadStore.recordSupportMessage(
            context,
            conversationId,
            "push-test-text-message-${UUID.randomUUID()}"
        )

        val result = sendSupportReplyAndClearUnread(context, conversationId) {
            "sent text message"
        }

        assertEquals("sent text message", result)
        assertTrue(SupportUnreadStore.unreadMessageIds(context, conversationId).isEmpty())
    }

    @Test
    fun successfulImageReplyClearsSupportUnreadState() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val conversationId = "push-test-image-reply-${UUID.randomUUID()}"
        SupportUnreadStore.recordSupportMessage(
            context,
            conversationId,
            "push-test-image-message-${UUID.randomUUID()}"
        )

        val result = sendSupportReplyAndClearUnread(context, conversationId) {
            "sent image message"
        }

        assertEquals("sent image message", result)
        assertTrue(SupportUnreadStore.unreadMessageIds(context, conversationId).isEmpty())
    }

    @Test
    fun failedReplyDoesNotClearSupportUnreadState() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val conversationId = "push-test-failed-reply-${UUID.randomUUID()}"
        val messageId = "push-test-failed-message-${UUID.randomUUID()}"
        SupportUnreadStore.recordSupportMessage(context, conversationId, messageId)

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                sendSupportReplyAndClearUnread(context, conversationId) {
                    throw IllegalStateException("send failed")
                }
            }
        }

        assertEquals(
            setOf(messageId),
            SupportUnreadStore.unreadMessageIds(context, conversationId)
        )
        SupportUnreadStore.clearConversation(context, conversationId)
    }
}

private class FakePushTokenAuth : PushTokenAuthDataSource {
    val account = SupabaseAccount(
        id = "push-test-auth-user",
        isGuest = false,
        name = "Test user",
        email = "test@example.com",
        avatarUrl = null
    )

    override suspend fun currentAccount(): SupabaseAccount = account

    override suspend fun accessTokenFor(userId: String): String {
        assertEquals(account.id, userId)
        return "test-access-token"
    }
}

private class CapturingPushHttp : ContactHttpTransport {
    data class Request(
        val method: String,
        val url: String,
        val headers: Map<String, String>,
        val body: String?
    )

    val requests = mutableListOf<Request>()

    override fun execute(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray?,
        contentType: String?
    ): ContactHttpResponse {
        requests += Request(method, url, headers, body?.toString(Charsets.UTF_8))
        return ContactHttpResponse(204, "")
    }
}
