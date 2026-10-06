package com.kolammaster.app.notifications

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kolammaster.app.ContactComposeTestActivity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SupportForegroundAlertInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ContactComposeTestActivity>()

    @Test
    fun openButtonRequestsOpeningTheNotifiedConversation() {
        var opened: SupportUnreadStore.ForegroundAlert? = null
        val alert = SupportUnreadStore.ForegroundAlert("conversation-1", "message-1")
        var deferred = false
        composeRule.setContent {
            SupportForegroundAlertDialog(
                alert = alert,
                onOpen = { opened = alert },
                onLater = { deferred = true }
            )
        }

        composeRule.onNodeWithText("You have a new message from Support").assertExists()
        composeRule.onNodeWithText("Open").performClick()

        composeRule.runOnIdle {
            assertEquals(alert, opened)
            assertEquals(false, deferred)
        }
    }

    @Test
    fun laterButtonDismissesWithoutOpeningConversation() {
        var opened = false
        var deferred = false
        composeRule.setContent {
            SupportForegroundAlertDialog(
                alert = SupportUnreadStore.ForegroundAlert("conversation-2", "message-2"),
                onOpen = { opened = true },
                onLater = { deferred = true }
            )
        }

        composeRule.onNodeWithText("Later").performClick()

        composeRule.runOnIdle {
            assertEquals(false, opened)
            assertEquals(true, deferred)
        }
    }
}
