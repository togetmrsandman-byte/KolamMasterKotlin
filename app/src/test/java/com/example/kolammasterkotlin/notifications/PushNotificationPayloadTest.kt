package com.kolammaster.app.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PushNotificationPayloadTest {
    @Test
    fun parsesCamelCaseSupportIdentifiers() {
        val payload = PushNotificationPayloadParser.parse(
            mapOf(
                "type" to "support_message",
                "sender" to "ADMIN",
                "conversationId" to "conversation-1",
                "messageId" to "message-1"
            )
        ) as PushNotificationPayload.Support

        assertEquals("conversation-1", payload.conversationId)
        assertEquals("message-1", payload.messageId)
    }

    @Test
    fun parsesSnakeCaseAndFallbackMessageId() {
        val payload = PushNotificationPayloadParser.parse(
            mapOf(
                "type" to "support_message",
                "sender" to "ADMIN",
                "conversation_id" to "conversation-2",
                "id" to "message-2"
            )
        ) as PushNotificationPayload.Support

        assertEquals("conversation-2", payload.conversationId)
        assertEquals("message-2", payload.messageId)
    }

    @Test
    fun rejectsUnrelatedAndNonAdminSupportMessages() {
        assertNull(PushNotificationPayloadParser.parse(mapOf("type" to "unrelated")))
        assertNull(
            PushNotificationPayloadParser.parse(
                mapOf(
                    "type" to "support_message",
                    "sender" to "USER",
                    "conversationId" to "conversation-1"
                )
            )
        )
        assertNull(PushNotificationPayloadParser.parse(emptyMap()))
    }

    @Test
    fun parsesAnnouncementPayload() {
        val payload = PushNotificationPayloadParser.parse(
            mapOf(
                "type" to "announcement",
                "title" to "Notice",
                "body" to "An update is ready."
            )
        ) as PushNotificationPayload.Announcement

        assertEquals("Notice", payload.title)
        assertEquals("An update is ready.", payload.body)
    }

    @Test
    fun tokenRowUsesFcmProviderAndAndroidPlatform() {
        val row = buildFcmTokenRow("auth-user", "token-value", "2026-04-01T00:00:00.000Z")

        assertEquals("auth-user", row.userId)
        assertEquals("token-value", row.token)
        assertEquals("fcm", row.provider)
        assertEquals("android", row.platform)
    }
}
