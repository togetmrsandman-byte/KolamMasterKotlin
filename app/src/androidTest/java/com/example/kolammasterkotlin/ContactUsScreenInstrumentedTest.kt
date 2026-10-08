package com.kolammaster.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.runtime.mutableStateOf
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kolammaster.app.notifications.SupportUnreadStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContactUsScreenInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ContactComposeTestActivity>()

    @Test
    fun signedInContactScreenShowsConversationsInsteadOfSignInPrompt() {
        var selectedConversationId: String? = null
        composeRule.setContent {
            ContactUsDestination(
                onSignIn = {},
                onBack = {},
                signedIn = true,
                conversations = listOf(
                    ContactConversation(
                        id = "conversation-1",
                        userId = "user-uuid",
                        email = "user@example.com",
                        phone = "",
                        subject = "Help with my lesson",
                        status = "OPEN",
                        createdAt = "2026-10-01T00:00:00Z",
                        updatedAt = "2026-10-02T00:00:00Z",
                        latestMessageCreatedAt = "2026-10-02T00:00:00Z",
                        latestMessagePreview = "I need help with this lesson."
                    )
                ),
                isLoading = false,
                errorMessage = null,
                onRetry = {},
                onConversationSelected = { selectedConversationId = it }
            )
        }

        composeRule.onNodeWithText("Help with my lesson").assertExists()
        composeRule.onNodeWithText("I need help with this lesson.").assertExists()
        composeRule.onNodeWithText("OPEN · 2026-10-02T00:00:00Z").assertDoesNotExist()
        composeRule.onNodeWithText("Sign in to start a new conversation.")
            .assertDoesNotExist()
        composeRule.onNodeWithText("New Conversation").assertExists()
        composeRule.onNodeWithText("Help with my lesson").performClick()
        composeRule.runOnIdle { assertTrue(selectedConversationId == "conversation-1") }
    }

    @Test
    fun displaysOnlySubjectsAndShowsPerConversationDotsInUnreadActivityOrder() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val unreadOld = conversation("Unread old", "2026-10-07T18:00:00Z")
        val unreadMiddle = conversation("Unread middle", "2026-10-07T19:00:00Z")
        val unreadRecent = conversation("Unread recent", "2026-10-07T20:00:00Z")
        val readOld = conversation("Read old", "2026-10-07T17:00:00Z")
        val readRecent = conversation("Read recent", "2026-10-07T21:00:00Z")
        val unread = listOf(unreadOld, unreadMiddle, unreadRecent)
        val conversations = listOf(readOld, unreadOld, readRecent, unreadRecent, unreadMiddle)
        val messageIds = unread.associate { it.id to "message-${it.id}" }
        val unreadIds = mutableStateOf<Set<String>>(unread.mapTo(mutableSetOf()) { it.id })
        conversations.forEach { SupportUnreadStore.clearConversation(context, it.id) }

        try {
            unread.forEach {
                SupportUnreadStore.recordSupportMessage(
                    context,
                    it.id,
                    messageIds.getValue(it.id)
                )
            }
            composeRule.setContent {
                ContactUsDestination(
                    onSignIn = {},
                    onBack = {},
                    signedIn = true,
                    conversations = conversations,
                    isLoading = false,
                    errorMessage = null,
                    onRetry = {},
                    unreadConversationIds = unreadIds.value
                )
            }

            composeRule.onAllNodesWithContentDescription(
                "Unread Support messages for Unread old"
            ).assertCountEquals(1)
            composeRule.onAllNodesWithContentDescription(
                "Unread Support messages for Unread middle"
            ).assertCountEquals(1)
            composeRule.onAllNodesWithContentDescription(
                "Unread Support messages for Unread recent"
            ).assertCountEquals(1)
            composeRule.onAllNodesWithContentDescription(
                "Unread Support messages for Read old"
            ).assertCountEquals(0)
            composeRule.onNodeWithText("OPEN · 2026-10-07T19:00:00Z").assertDoesNotExist()

            val expectedOrder = listOf(
                "Unread recent",
                "Unread middle",
                "Unread old",
                "Read recent",
                "Read old"
            )
            val renderedOrder = expectedOrder.map { subject ->
                composeRule.onNodeWithText(subject).fetchSemanticsNode().boundsInRoot.top
            }
            assertEquals(renderedOrder.sorted(), renderedOrder)

            composeRule.runOnIdle {
                SupportUnreadStore.clearConversation(
                    context,
                    unreadMiddle.id,
                    messageIds.getValue(unreadMiddle.id)
                )
                unreadIds.value = SupportUnreadStore.unreadConversationIds(context)
            }
            composeRule.onAllNodesWithContentDescription(
                "Unread Support messages for Unread middle"
            ).assertCountEquals(0)
            composeRule.onAllNodesWithContentDescription(
                "Unread Support messages for Unread old"
            ).assertCountEquals(1)
            composeRule.onAllNodesWithContentDescription(
                "Unread Support messages for Unread recent"
            ).assertCountEquals(1)
            val orderAfterRead = listOf(
                "Unread recent",
                "Unread old",
                "Read recent",
                "Unread middle",
                "Read old"
            ).map { subject ->
                composeRule.onNodeWithText(subject).fetchSemanticsNode().boundsInRoot.top
            }
            assertEquals(orderAfterRead.sorted(), orderAfterRead)
        } finally {
            conversations.forEach { SupportUnreadStore.clearConversation(context, it.id) }
        }
    }

    @Test
    fun newlyPromotedConversationIsVisibleAfterListReorders() {
        val promoted = (0..9).map { index ->
            conversation(
                "Conversation $index",
                "2026-10-07T${index.toString().padStart(2, '0')}:00:00Z"
            )
        }
        val unreadIds = mutableStateOf<Set<String>>(emptySet())
        composeRule.setContent {
            ContactUsDestination(
                onSignIn = {},
                onBack = {},
                signedIn = true,
                conversations = promoted,
                isLoading = false,
                errorMessage = null,
                onRetry = {},
                unreadConversationIds = unreadIds.value
            )
        }

        composeRule.onNodeWithTag("contact-conversation-list")
            .performScrollToIndex(5)
        composeRule.runOnIdle {
            unreadIds.value = setOf(promoted.first().id)
        }

        composeRule.onNodeWithText("Conversation 0").assertIsDisplayed()
    }

    @Test
    fun guestContactScreenUsesExistingGoogleSignInCallback() {
        var signInRequested = false
        composeRule.setContent {
            ContactUsDestination(
                onSignIn = { signInRequested = true },
                onBack = {},
                signedIn = false,
                conversations = emptyList(),
                isLoading = false,
                errorMessage = null,
                onRetry = {}
            )
        }

        composeRule.onNodeWithText("Sign in to start a new conversation.").assertExists()
        composeRule.onNodeWithText("Sign in with Google").performClick()
        composeRule.runOnIdle { assertTrue(signInRequested) }
    }

    @Test
    fun loadingStateShowsProgressAndKeepsCreateAvailable() {
        composeRule.setContent {
            ContactUsDestination(
                onSignIn = {},
                onBack = {},
                signedIn = true,
                conversations = emptyList(),
                isLoading = true,
                errorMessage = null,
                onRetry = {}
            )
        }

        composeRule.onNodeWithText("Loading conversations...").assertExists()
        composeRule.onNodeWithText("New Conversation").assertExists()
    }

    @Test
    fun networkErrorShowsSharedMessageAndAllowsManualRetry() {
        var retryRequested = false
        composeRule.setContent {
            ContactUsDestination(
                onSignIn = {},
                onBack = {},
                signedIn = true,
                conversations = emptyList(),
                isLoading = false,
                errorMessage = NetworkErrors.DISPLAY_TEXT,
                onRetry = { retryRequested = true }
            )
        }

        composeRule.onNodeWithText(NetworkErrors.DISPLAY_TEXT).assertExists()
        composeRule.onNodeWithText("Retry").performClick()
        composeRule.runOnIdle { assertTrue(retryRequested) }
    }

    private fun conversation(id: String, activityAt: String) = ContactConversation(
        id = id,
        userId = "user-uuid",
        email = "",
        phone = "",
        subject = id,
        status = "OPEN",
        createdAt = "2026-10-07T18:00:00Z",
        updatedAt = "2026-10-07T19:00:00Z",
        latestMessageCreatedAt = activityAt
    )
}
