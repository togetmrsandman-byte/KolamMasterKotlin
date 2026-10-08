package com.kolammaster.app.notifications

import com.kolammaster.app.Announcement
import org.junit.Assert.assertEquals
import org.junit.Test

class UnreadBadgeCountTest {
    @Test
    fun combinedCountAddsUnreadAnnouncementsAndUniqueSupportMessageIds() {
        val announcements = listOf(
            announcement("1", null),
            announcement("2", "read"),
            announcement("3", null)
        )
        val supportCount = countOutstandingSupportMessageIds(
            unreadByConversation = mapOf(
                "conversation-1" to setOf("message-a", "message-b"),
                "conversation-2" to setOf("message-a", " ")
            ),
            pendingMessageIds = setOf("message-c", "message-b", "")
        )

        assertEquals(3, supportCount)
        assertEquals(5, calculateCombinedUnreadCount(announcements, supportCount))
    }

    @Test
    fun pendingAnnouncementsOnlyCountWhenNotAlreadyUnreadInCache() {
        val announcements = listOf(
            announcement("unread", null),
            announcement("read", "read")
        )

        assertEquals(
            5,
            calculateCombinedUnreadCount(
                announcements,
                outstandingSupportMessageCount = 2,
                pendingAnnouncementIds = setOf("unread", "read", "new", "new", "")
            )
        )
    }

    @Test
    fun announcementUnreadCountAddsUniquePendingIdsToExistingUnreadCount() {
        assertEquals(
            4,
            calculateAnnouncementUnreadCount(
                authoritativeUnreadCount = 3,
                authoritativeAnnouncementIds = setOf("existing-1", "existing-2", "existing-3"),
                pendingAnnouncementIds = setOf("new-1")
            )
        )
        assertEquals(
            5,
            calculateAnnouncementUnreadCount(
                authoritativeUnreadCount = 4,
                authoritativeAnnouncementIds = setOf(
                    "existing-1",
                    "existing-2",
                    "existing-3",
                    "existing-4"
                ),
                pendingAnnouncementIds = setOf("new-1")
            )
        )
        assertEquals(
            4,
            calculateAnnouncementUnreadCount(
                authoritativeUnreadCount = 3,
                authoritativeAnnouncementIds = setOf("existing-1", "existing-2", "existing-3"),
                pendingAnnouncementIds = setOf("new-1", "new-1")
            )
        )
    }

    private fun announcement(id: String, readAt: String?) = Announcement(
        id = id,
        subject = "Subject",
        message = "Message",
        imageUrl = null,
        createdAt = "2024-01-02T00:00:00Z",
        readAt = readAt
    )
}
