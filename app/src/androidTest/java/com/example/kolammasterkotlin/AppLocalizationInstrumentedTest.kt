package com.kolammaster.app

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppLocalizationInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ContactComposeTestActivity>()

    @Test
    fun changingSelectedLanguageReloadsComposeStringResourcesImmediately() {
        val language = mutableStateOf("Tamil")
        composeRule.setContent {
            LocalizedAppResources(language.value) {
                Text(
                    text = stringResource(R.string.continue_with_google),
                    modifier = Modifier.testTag("localized-label")
                )
            }
        }

        composeRule.onNodeWithTag("localized-label")
            .assertTextEquals("Google மூலம் தொடரவும்")

        composeRule.runOnIdle { language.value = "Hindi" }
        composeRule.onNodeWithTag("localized-label")
            .assertTextEquals("Google के साथ जारी रखें")

        composeRule.runOnIdle { language.value = "Chinese (Simplified)" }
        composeRule.onNodeWithTag("localized-label")
            .assertTextEquals("使用 Google 继续")
    }

    @Test
    fun localizedComposeContextRetainsActivityForActivityResultLaunchers() {
        var localizedContext: Context? = null
        composeRule.setContent {
            LocalizedAppResources("Tamil") {
                localizedContext = LocalContext.current
                rememberLauncherForActivityResult(
                    ActivityResultContracts.GetContent()
                ) { }
                Text(
                    text = stringResource(R.string.continue_with_google),
                    modifier = Modifier.testTag("localized-context-label")
                )
            }
        }

        composeRule.onNodeWithTag("localized-context-label")
            .assertTextEquals("Google மூலம் தொடரவும்")
        composeRule.runOnIdle {
            var context = requireNotNull(localizedContext)
            while (context !is ContactComposeTestActivity && context is ContextWrapper) {
                context = context.baseContext
            }
            assert(context === composeRule.activity) {
                "Localized context no longer wraps the Activity required by UI launchers."
            }
            assert(localizedContext?.resources?.getString(R.string.continue_with_google) ==
                "Google மூலம் தொடரவும்")
        }
    }
}
