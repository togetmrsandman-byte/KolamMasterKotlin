package com.kolammaster.app.notifications

import android.content.Context

internal object PendingAnnouncementStore {
    private const val PREFERENCES_NAME = "kolam_master_announcements"
    private const val UNREAD_COUNT_KEY = "announcement_unread_count"
    private const val PENDING_IDS_KEY = "pending_announcement_ids"
    private const val SEEN_IDS_KEY = "seen_announcement_ids"

    @Synchronized
    fun record(context: Context, announcementId: String): Int {
        require(announcementId.isNotBlank()) {
            "Pending announcements require an announcement ID."
        }
        val preferences = preferences(context)
        val pendingIds = preferences.getStringSet(PENDING_IDS_KEY, emptySet())
            .orEmpty()
            .toMutableSet()
        val seenIds = preferences.getStringSet(SEEN_IDS_KEY, emptySet())
            .orEmpty()
            .toMutableSet()
        val count = count(context)
        if (announcementId in seenIds) return count
        seenIds += announcementId
        pendingIds += announcementId
        val updatedCount = count + 1
        check(
            preferences.edit()
                .putInt(UNREAD_COUNT_KEY, updatedCount)
                .putStringSet(PENDING_IDS_KEY, pendingIds)
                .putStringSet(SEEN_IDS_KEY, seenIds)
                .commit()
        ) {
            "Could not persist pending announcements."
        }
        return updatedCount
    }

    @Synchronized
    fun count(context: Context): Int =
        preferences(context).getInt(UNREAD_COUNT_KEY, 0).coerceAtLeast(0)

    @Synchronized
    fun ids(context: Context): Set<String> =
        preferences(context).getStringSet(PENDING_IDS_KEY, emptySet()).orEmpty().toSet()

    @Synchronized
    fun reconcile(
        context: Context,
        authoritativeUnreadCount: Int,
        authoritativeIds: Set<String>,
        resolvedIds: Set<String> = emptySet()
    ): Int {
        val preferences = preferences(context)
        val pendingIds = preferences.getStringSet(PENDING_IDS_KEY, emptySet())
            .orEmpty()
            .toMutableSet()
        pendingIds.removeAll(authoritativeIds)
        pendingIds.removeAll(resolvedIds)
        val seenIds = preferences.getStringSet(SEEN_IDS_KEY, emptySet())
            .orEmpty()
            .toMutableSet()
        seenIds.addAll(authoritativeIds)
        seenIds.addAll(resolvedIds)
        val updatedCount = calculateAnnouncementUnreadCount(
            authoritativeUnreadCount,
            authoritativeIds,
            pendingIds
        )
        check(
            preferences.edit()
                .putInt(UNREAD_COUNT_KEY, updatedCount)
                .putStringSet(PENDING_IDS_KEY, pendingIds)
                .putStringSet(SEEN_IDS_KEY, seenIds)
                .commit()
        ) {
            "Could not reconcile announcement unread state."
        }
        return updatedCount
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
}
