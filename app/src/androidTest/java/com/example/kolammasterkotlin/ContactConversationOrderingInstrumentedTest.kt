package com.kolammaster.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kolammaster.app.notifications.SupportUnreadStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ContactConversationOrderingInstrumentedTest {
    @Test
    fun acceptedSupportMessagesAdvanceVersionAndDuplicatesDoNot() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val conversationA = "ordering-version-a-${UUID.randomUUID()}"
        val conversationB = "ordering-version-b-${UUID.randomUUID()}"
        val firstMessage = "ordering-version-message-${UUID.randomUUID()}"
        val secondMessage = "ordering-version-message-${UUID.randomUUID()}"
        val thirdMessage = "ordering-version-message-${UUID.randomUUID()}"
        val initialVersion = SupportUnreadStore.messageVersion(context)

        try {
            assertTrue(
                SupportUnreadStore.recordSupportMessage(
                    context,
                    conversationA,
                    firstMessage
                )
            )
            assertEquals(initialVersion + 1L, SupportUnreadStore.messageVersion(context))
            assertFalse(
                SupportUnreadStore.recordSupportMessage(
                    context,
                    conversationA,
                    firstMessage
                )
            )
            assertEquals(initialVersion + 1L, SupportUnreadStore.messageVersion(context))
            assertTrue(
                SupportUnreadStore.recordSupportMessage(
                    context,
                    conversationA,
                    secondMessage
                )
            )
            assertEquals(initialVersion + 2L, SupportUnreadStore.messageVersion(context))
            assertTrue(
                SupportUnreadStore.recordSupportMessage(
                    context,
                    conversationB,
                    thirdMessage
                )
            )
            assertEquals(initialVersion + 3L, SupportUnreadStore.messageVersion(context))
            assertTrue(SupportUnreadStore.hasUnread(context, conversationA))
            assertTrue(SupportUnreadStore.hasUnread(context, conversationB))
        } finally {
            SupportUnreadStore.clearConversation(context, conversationA)
            SupportUnreadStore.clearConversation(context, conversationB)
        }
    }

    @Test
    fun clearingOneConversationPreservesOtherUnreadDotsAndSortPositions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val unreadOld = conversation("ordering-unread-old-${UUID.randomUUID()}", "2026-10-07T18:00:00Z")
        val unreadMiddle = conversation(
            "ordering-unread-middle-${UUID.randomUUID()}",
            "2026-10-07T19:00:00Z"
        )
        val unreadRecent = conversation(
            "ordering-unread-recent-${UUID.randomUUID()}",
            "2026-10-07T20:00:00Z"
        )
        val readOld = conversation("ordering-read-old-${UUID.randomUUID()}", "2026-10-07T17:00:00Z")
        val readRecent = conversation("ordering-read-recent-${UUID.randomUUID()}", "2026-10-07T21:00:00Z")
        val conversations = listOf(unreadOld, readRecent, unreadMiddle, readOld, unreadRecent)
        val unreadConversations = listOf(unreadOld, unreadMiddle, unreadRecent)
        val messageIds = unreadConversations.associate { it.id to "message-${UUID.randomUUID()}" }
        conversations.forEach { SupportUnreadStore.clearConversation(context, it.id) }

        try {
            unreadConversations.forEach { conversation ->
                val messageId = messageIds.getValue(conversation.id)
                assertTrue(
                    SupportUnreadStore.recordSupportMessage(
                        context,
                        conversation.id,
                        messageId
                    )
                )
                assertFalse(
                    SupportUnreadStore.recordSupportMessage(
                        context,
                        conversation.id,
                        messageId
                    )
                )
                assertTrue(SupportUnreadStore.hasUnread(context, conversation.id))
            }
            val unreadIds = unreadConversations.mapTo(mutableSetOf()) { it.id }
            assertEquals(
                listOf(unreadRecent, unreadMiddle, unreadOld, readRecent, readOld),
                orderContactConversations(conversations, unreadIds)
            )

            SupportUnreadStore.clearConversation(
                context,
                unreadMiddle.id,
                messageIds.getValue(unreadMiddle.id)
            )
            val remainingUnreadIds = unreadConversations
                .filter { SupportUnreadStore.hasUnread(context, it.id) }
                .mapTo(mutableSetOf()) { it.id }
            assertFalse(SupportUnreadStore.hasUnread(context, unreadMiddle.id))
            assertTrue(SupportUnreadStore.hasUnread(context, unreadOld.id))
            assertTrue(SupportUnreadStore.hasUnread(context, unreadRecent.id))
            assertEquals(
                listOf(unreadRecent, unreadOld, readRecent, unreadMiddle, readOld),
                orderContactConversations(conversations, remainingUnreadIds)
            )
        } finally {
            conversations.forEach { SupportUnreadStore.clearConversation(context, it.id) }
        }
    }

    private fun conversation(id: String, activityAt: String) = ContactConversation(
        id = id,
        userId = "user",
        email = "",
        phone = "",
        subject = id,
        status = "OPEN",
        createdAt = "2026-10-07T18:00:00Z",
        updatedAt = "2026-10-07T19:00:00Z",
        latestMessageCreatedAt = activityAt
    )
}
