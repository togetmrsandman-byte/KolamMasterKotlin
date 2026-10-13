package com.kolammaster.app

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
        val baseContext = composeRule.activity

        supportedLanguageIdentifiers.forEach { language ->
            val localizedContext = baseContext.withAppLanguage(language)
            val expectedTexts = listOf(
                R.string.congratulations,
                R.string.your_kolam_is_complete,
                R.string.share_kolam_community,
                R.string.continue_finished_kolam,
                R.string.proceed,
                R.string.cancel
            ).map(localizedContext::getString)

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
