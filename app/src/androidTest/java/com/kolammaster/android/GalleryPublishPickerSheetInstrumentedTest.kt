package com.kolammaster.android

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GalleryPublishPickerSheetInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ContactComposeTestActivity>()

    @Test
    fun completionPopupUsesResourcesForEverySelectedLanguage() {
        supportedLanguageIdentifiers.forEach { language ->
            val expectedTexts = listOf(
                "congratulations",
                "your_kolam_is_complete",
                "share_kolam_community",
                "continue_finished_kolam",
                "proceed",
                "cancel"
            ).map { key -> formatAppString(language, key, emptyArray()) }

            composeRule.setContent {
                GalleryPublishPickerSheet(
                    languageIdentifier = language,
                    onDismiss = {},
                    onProceed = {}
                )
            }

            expectedTexts.forEach { text ->
                composeRule.onNodeWithText(text).assertExists()
            }
        }
    }
}
