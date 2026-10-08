package com.kolammaster.app.notifications

internal sealed interface PushNotificationPayload {
    data class Support(
        val conversationId: String?,
        val messageId: String?
    ) : PushNotificationPayload

    data class Announcement(
        val title: String?,
        val body: String?,
        val announcementId: String?
    ) : PushNotificationPayload
}

internal object PushNotificationPayloadParser {
    fun parse(data: Map<String, String>): PushNotificationPayload? {
        val type = data["type"]?.trim()?.lowercase() ?: return null
        return when (type) {
            "support_message" -> {
                if (!data["sender"].equals("ADMIN", ignoreCase = true)) return null
                PushNotificationPayload.Support(
                    conversationId = data.firstValue("conversationId", "conversation_id"),
                    messageId = data.firstValue("messageId", "message_id", "id")
                )
            }
            "announcement", "announcement_message" ->
                PushNotificationPayload.Announcement(
                    title = data["title"]?.takeIf(String::isNotBlank),
                    body = data["body"]?.takeIf(String::isNotBlank),
                    announcementId = data.firstValue("announcementId", "announcement_id")
                )
            else -> null
        }
    }

    private fun Map<String, String>.firstValue(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key -> get(key)?.takeIf(String::isNotBlank) }
}
