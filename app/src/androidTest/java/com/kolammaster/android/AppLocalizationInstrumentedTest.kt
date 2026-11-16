package com.kolammaster.android

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kolammaster.android.ui.theme.KolamMasterKotlinTheme
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
                    text = currentAppString("continue_with_google"),
                    modifier = Modifier.testTag("localized-label")
                )
                Text(
                    text = LocalConfiguration.current.locales[0].toLanguageTag(),
                    modifier = Modifier.testTag("localized-locale")
                )
                Text(
                    text = currentAppString("home"),
                    modifier = Modifier.testTag("localized-resource-label")
                )
                Text(
                    text = currentAppString("learn"),
                    modifier = Modifier.testTag("localized-context-resource-label")
                )
            }
        }

        composeRule.onNodeWithTag("localized-label")
            .assertTextEquals("Google மூலம் தொடரவும்")
        composeRule.onNodeWithTag("localized-locale").assertTextEquals("ta")
        composeRule.onNodeWithTag("localized-resource-label").assertTextEquals("முகப்பு")
        composeRule.onNodeWithTag("localized-context-resource-label").assertTextEquals("கற்க")

        composeRule.runOnIdle { language.value = "Hindi" }
        composeRule.onNodeWithTag("localized-label")
            .assertTextEquals("Google के साथ जारी रखें")
        composeRule.onNodeWithTag("localized-locale").assertTextEquals("hi")
        composeRule.onNodeWithTag("localized-resource-label").assertTextEquals("होम")
        composeRule.onNodeWithTag("localized-context-resource-label").assertTextEquals("सीखें")

        composeRule.runOnIdle { language.value = "Chinese (Simplified)" }
        composeRule.onNodeWithTag("localized-label")
            .assertTextEquals("使用 Google 继续")
        composeRule.onNodeWithTag("localized-locale").assertTextEquals("zh-CN")
        composeRule.onNodeWithTag("localized-resource-label").assertTextEquals("首页")
        composeRule.onNodeWithTag("localized-context-resource-label").assertTextEquals("学习")
    }

    @Test
    fun landingPageUsesTheSelectedLanguage() {
        val language = mutableStateOf("Tamil")
        composeRule.setContent {
            KolamMasterKotlinTheme {
                LocalizedAppResources(language.value) {
                    LandingPageFrame(onMenuAction = {}) {
                        Column {
                            LandingDestination.entries.forEach { destination ->
                                Text(currentAppString(destination.titleKey))
                            }
                        }
                    }
                }
            }
        }

        composeRule.onNodeWithText("கற்க").assertExists()
        composeRule.onNodeWithText("எனது கோப்புறைகள்").assertExists()
        composeRule.onNodeWithText("சமூகம்").assertExists()

        composeRule.runOnIdle { language.value = "Hindi" }
        composeRule.onNodeWithText("सीखें").assertExists()
        composeRule.onNodeWithText("मेरे फ़ोल्डर").assertExists()
        composeRule.onNodeWithText("समुदाय").assertExists()
    }

    @Test
    fun drawerItemsUseTheSelectedLanguage() {
        composeRule.setContent {
            KolamMasterKotlinTheme {
                LocalizedAppResources("Tamil") {
                    DrawerOverlay(
                        visible = true,
                        settingsExpanded = false,
                        onSettingsExpandedChange = {},
                        onBack = {},
                        onSelect = {}
                    )
                }
            }
        }

        composeRule.onNodeWithText("முகப்பு").assertExists()
        composeRule.onNodeWithText("சுயவிவரம்").assertExists()
        composeRule.onNodeWithText("மொழி (Language)").assertExists()
        composeRule.onNodeWithText("அமைப்புகள்").assertExists()
    }

    @Test
    fun profileButtonsUseTheSelectedLanguage() {
        composeRule.setContent {
            KolamMasterKotlinTheme {
                LocalizedAppResources("Tamil") {
                    ProfileDestination(onSignIn = {}, onBack = {})
                }
            }
        }

        composeRule.onNodeWithText("Google மூலம் உள்நுழைக").assertExists()
        composeRule.onNodeWithText("பின்").assertExists()
    }

    @Test
    fun dialogsUseTheSelectedLanguage() {
        val language = mutableStateOf("Tamil")
        composeRule.setContent {
            KolamMasterKotlinTheme {
                LocalizedAppResources(language.value) {
                    NetworkRetryDialog(onRetry = {}, onClose = {})
                }
            }
        }

        composeRule.onNodeWithText("இணைய இணைப்பு இல்லை").assertExists()
        composeRule.onNodeWithText("மீண்டும் முயற்சி").assertExists()
        composeRule.onNodeWithText("மூடு").assertExists()

        composeRule.runOnIdle { language.value = "Hindi" }
        composeRule.onNodeWithText("इंटरनेट नहीं है").assertExists()
        composeRule.onNodeWithText("फिर कोशिश करें").assertExists()
        composeRule.onNodeWithText("बंद करें").assertExists()
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
                    text = currentAppString("continue_with_google"),
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
            assert(
                formatAppString("Tamil", "continue_with_google", emptyArray()) ==
                    "Google மூலம் தொடரவும்"
            )
        }
    }
}
