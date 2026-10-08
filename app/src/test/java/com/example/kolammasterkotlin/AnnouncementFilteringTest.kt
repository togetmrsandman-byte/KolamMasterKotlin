package com.kolammaster.app

import org.junit.Assert.assertEquals
import org.junit.Test

class AnnouncementFilteringTest {
    @Test
    fun excludesInvalidAndNonLaterTimestamps() {
        val accountCreatedAt = "2024-01-01T00:00:00Z"
        val visible = announcement(
            id = uuid(1),
            createdAt = "2024-01-01T00:00:00.001Z"
        )
        val announcements = listOf(
            visible,
            announcement(uuid(2), accountCreatedAt),
            announcement(uuid(3), "2023-12-31T23:59:59Z"),
            announcement(uuid(4), "not-a-timestamp"),
            announcement("not-a-uuid", "2024-01-02T00:00:00Z")
        )

        assertEquals(listOf(visible), filterVisibleAnnouncements(announcements, accountCreatedAt))
    }

    @Test
    fun deduplicatesAndKeepsOnlyTheNewestFifteen() {
        val announcements = (1..16).map { index ->
            announcement(
                id = uuid(index),
                createdAt = "2024-01-02T00:00:${"%02d".format(index)}Z"
            )
        } + announcement(
            id = uuid(16),
            createdAt = "2024-01-02T00:00:01Z"
        )

        val result = filterVisibleAnnouncements(
            announcements = announcements,
            accountCreatedAt = "2024-01-01T00:00:00Z"
        )

        assertEquals(15, result.size)
        assertEquals(uuid(16), result.first().id)
        assertEquals(uuid(2), result.last().id)
        assertEquals(15, result.map(Announcement::id).distinct().size)
    }

    @Test
    fun markAllIncludesAlreadyReadAndUnreadEligibleAnnouncements() {
        val announcements = listOf(
            announcement(uuid(1), "2024-01-02T00:00:01Z").copy(readAt = "2024-01-03T00:00:00Z"),
            announcement(uuid(2), "2024-01-02T00:00:02Z")
        )

        assertEquals(
            listOf(uuid(1), uuid(2)),
            announcementIdsForMarkAll(announcements)
        )
    }

    @Test
    fun announcementCacheUsesOldAppPerUserKeyFormat() {
        assertEquals(
            "kolam-master-announcement-content-cache:user-123",
            announcementContentCacheKey("user-123")
        )
    }

    private fun announcement(id: String, createdAt: String) = Announcement(
        id = id,
        subject = "Subject",
        message = "Message",
        imageUrl = null,
        createdAt = createdAt,
        readAt = null
    )

    private fun uuid(number: Int): String =
        "00000000-0000-0000-0000-${number.toString().padStart(12, '0')}"
}
