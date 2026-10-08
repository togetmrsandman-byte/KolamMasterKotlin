package com.kolammaster.app.notifications

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kolammaster.app.ContactComposeTestActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AnnouncementForegroundAlertInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ContactComposeTestActivity>()

    @Test
    fun foregroundAnnouncementAlertCanBePersistedAndDismissed() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = ForegroundAnnouncementAlertStore
        val alertId = "announcement-${System.nanoTime()}"
        store.record(context, alertId)
        val alert = store.pending(context)

        assertEquals(alertId, alert?.announcementId)
        store.dismiss(context, requireNotNull(alert))
        assertNull(store.pending(context))
    }

    @Test
    fun openRequestsTheExistingAnnouncementNavigationPath() {
        var openedAnnouncementId: String? = null
        composeRule.setContent {
            AnnouncementForegroundAlertDialog(
                announcementId = "announcement-1",
                onOpen = { openedAnnouncementId = it },
                onLater = {}
            )
        }

        composeRule.onNodeWithText("A new announcement has been received").assertExists()
        composeRule.onNodeWithText("Open").performClick()

        composeRule.runOnIdle {
            assertEquals("announcement-1", openedAnnouncementId)
        }
    }

    @Test
    fun laterDismissesWithoutOpeningTheAnnouncement() {
        var opened = false
        var deferred = false
        composeRule.setContent {
            AnnouncementForegroundAlertDialog(
                announcementId = "announcement-2",
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
