package com.kolammaster.app

import org.junit.Assert.assertEquals
import org.junit.Test

class ContactConversationOrderingTest {
    @Test
    fun contactListOnlyRevealsWhenTheLeadingConversationChanges() {
        assertEquals(
            true,
            shouldRevealContactConversationPromotion(
                previousOrder = listOf("A", "B", "C"),
                currentOrder = listOf("C", "A", "B")
            )
        )
        assertEquals(
            false,
            shouldRevealContactConversationPromotion(
                previousOrder = listOf("A", "B", "C"),
                currentOrder = listOf("A", "B", "C")
            )
        )
        assertEquals(
            true,
            shouldRevealContactConversationPromotion(
                previousOrder = listOf("A", "B", "C"),
                currentOrder = listOf("A", "C", "B")
            )
        )
        assertEquals(
            false,
            shouldRevealContactConversationPromotion(
                previousOrder = listOf("A", "B", "C"),
                currentOrder = listOf("A", "X", "B", "C")
            )
        )
        assertEquals(
            false,
            shouldRevealContactConversationPromotion(
                previousOrder = null,
                currentOrder = listOf("A", "B", "C")
            )
        )
    }

    @Test
    fun unreadConversationPrecedesNewerReadConversation() {
        val unread = conversation("unread", messageAt = "2026-10-07T20:00:00Z")
        val newerRead = conversation("read", messageAt = "2026-10-07T21:00:00Z")

        val ordered = orderContactConversations(
            listOf(newerRead, unread),
            setOf(unread.id)
        )

        assertEquals(listOf(unread, newerRead), ordered)
    }

    @Test
    fun unreadConversationsAreOrderedByNewestMessageActivityFirst() {
        val older = conversation("older-unread", messageAt = "2026-10-07T20:00:00Z")
        val middle = conversation("middle-unread", messageAt = "2026-10-07T21:00:00Z")
        val newer = conversation("newer-unread", messageAt = "2026-10-07T22:00:00Z")

        val ordered = orderContactConversations(
            listOf(older, newer, middle),
            setOf(older.id, middle.id, newer.id)
        )

        assertEquals(listOf(newer, middle, older), ordered)
    }

    @Test
    fun threeUnreadConversationsStayAboveAllReadConversations() {
        val unreadOld = conversation("unread-old", "2026-10-07T18:00:00Z")
        val unreadRecent = conversation("unread-recent", "2026-10-07T20:00:00Z")
        val unreadMiddle = conversation("unread-middle", "2026-10-07T19:00:00Z")
        val readOld = conversation("read-old", "2026-10-07T17:00:00Z")
        val readRecent = conversation("read-recent", "2026-10-07T21:00:00Z")

        assertEquals(
            listOf(unreadRecent, unreadMiddle, unreadOld, readRecent, readOld),
            orderContactConversations(
                listOf(readOld, unreadOld, readRecent, unreadRecent, unreadMiddle),
                setOf(unreadOld.id, unreadMiddle.id, unreadRecent.id)
            )
        )
    }

    @Test
    fun readConversationsAreOrderedByNewestMessageActivityFirst() {
        val older = conversation("older-read", messageAt = "2026-10-07T20:00:00Z")
        val newer = conversation("newer-read", messageAt = "2026-10-07T21:00:00Z")

        val ordered = orderContactConversations(listOf(older, newer), emptySet())

        assertEquals(listOf(newer, older), ordered)
    }

    @Test
    fun newerMessageMovesConversationToItsLatestActivityPosition() {
        val conversationA = conversation("a", messageAt = "2026-10-07T20:00:00Z")
        val conversationB = conversation("b", messageAt = "2026-10-07T21:00:00Z")

        val beforeReply = orderContactConversations(
            listOf(conversationA, conversationB),
            emptySet()
        )
        val afterReply = orderContactConversations(
            listOf(
                conversationA.copy(latestMessageCreatedAt = "2026-10-07T22:00:00Z"),
                conversationB
            ),
            emptySet()
        )

        assertEquals(listOf(conversationB, conversationA), beforeReply)
        assertEquals(
            listOf(
                conversationA.copy(latestMessageCreatedAt = "2026-10-07T22:00:00Z"),
                conversationB
            ),
            afterReply
        )
    }

    @Test
    fun missingMessageActivityFallsBackToUpdatedAt() {
        val older = conversation(
            "older",
            messageAt = null,
            updatedAt = "2026-10-07T20:00:00Z"
        )
        val newer = conversation(
            "newer",
            messageAt = null,
            updatedAt = "2026-10-07T21:00:00Z"
        )

        assertEquals(
            listOf(newer, older),
            orderContactConversations(listOf(older, newer), emptySet())
        )
    }

    @Test
    fun missingUpdatedAtFallsBackToCreatedAt() {
        val older = conversation(
            "older",
            messageAt = null,
            updatedAt = "",
            createdAt = "2026-10-07T20:00:00Z"
        )
        val newer = conversation(
            "newer",
            messageAt = null,
            updatedAt = "",
            createdAt = "2026-10-07T21:00:00Z"
        )

        assertEquals(
            listOf(newer, older),
            orderContactConversations(listOf(older, newer), emptySet())
        )
    }

    private fun conversation(
        id: String,
        messageAt: String?,
        updatedAt: String = "2026-10-07T19:00:00Z",
        createdAt: String = "2026-10-07T18:00:00Z"
    ) = ContactConversation(
        id = id,
        userId = "user",
        email = "",
        phone = "",
        subject = id,
        status = "OPEN",
        createdAt = createdAt,
        updatedAt = updatedAt,
        latestMessageCreatedAt = messageAt
    )
}
