package com.kolammaster.app

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kolammaster.app.notifications.SupportUnreadStore
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicInteger

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
        composeRule.onNodeWithTag("contact-conversation-composer").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals("selected-conversation-id", requestedConversationId) }
    }

    @Test
    fun freshCachedHistoryIsReusedUnlessAnExternalRefreshIsRequested() {
        val userId = "cache-test-user-${java.util.UUID.randomUUID()}"
        val conversationId = "cache-test-conversation-${java.util.UUID.randomUUID()}"
        val localCache = ContactLocalCache(composeRule.activity)
        val cachedConversation = conversation(conversationId).copy(userId = userId)
        val cachedMessage = message(
            conversationId,
            "cached-message",
            "ADMIN",
            "Cached reply",
            "2026-10-13"
        )
        localCache.saveConversation(userId, cachedConversation)
        localCache.saveMessages(userId, conversationId, listOf(cachedMessage))

        var conversationLoads = 0
        var messageLoads = 0
        val externalRefreshKey = mutableIntStateOf(0)
        composeRule.setContent {
            ContactConversationDetailDestination(
                conversationId = conversationId,
                accountId = userId,
                localCache = localCache,
                externalRefreshKey = externalRefreshKey.intValue,
                onLoadConversation = {
                    conversationLoads++
                    cachedConversation
                },
                onLoadMessages = {
                    messageLoads++
                    listOf(cachedMessage)
                },
                onSendMessage = { _, _, _ -> error("Not expected") },
                onUploadImage = { error("Not expected") }
            )
        }

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Cached reply").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.runOnIdle {
            assertEquals(0, conversationLoads)
            assertEquals(0, messageLoads)
            externalRefreshKey.intValue++
        }
        composeRule.waitUntil(5_000) {
            conversationLoads == 1 && messageLoads == 1
        }
    }

    @Test
    fun deferredPushRefreshesOnlyItsConversationDespiteFreshHistoryCaches() {
        val context = composeRule.activity
        val userId = "deferred-push-user-${java.util.UUID.randomUUID()}"
        val conversationAId = "deferred-push-a-${java.util.UUID.randomUUID()}"
        val conversationBId = "deferred-push-b-${java.util.UUID.randomUUID()}"
        val localCache = ContactLocalCache(context)
        val conversationA = conversation(conversationAId).copy(userId = userId)
        val conversationB = conversation(conversationBId).copy(userId = userId)
        val messageA = message(
            conversationAId,
            "deferred-push-message-a",
            "ADMIN",
            "Cached reply A",
            "2026-10-13"
        )
        val messageB = message(
            conversationBId,
            "deferred-push-message-b",
            "ADMIN",
            "Cached reply B",
            "2026-10-13"
        )
        localCache.saveConversation(userId, conversationA)
        localCache.saveMessages(userId, conversationAId, listOf(messageA))
        localCache.saveConversation(userId, conversationB)
        localCache.saveMessages(userId, conversationBId, listOf(messageB))
        SupportUnreadStore.notifyConversationRefresh(context, conversationAId)

        val conversationLoadsA = AtomicInteger()
        val messageLoadsA = AtomicInteger()
        val conversationLoadsB = AtomicInteger()
        val messageLoadsB = AtomicInteger()
        composeRule.setContent {
            val selectedConversationId = remember { mutableStateOf<String?>(null) }
            val refreshKeyA = remember { mutableIntStateOf(0) }
            val refreshKeyB = remember { mutableIntStateOf(0) }
            Column(Modifier.fillMaxSize()) {
                Button(onClick = {
                    if (SupportUnreadStore.consumeConversationRefreshSignal(
                            context,
                            conversationAId
                        )
                    ) {
                        refreshKeyA.intValue++
                    }
                    selectedConversationId.value = conversationAId
                }) { Text("Open conversation A") }
                Button(onClick = {
                    if (SupportUnreadStore.consumeConversationRefreshSignal(
                            context,
                            conversationBId
                        )
                    ) {
                        refreshKeyB.intValue++
                    }
                    selectedConversationId.value = conversationBId
                }) { Text("Open conversation B") }

                when (selectedConversationId.value) {
                    conversationAId -> ContactConversationDetailDestination(
                        conversationId = conversationAId,
                        accountId = userId,
                        localCache = localCache,
                        externalRefreshKey = refreshKeyA.intValue,
                        onLoadConversation = {
                            conversationLoadsA.incrementAndGet()
                            conversationA
                        },
                        onLoadMessages = { _: String ->
                            messageLoadsA.incrementAndGet()
                            listOf(messageA)
                        },
                        onSendMessage = { _, _, _ -> error("Not expected") },
                        onUploadImage = { error("Not expected") }
                    )
                    conversationBId -> ContactConversationDetailDestination(
                        conversationId = conversationBId,
                        accountId = userId,
                        localCache = localCache,
                        externalRefreshKey = refreshKeyB.intValue,
                        onLoadConversation = {
                            conversationLoadsB.incrementAndGet()
                            conversationB
                        },
                        onLoadMessages = { _: String ->
                            messageLoadsB.incrementAndGet()
                            listOf(messageB)
                        },
                        onSendMessage = { _, _, _ -> error("Not expected") },
                        onUploadImage = { error("Not expected") }
                    )
                    else -> Unit
                }
            }
        }

        composeRule.onNodeWithText("Open conversation A").performClick()
        composeRule.waitUntil(5_000) {
            messageLoadsA.get() == 1 &&
                composeRule.onAllNodesWithText("Cached reply A").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.runOnIdle {
            assertEquals(1, conversationLoadsA.get())
            assertEquals(1, messageLoadsA.get())
            assertEquals(0, conversationLoadsB.get())
            assertEquals(0, messageLoadsB.get())
            assertFalse(
                SupportUnreadStore.consumeConversationRefreshSignal(context, conversationAId)
            )
        }

        composeRule.onNodeWithText("Open conversation B").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Cached reply B").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.runOnIdle {
            assertEquals(0, conversationLoadsB.get())
            assertEquals(0, messageLoadsB.get())
        }
    }

    @Test
    fun composerStaysVisibleWhileScrollingConversationMessages() {
        composeRule.setContent {
            ContactConversationDetailDestination(
                conversationId = "conversation-id",
                onLoadConversation = { conversation(it) },
                onLoadMessages = { conversationId ->
                    (0 until 40).map { index ->
                        message(
                            conversationId,
                            "message-$index",
                            if (index % 2 == 0) "USER" else "ADMIN",
                            "Message $index",
                            "2026-10-13T00:${index.toString().padStart(2, '0')}:00Z"
                        )
                    }
                },
                onSendMessage = { _, _, _ -> error("Not expected") },
                onUploadImage = { error("Not expected") }
            )
        }

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Message 39").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("contact-conversation-composer").assertIsDisplayed()
        composeRule.onNodeWithTag("contact-conversation-messages").performScrollToIndex(0)
        composeRule.onNodeWithTag("contact-conversation-composer").assertIsDisplayed()
        composeRule.onNodeWithTag("contact-conversation-messages").performScrollToIndex(39)
        composeRule.onNodeWithTag("contact-conversation-composer").assertIsDisplayed()
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
        composeRule.onNodeWithTag("contact-conversation-composer").assertIsDisplayed()
        composeRule.onNodeWithText("No messages in this conversation yet.")
            .assertDoesNotExist()
    }

    @Test
    fun composerRemainsInteractiveWhileConversationDetailsAreLoading() {
        val conversationRequest = CompletableDeferred<ContactConversation>()
        val sendStarted = CompletableDeferred<Unit>()
        composeRule.setContent {
            ContactConversationDetailDestination(
                conversationId = "conversation-id",
                onLoadConversation = { conversationRequest.await() },
                onLoadMessages = { emptyList() },
                onSendMessage = { conversationId, text, imageUrl ->
                    assertEquals("conversation-id", conversationId)
                    assertEquals("Message while loading", text)
                    assertEquals(null, imageUrl)
                    sendStarted.complete(Unit)
                    message(conversationId, "sent-message", "USER", text, "2026-10-13")
                },
                onUploadImage = { error("Not expected") }
            )
        }

        composeRule.onNodeWithContentDescription("Message input")
            .performTextInput("Message while loading")
        composeRule.onNodeWithContentDescription("Send message").performClick()
        composeRule.waitUntil(5_000) { sendStarted.isCompleted }
        composeRule.onNodeWithText("Message while loading").assertExists()
        composeRule.onNodeWithTag("contact-conversation-composer").assertIsDisplayed()
        conversationRequest.cancel()
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
        composeRule.onNodeWithContentDescription("Message input")
            .performTextInput("  Please help  ")
        composeRule.onNodeWithContentDescription("Send message").assertExists()
        composeRule.onNodeWithText("+").assertExists()
        composeRule.onNodeWithContentDescription("Send message").performClick()
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
        composeRule.onNodeWithContentDescription("Message input")
            .performTextInput("Don't lose this")
        composeRule.onNodeWithContentDescription("Send message").performClick()
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
        composeRule.onNodeWithText("Type a message...").assertDoesNotExist()
        composeRule.onNodeWithText("+").assertDoesNotExist()
        composeRule.runOnIdle { assertTrue(true) }
    }

    @Test
    fun integratedAttachmentButtonOpensExistingAttachmentOptions() {
        composeRule.setContent {
            ContactConversationDetailDestination(
                conversationId = "conversation-id",
                onLoadConversation = { conversation(it) },
                onLoadMessages = { emptyList() },
                onSendMessage = { _, _, _ -> error("Not expected") },
                onUploadImage = { error("Not expected") }
            )
        }

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("No messages in this conversation yet.")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("+").performClick()
        composeRule.onNodeWithText("Add attachment").assertExists()
        val camera = composeRule.onNodeWithText("Camera").fetchSemanticsNode().boundsInRoot
        val gallery = composeRule.onNodeWithText("Photo Gallery").fetchSemanticsNode().boundsInRoot
        val cancel = composeRule.onNodeWithText("Cancel").fetchSemanticsNode().boundsInRoot
        assertTrue(camera.top < gallery.top)
        assertTrue(gallery.top < cancel.top)
    }

    @Test
    fun contactImageShowsLoadingThenDisplaysLoadedImage() {
        val image = CompletableDeferred<androidx.compose.ui.graphics.ImageBitmap?>()
        val loadStarted = CompletableDeferred<Unit>()
        composeRule.setContent {
            ContactMessageImage(
                url = "test-image",
                onClick = {},
                loadImage = {
                    loadStarted.complete(Unit)
                    image.await()
                }
            )
        }

        composeRule.waitUntil(5_000) { loadStarted.isCompleted }
        composeRule.onNodeWithText("Image loading…").assertExists()

        image.complete(Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).asImageBitmap())
        composeRule.onNodeWithContentDescription("Contact conversation image").assertExists()
        composeRule.onNodeWithText("Image loading…").assertDoesNotExist()
        composeRule.onNodeWithText("Image not available").assertDoesNotExist()
    }

    @Test
    fun contactImageShowsUnavailableOnlyAfterLoadFails() {
        val image = CompletableDeferred<androidx.compose.ui.graphics.ImageBitmap?>()
        val loadStarted = CompletableDeferred<Unit>()
        composeRule.setContent {
            ContactMessageImage(
                url = "unavailable-image",
                onClick = {},
                loadImage = {
                    loadStarted.complete(Unit)
                    image.await()
                }
            )
        }

        composeRule.waitUntil(5_000) { loadStarted.isCompleted }
        composeRule.onNodeWithText("Image loading…").assertExists()
        image.complete(null)
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Image not available")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Image not available").assertExists()
        composeRule.onNodeWithText("Image loading…").assertDoesNotExist()
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
