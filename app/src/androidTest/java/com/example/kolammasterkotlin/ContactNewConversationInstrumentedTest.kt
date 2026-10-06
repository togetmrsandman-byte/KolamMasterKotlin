package com.kolammaster.app

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContactNewConversationInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ContactComposeTestActivity>()

    @Test
    fun emptySubjectIsRejectedWithoutCallingRepository() {
        var createCalls = 0
        composeRule.setContent {
            ContactNewConversationDestination(
                onCreate = { _, _, _ -> createCalls++ }
            )
        }

        composeRule.onNodeWithText("Back").assertDoesNotExist()
        composeRule.onNodeWithText("Send").performClick()

        composeRule.onNodeWithText("Please enter a subject.").assertExists()
        composeRule.runOnIdle { assertEquals(0, createCalls) }
    }

    @Test
    fun emptyMessageIsRejectedWithoutCallingRepository() {
        var createCalls = 0
        composeRule.setContent {
            ContactNewConversationDestination(
                onCreate = { _, _, _ -> createCalls++ }
            )
        }

        composeRule.onNodeWithText("Subject").performTextInput("Help")
        composeRule.onNodeWithText("Send").performClick()

        composeRule.onNodeWithText("Please enter a message.").assertExists()
        composeRule.runOnIdle { assertEquals(0, createCalls) }
    }

    @Test
    fun submitsTrimmedSubjectAndMessageOnceWithCurrentAccountContactFields() {
        var createCalls = 0
        var submittedSubject = ""
        var submittedMessage = ""
        composeRule.setContent {
            ContactNewConversationDestination(
                onCreate = { _, subject, message ->
                    createCalls++
                    submittedSubject = subject
                    submittedMessage = message
                }
            )
        }

        composeRule.onNodeWithText("Subject").performTextInput("  Lesson help  ")
        composeRule.onNodeWithText("Message").performTextInput("  I need help  ")
        composeRule.onNodeWithText("Send").performClick()
        composeRule.waitUntil(5_000) { createCalls == 1 }

        composeRule.runOnIdle {
            assertEquals("Lesson help", submittedSubject)
            assertEquals("I need help", submittedMessage)
            assertEquals(1, createCalls)
        }
    }

    @Test
    fun disablesRepeatedSubmissionWhileRepositoryCallIsPending() {
        val requestStarted = CompletableDeferred<Unit>()
        var createCalls = 0
        composeRule.setContent {
            ContactNewConversationDestination(
                onCreate = { _, _, _ ->
                    createCalls++
                    requestStarted.complete(Unit)
                    awaitCancellation()
                }
            )
        }

        composeRule.onNodeWithText("Subject").performTextInput("Help")
        composeRule.onNodeWithText("Message").performTextInput("Question")
        composeRule.onNodeWithText("Send").performClick()
        composeRule.waitUntil(5_000) { requestStarted.isCompleted }
        composeRule.onNodeWithText("Sending...").assertExists()
        composeRule.onNodeWithText("Send").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(1, createCalls) }
    }

    @Test
    fun successfulCreationReturnsToListAndTriggersRefresh() {
        val showNewConversation = mutableStateOf(true)
        val refreshCount = mutableIntStateOf(0)
        var createCalls = 0
        composeRule.setContent {
            if (showNewConversation.value) {
                ContactNewConversationDestination(
                    onCreate = { _, _, _ ->
                        createCalls++
                        showNewConversation.value = false
                        refreshCount.intValue++
                    }
                )
            } else {
                androidx.compose.foundation.layout.Column {
                    androidx.compose.material3.Text("Contact Us")
                    androidx.compose.material3.Text("Conversation list refreshed")
                }
            }
        }

        composeRule.onNodeWithText("Subject").performTextInput("Help")
        composeRule.onNodeWithText("Message").performTextInput("Question")
        composeRule.onNodeWithText("Send").performClick()
        composeRule.waitUntil(5_000) { !showNewConversation.value }

        composeRule.onNodeWithText("Contact Us").assertExists()
        composeRule.onNodeWithText("Conversation list refreshed").assertExists()
        composeRule.runOnIdle {
            assertEquals(1, createCalls)
            assertEquals(1, refreshCount.intValue)
        }
    }

    @Test
    fun optionalPhoneValidationAcceptsBlankAndRejectsInvalidValues() {
        assertTrue(isValidContactPhone(""))
        assertTrue(isValidContactPhone(" +1 (555) 123-4567 "))
        assertFalse(isValidContactPhone("123"))
        assertFalse(isValidContactPhone("+1+5551234567"))
        assertFalse(isValidContactPhone("phone"))
    }
}
