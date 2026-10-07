package com.kolammaster.app

import android.net.Uri
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
    fun emptyPhoneIsRejectedWithoutCallingRepository() {
        var createCalls = 0
        composeRule.setContent {
            ContactNewConversationDestination(
                onCreate = { _, _, _ -> createCalls++ }
            )
        }

        composeRule.onNodeWithText("Subject").performTextInput("Help")
        composeRule.onNodeWithText("Message").performTextInput("Question")
        composeRule.onNodeWithText("Send").performClick()

        composeRule.onNodeWithText(
            "Please enter a valid phone number using digits only."
        ).assertExists()
        composeRule.runOnIdle { assertEquals(0, createCalls) }
    }

    @Test
    fun submitsTrimmedSubjectAndMessageOnceWithCurrentAccountContactFields() {
        var createCalls = 0
        var submittedPhone = ""
        var submittedSubject = ""
        var submittedMessage = ""
        composeRule.setContent {
            ContactNewConversationDestination(
                onCreate = { phone, subject, message ->
                    createCalls++
                    submittedPhone = phone
                    submittedSubject = subject
                    submittedMessage = message
                }
            )
        }

        composeRule.onNodeWithText("Subject").performTextInput("  Lesson help  ")
        composeRule.onNodeWithText("Message").performTextInput("  I need help  ")
        composeRule.onNodeWithText("Phone number").performTextInput("9876543210")
        composeRule.onNodeWithText("\uD83C\uDDEE\uD83C\uDDF3").assertExists()
        composeRule.onNodeWithText("+91").assertExists()
        composeRule.onNodeWithText("Send").performClick()
        composeRule.waitUntil(5_000) { createCalls == 1 }

        composeRule.runOnIdle {
            assertEquals("+919876543210", submittedPhone)
            assertEquals("Lesson help", submittedSubject)
            assertEquals("I need help", submittedMessage)
            assertEquals(1, createCalls)
        }
    }

    @Test
    fun selectingCountryUsesItsCallingCodeWhenSubmitting() {
        var submittedPhone = ""
        composeRule.setContent {
            ContactNewConversationDestination(
                onCreate = { phone, _, _ -> submittedPhone = phone }
            )
        }

        composeRule.onNodeWithContentDescription("Country: India, +91").performClick()
        composeRule.onNodeWithText("Search countries").performTextInput("Germany")
        composeRule.onAllNodesWithText("Germany", substring = true).assertCountEquals(2)
        composeRule.onNodeWithText("\uD83C\uDDE9\uD83C\uDDEA", substring = true).assertExists()
        composeRule.onNodeWithText("+49").performClick()
        composeRule.onNodeWithText("Subject").performTextInput("Help")
        composeRule.onNodeWithText("Message").performTextInput("Question")
        composeRule.onNodeWithText("Phone number").performTextInput("123456789")
        composeRule.onNodeWithText("Send").performClick()
        composeRule.waitUntil(5_000) { submittedPhone == "+49123456789" }
    }

    @Test
    fun attachmentPreviewCanBeShownAndRemoved() {
        val attachment = mutableStateOf<ContactNewConversationImage?>(null)
        var pickerRequested = false
        composeRule.setContent {
            ContactNewConversationAttachment(
                attachment = attachment.value,
                enabled = true,
                onPickImage = {
                    pickerRequested = true
                    attachment.value = ContactNewConversationImage(
                        uri = Uri.parse("content://test/contact-image"),
                        mimeType = "image/png",
                        sizeBytes = 2048,
                        preview = ImageBitmap(2, 2)
                    )
                },
                onRemoveImage = { attachment.value = null }
            )
        }

        composeRule.onNodeWithText("Attach image").performClick()
        composeRule.onNodeWithContentDescription("Selected image preview").assertExists()
        composeRule.onNodeWithText("Image attached (2 KB)").assertExists()
        composeRule.runOnIdle { assertTrue(pickerRequested) }

        composeRule.onNodeWithText("Remove image").performClick()
        composeRule.onNodeWithContentDescription("Selected image preview").assertDoesNotExist()
    }

    @Test
    fun attachImageOpensAndroidSystemImagePicker() {
        composeRule.setContent {
            ContactNewConversationDestination(onCreate = { _, _, _ -> })
        }

        composeRule.onNodeWithText("Attach image").performClick()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val uiAutomation = instrumentation.uiAutomation
        composeRule.waitUntil(10_000) {
            val packageName = uiAutomation.rootInActiveWindow?.packageName?.toString()
            packageName != null && packageName != composeRule.activity.packageName
        }

        uiAutomation.executeShellCommand("input keyevent 4").close()
        composeRule.waitUntil(10_000) {
            uiAutomation.rootInActiveWindow?.packageName?.toString() ==
                composeRule.activity.packageName
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
        composeRule.onNodeWithText("Phone number").performTextInput("9876543210")
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
        composeRule.onNodeWithText("Phone number").performTextInput("9876543210")
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
