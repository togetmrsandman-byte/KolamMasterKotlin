package com.kolammaster.app

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContactSupportNotificationInstrumentedTest {
    @Test
    fun readsCamelCaseConversationIdFromSupportAdminNotification() {
        val notification = Intent()
            .putExtra("type", "support_message")
            .putExtra("sender", "ADMIN")
            .putExtra("conversationId", "conversation-1")
            .toContactSupportNotification()

        assertEquals("conversation-1", notification?.conversationId)
        assertNull(notification?.messageId)
    }

    @Test
    fun readsSnakeCaseMessageIdForOwnedConversationResolution() {
        val notification = Intent()
            .putExtra("type", "support_message")
            .putExtra("sender", "ADMIN")
            .putExtra("message_id", "message-1")
            .toContactSupportNotification()

        assertNull(notification?.conversationId)
        assertEquals("message-1", notification?.messageId)
    }

    @Test
    fun ignoresNotificationsThatAreNotSupportAdminMessages() {
        val notification = Intent()
            .putExtra("type", "support_message")
            .putExtra("sender", "USER")
            .putExtra("conversation_id", "conversation-1")
            .toContactSupportNotification()

        assertNull(notification)
    }
}
