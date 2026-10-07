package com.kolammaster.app

import org.json.JSONException
import org.json.JSONObject

internal data class ContactConversation(
    val id: String,
    val userId: String,
    val email: String,
    val phone: String,
    val subject: String,
    val status: String,
    val createdAt: String,
    val updatedAt: String,
    val latestMessageCreatedAt: String? = null
)

internal data class ContactMessage(
    val id: String,
    val conversationId: String,
    val sender: String,
    val message: String,
    val imageUrl: String?,
    val createdAt: String
)

internal class ContactDataException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

internal object ContactJson {
    fun decodeConversation(value: JSONObject): ContactConversation = ContactConversation(
        id = value.requiredString("id"),
        userId = value.requiredString("user_id"),
        email = value.optionalString("email"),
        phone = value.optionalString("phone"),
        subject = value.requiredString("subject"),
        status = value.requiredString("status"),
        createdAt = value.optionalString("created_at"),
        updatedAt = value.optionalString("updated_at"),
        latestMessageCreatedAt = value.latestContactMessageCreatedAt()
    )

    fun decodeMessage(value: JSONObject): ContactMessage = ContactMessage(
        id = value.requiredString("id"),
        conversationId = value.requiredString("conversation_id"),
        sender = value.requiredString("sender"),
        message = value.requiredString("message"),
        imageUrl = value.optionalString("image_url").takeIf(String::isNotEmpty),
        createdAt = value.requiredString("created_at")
    )

    fun encodeConversationInsert(
        userId: String,
        email: String,
        phone: String,
        subject: String
    ): String = JSONObject()
        .put("user_id", userId)
        .put("email", email)
        .put("phone", phone)
        .put("subject", subject)
        .toString()

    fun encodeInitialMessage(
        conversationId: String,
        message: String,
        imageUrl: String? = null
    ): String = JSONObject()
        .put("conversation_id", conversationId)
        .put("sender", "USER")
        .put("message", message)
        .apply {
            if (imageUrl != null) put("image_url", imageUrl)
        }
        .toString()

    fun encodeUserMessage(
        conversationId: String,
        message: String,
        imageUrl: String?
    ): String = JSONObject()
        .put("conversation_id", conversationId)
        .put("sender", "USER")
        .put("message", message)
        .put("image_url", imageUrl ?: JSONObject.NULL)
        .toString()

    private fun JSONObject.requiredString(key: String): String =
        opt(key) as? String
            ?: throw ContactDataException("Contact response is missing '$key'.")

    private fun JSONObject.optionalString(key: String): String = when (val value = opt(key)) {
        null, JSONObject.NULL -> ""
        is String -> value
        else -> throw ContactDataException("Contact response field '$key' is not a string.")
    }

    private fun JSONObject.latestContactMessageCreatedAt(): String? {
        val messages = optJSONArray("contact_messages") ?: return null
        return (0 until messages.length())
            .mapNotNull { index ->
                messages.optJSONObject(index)?.let { message ->
                    message.optString("created_at").takeIf(String::isNotBlank)
                }
            }
            .maxOrNull()
    }
}
