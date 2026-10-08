package com.kolammaster.app.notifications

import com.kolammaster.app.Announcement

internal fun countOutstandingSupportMessageIds(
    unreadByConversation: Map<String, Set<String>>,
    pendingMessageIds: Set<String>
): Int =
    (unreadByConversation.values.asSequence().flatten() + pendingMessageIds.asSequence())
        .filter(String::isNotBlank)
        .toSet()
        .size

internal fun calculateCombinedUnreadCount(
    announcements: List<Announcement>,
    outstandingSupportMessageCount: Int,
    pendingAnnouncementIds: Set<String> = emptySet()
): Int =
    announcements.count { !it.isRead } +
        pendingAnnouncementIds.count { pendingId ->
            pendingId.isNotBlank() &&
                announcements.none { !it.isRead && it.id == pendingId }
        } +
        outstandingSupportMessageCount

internal fun calculateAnnouncementUnreadCount(
    authoritativeUnreadCount: Int,
    authoritativeAnnouncementIds: Set<String>,
    pendingAnnouncementIds: Set<String>
): Int =
    authoritativeUnreadCount.coerceAtLeast(0) +
        pendingAnnouncementIds.count {
            it.isNotBlank() && it !in authoritativeAnnouncementIds
        }
