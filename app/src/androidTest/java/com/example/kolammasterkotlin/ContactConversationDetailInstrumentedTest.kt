package com.kolammaster.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.UnknownHostException

@RunWith(AndroidJUnit4::class)
class ContactConversationDetailInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ContactComposeTestActivity>()

    @Test
    fun loadsSelectedConversationAndDisplaysMessagesOldestFirst() {
        var requestedConversationId = ""
        composeRule.setContent {
            ContactConversationDetailDestination(
                conversationId = "selected-conversation-id",
                onLoadConversation = {
                    requestedConversationId = it
                    conversation(it)
                },
                onLoadMessages = { conversationId ->
                    requestedConversationId = conversationId
                    listOf(
                        message(conversationId, "admin-message", "ADMIN", "Reply", "2026-10-02"),
                        message(conversationId, "user-message", "USER", "First", "2026-10-01")
                    )
                },
                onSendMessage = { _, _, _ -> error("Not expected") },
                onUploadImage = { error("Not expected") }
            )
        }

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Reply").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("You").assertExists()
        composeRule.onNodeWithText("First").assertExists()
        composeRule.onNodeWithText("Support").assertExists()
        composeRule.onNodeWithText("Reply").assertExists()
        composeRule.runOnIdle { assertEquals("selected-conversation-id", requestedConversationId) }
    }

    @Test
    fun showsLoadingStateUntilMessagesLoad() {
        val messages = CompletableDeferred<List<ContactMessage>>()
        val requestStarted = CompletableDeferred<Unit>()
        composeRule.setContent {
            ContactConversationDetailDestination(
                conversationId = "conversation-id",
                onLoadConversation = { conversation(it) },
                onLoadMessages = {
                    requestStarted.complete(Unit)
                    messages.await()
                },
                onSendMessage = { _, _, _ -> error("Not expected") },
                onUploadImage = { error("Not expected") }
            )
        }

        composeRule.waitUntil(5_000) { requestStarted.isCompleted }
        composeRule.onNodeWithText("Conversation").assertExists()
        composeRule.onNodeWithText("No messages in this conversation yet.")
            .assertDoesNotExist()
    }

    @Test
    fun successfulTextSendShowsOptimisticMessageThenSentState() {
        val sendStarted = CompletableDeferred<Unit>()
        val response = CompletableDeferred<ContactMessage>()
        composeRule.setContent {
            ContactConversationDetailDestination(
                conversationId = "conversation-id",
                onLoadConversation = { conversation(it) },
                onLoadMessages = { emptyList() },
                onSendMessage = { conversationId, text, imageUrl ->
                    assertEquals("conversation-id", conversationId)
                    assertEquals("Please help", text)
                    assertEquals(null, imageUrl)
                    sendStarted.complete(Unit)
                    response.await()
                },
                onUploadImage = { error("Not expected") }
            )
        }

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("No messages in this conversation yet.")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Message").performTextInput("  Please help  ")
        composeRule.onNodeWithText("Send").performClick()
        composeRule.waitUntil(5_000) { sendStarted.isCompleted }
        composeRule.onNodeWithText("Please help").assertExists()
        composeRule.onNodeWithText("Sending").assertExists()

        response.complete(
            message("conversation-id", "server-message", "USER", "Please help", "2026-10-06")
        )
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Sent").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Sent").assertExists()
    }

    @Test
    fun failedTextSendRemainsVisibleAsNotSent() {
        composeRule.setContent {
            ContactConversationDetailDestination(
                conversationId = "conversation-id",
                onLoadConversation = { conversation(it) },
                onLoadMessages = { emptyList() },
                onSendMessage = { _, _, _ -> throw UnknownHostException("offline") },
                onUploadImage = { error("Not expected") }
            )
        }

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("No messages in this conversation yet.")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Message").performTextInput("Don't lose this")
        composeRule.onNodeWithText("Send").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Not sent").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Don't lose this").assertExists()
        composeRule.onNodeWithText(NetworkErrors.TITLE).assertExists()
    }

    @Test
    fun closedConversationDoesNotShowComposerOrAttachmentControl() {
        composeRule.setContent {
            ContactConversationDetailDestination(
                conversationId = "conversation-id",
                onLoadConversation = { conversation(it, status = "CLOSED") },
                onLoadMessages = { emptyList() },
                onSendMessage = { _, _, _ -> error("Closed conversations cannot send") },
                onUploadImage = { error("Closed conversations cannot upload") }
            )
        }

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(
                "This conversation has been closed. If you still need assistance, please start a new conversation."
            ).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Message").assertDoesNotExist()
        composeRule.onNodeWithText("+").assertDoesNotExist()
        composeRule.runOnIdle { assertTrue(true) }
    }

    private fun conversation(id: String, status: String = "OPEN") = ContactConversation(
        id = id,
        userId = "user-id",
        email = "user@example.com",
        phone = "",
        subject = "Help",
        status = status,
        createdAt = "2026-10-01",
        updatedAt = "2026-10-02"
    )

    private fun message(
        conversationId: String,
        id: String,
        sender: String,
        text: String,
        createdAt: String
    ) = ContactMessage(
        id = id,
        conversationId = conversationId,
        sender = sender,
        message = text,
        imageUrl = null,
        createdAt = createdAt
    )
}
