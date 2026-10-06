package com.kolammaster.app

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SupportUnreadIndicatorInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ContactComposeTestActivity>()

    @Test
    fun navigationHeaderShowsUnreadSupportIndicator() {
        composeRule.setContent {
            AppNavigationHeader(
                onBack = {},
                onHome = {},
                onOpenDrawer = {},
                unreadSupport = true
            )
        }

        composeRule.onNodeWithContentDescription("Unread Support messages").assertExists()
    }

    @Test
    fun landingHeaderAndContactUsDrawerItemShowUnreadSupportIndicators() {
        composeRule.setContent {
            LandingPageFrame(
                onMenuAction = {},
                unreadSupport = true
            ) {}
        }

        composeRule.onNodeWithContentDescription("Open navigation menu").performClick()
        composeRule.onNodeWithText("Contact Us").assertExists()
        composeRule.onAllNodesWithContentDescription("Unread Support messages")
            .assertCountEquals(2)
    }
}
