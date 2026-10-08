package com.kolammaster.app.notifications

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal object SupportUnreadStore {
    private const val PREFERENCES_NAME = "kolam_master_support_notifications"
    private const val UNREAD_KEY = "unread_support_messages"
    private const val PENDING_MESSAGE_IDS_KEY = "pending_support_message_ids"
    private const val SEEN_MESSAGE_IDS_KEY = "seen_support_message_ids"
    private const val FOREGROUND_ALERTS_KEY = "foreground_support_alerts"
    private const val REFRESH_SIGNAL_PREFIX = "support_refresh_signal_"
    private const val MESSAGE_VERSION_KEY = "support_message_version"
    private const val UNREAD_VERSION_KEY = "support_unread_version"
    private const val CURRENT_USER_ID_KEY = "support_unread_user_id"
    @Volatile
    private var activeConversationId: String? = null

    @Synchronized
    fun unreadConversationIds(context: Context): Set<String> =
        readUnread(context).filterValues { it.isNotEmpty() }.keys

    @Synchronized
    fun unreadMessageIds(context: Context, conversationId: String): Set<String> =
        readUnread(context)[conversationId].orEmpty()

    data class ForegroundAlert(
        val conversationId: String?,
        val messageId: String?
    )

    @Synchronized
    fun hasUnread(context: Context): Boolean {
        val preferences = preferences(context)
        return readUnread(context).values.any { it.isNotEmpty() } ||
            preferences.getStringSet(PENDING_MESSAGE_IDS_KEY, emptySet())
                .orEmpty().any(String::isNotBlank)
    }

    @Synchronized
    fun unreadMessageCount(context: Context): Int {
        val pending = preferences(context).getStringSet(PENDING_MESSAGE_IDS_KEY, emptySet())
            .orEmpty()
        return countOutstandingSupportMessageIds(readUnread(context), pending)
    }

    @Synchronized
    fun unreadVersion(context: Context): Long =
        preferences(context).getLong(UNREAD_VERSION_KEY, 0L)

    @Synchronized
    fun useAccount(context: Context, userId: String) {
        require(userId.isNotBlank()) { "Support unread state requires a user ID." }
        val preferences = preferences(context)
        if (preferences.getString(CURRENT_USER_ID_KEY, null) == userId) return
        preferences.edit()
            .putString(CURRENT_USER_ID_KEY, userId)
            .putString(UNREAD_KEY, JSONObject().toString())
            .putStringSet(PENDING_MESSAGE_IDS_KEY, emptySet())
            .putLong(UNREAD_VERSION_KEY, preferences.getLong(UNREAD_VERSION_KEY, 0L) + 1L)
            .commit()
    }

    @Synchronized
    fun hasUnread(context: Context, conversationId: String): Boolean =
        readUnread(context)[conversationId].orEmpty().isNotEmpty()

    @Synchronized
    fun messageVersion(context: Context): Long =
        preferences(context).getLong(MESSAGE_VERSION_KEY, 0L)

    @Synchronized
    fun isActiveConversation(conversationId: String?): Boolean =
        !conversationId.isNullOrBlank() &&
            activeConversationId == conversationId

    @Synchronized
    fun recordSupportMessage(
        context: Context,
        conversationId: String?,
        messageId: String?
    ): Boolean {
        val normalizedConversationId = conversationId?.takeIf(String::isNotBlank)
        val normalizedMessageId = messageId?.takeIf(String::isNotBlank)
        val preferences = preferences(context)
        val editor = preferences.edit()
        if (normalizedMessageId != null) {
            val seen = readSeenMessageIds(context)
            if (normalizedMessageId in seen) return false
            val updatedSeen = seen + normalizedMessageId
            editor.putString(SEEN_MESSAGE_IDS_KEY, JSONArray(updatedSeen).toString())
        }
        if (normalizedConversationId != null) {
            if (normalizedMessageId != null) {
                val unread = readUnread(context).toMutableMap()
                unread[normalizedConversationId] =
                    unread[normalizedConversationId].orEmpty() + normalizedMessageId
                editor.putString(UNREAD_KEY, unread.toJson())
            }
        } else if (normalizedMessageId != null) {
            val pending = preferences.getStringSet(PENDING_MESSAGE_IDS_KEY, emptySet())
                .orEmpty().toMutableSet()
            pending += normalizedMessageId
            editor.putStringSet(PENDING_MESSAGE_IDS_KEY, pending)
        }
        editor.putLong(MESSAGE_VERSION_KEY, preferences.getLong(MESSAGE_VERSION_KEY, 0L) + 1L)
        editor.putLong(UNREAD_VERSION_KEY, preferences.getLong(UNREAD_VERSION_KEY, 0L) + 1L)
        editor.commit()
        return true
    }

    @Synchronized
    fun recordForegroundAlert(
        context: Context,
        conversationId: String?,
        messageId: String?
    ) {
        val preferences = preferences(context)
        val alerts = readForegroundAlerts(context).toMutableList()
        if (messageId != null && alerts.any { it.messageId == messageId }) return
        alerts += ForegroundAlert(
            conversationId = conversationId?.takeIf(String::isNotBlank),
            messageId = messageId?.takeIf(String::isNotBlank)
        )
        preferences.edit()
            .putString(FOREGROUND_ALERTS_KEY, alerts.toJson())
            .commit()
    }

    @Synchronized
    fun foregroundAlert(context: Context): ForegroundAlert? =
        readForegroundAlerts(context).firstOrNull()

    @Synchronized
    fun foregroundAlerts(context: Context): List<ForegroundAlert> =
        readForegroundAlerts(context)

    @Synchronized
    fun dismissForegroundAlert(context: Context, messageId: String?) {
        val preferences = preferences(context)
        val alerts = readForegroundAlerts(context).toMutableList()
        val index = alerts.indexOfFirst { it.messageId == messageId }
        if (index >= 0) alerts.removeAt(index)
        preferences.edit().putString(FOREGROUND_ALERTS_KEY, alerts.toJson()).commit()
    }

    @Synchronized
    fun notifyActiveConversationRefresh(context: Context, conversationId: String) {
        val key = conversationRefreshPreferenceKey(conversationId)
        val preferences = preferences(context)
        preferences.edit().putInt(key, preferences.getInt(key, 0) + 1).commit()
    }

    fun isConversationRefreshSignal(key: String?): Boolean =
        key?.startsWith(REFRESH_SIGNAL_PREFIX) == true

    fun conversationRefreshPreferenceKey(conversationId: String): String =
        REFRESH_SIGNAL_PREFIX + Base64.encodeToString(
            conversationId.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )

    @Synchronized
    fun conversationRefreshSignal(context: Context, conversationId: String): Int {
        val key = conversationRefreshPreferenceKey(conversationId)
        return preferences(context).getInt(key, 0)
    }

    @Synchronized
    fun clearConversation(context: Context, conversationId: String, messageId: String? = null) {
        val preferences = preferences(context)
        val unread = readUnread(context).toMutableMap()
        unread.remove(conversationId)
        val pending = preferences.getStringSet(PENDING_MESSAGE_IDS_KEY, emptySet())
            .orEmpty().toMutableSet()
        messageId?.let(pending::remove)
        preferences.edit()
            .putString(UNREAD_KEY, unread.toJson())
            .putStringSet(PENDING_MESSAGE_IDS_KEY, pending)
            .putLong(UNREAD_VERSION_KEY, preferences.getLong(UNREAD_VERSION_KEY, 0L) + 1L)
            .commit()
    }

    @Synchronized
    fun setActiveConversation(conversationId: String?) {
        activeConversationId = conversationId
    }

    @Synchronized
    fun clearPendingMessage(context: Context, messageId: String) {
        val preferences = preferences(context)
        val pending = preferences.getStringSet(PENDING_MESSAGE_IDS_KEY, emptySet())
            .orEmpty().toMutableSet()
        if (pending.remove(messageId)) {
            preferences.edit()
                .putStringSet(PENDING_MESSAGE_IDS_KEY, pending)
                .putLong(UNREAD_VERSION_KEY, preferences.getLong(UNREAD_VERSION_KEY, 0L) + 1L)
                .commit()
        }
    }

    private fun readUnread(context: Context): Map<String, Set<String>> {
        val encoded = preferences(context).getString(UNREAD_KEY, null) ?: return emptyMap()
        return try {
            val json = JSONObject(encoded)
            buildMap {
                val keys = json.keys()
                while (keys.hasNext()) {
                    val id = keys.next()
                    val messages = json.optJSONArray(id) ?: JSONArray()
                    put(id, (0 until messages.length()).map { index ->
                        messages.optString(index)
                    }.filter(String::isNotBlank).toSet())
                }
            }
        } catch (_: JSONException) {
            emptyMap()
        }
    }

    private fun readSeenMessageIds(context: Context): List<String> {
        val encoded = preferences(context).getString(SEEN_MESSAGE_IDS_KEY, null) ?: return emptyList()
        return try {
            val ids = JSONArray(encoded)
            (0 until ids.length()).map { ids.getString(it) }
        } catch (_: JSONException) {
            emptyList()
        }
    }

    private fun readForegroundAlerts(context: Context): List<ForegroundAlert> {
        val encoded = preferences(context).getString(FOREGROUND_ALERTS_KEY, null) ?: return emptyList()
        return try {
            val alerts = JSONArray(encoded)
            (0 until alerts.length()).mapNotNull { index ->
                val alert = alerts.optJSONObject(index) ?: return@mapNotNull null
                ForegroundAlert(
                    conversationId = alert.optString("conversationId")
                        .takeIf(String::isNotBlank),
                    messageId = alert.optString("messageId")
                        .takeIf(String::isNotBlank)
                )
            }
        } catch (_: JSONException) {
            emptyList()
        }
    }

    private fun Map<String, Set<String>>.toJson(): String =
        JSONObject().apply {
            forEach { (conversationId, messageIds) ->
                put(conversationId, JSONArray(messageIds.toList()))
            }
        }.toString()

    private fun List<ForegroundAlert>.toJson(): String =
        JSONArray().apply {
            forEach { alert ->
                put(
                    JSONObject()
                        .put("conversationId", alert.conversationId)
                        .put("messageId", alert.messageId)
                )
            }
        }.toString()

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
}
