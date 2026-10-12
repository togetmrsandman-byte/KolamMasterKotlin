package com.kolammaster.app

import android.content.res.Configuration
import android.content.Context
import android.view.ContextThemeWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

internal const val DEFAULT_LANGUAGE_IDENTIFIER = "English"
private const val APP_PREFERENCES_NAME = "kolam_master_preferences"
private const val APP_LANGUAGE_KEY = "language"

internal val supportedLanguageIdentifiers = listOf(
    "English",
    "Tamil",
    "Hindi",
    "Kannada",
    "Telugu",
    "Malayalam",
    "Thai",
    "Chinese (Simplified)",
    "Vietnamese",
    "Malay",
    "Japanese",
    "Korean"
)

internal fun localeTagForLanguage(languageIdentifier: String): String =
    when (languageIdentifier) {
        "Tamil" -> "ta"
        "Hindi" -> "hi"
        "Kannada" -> "kn"
        "Telugu" -> "te"
        "Malayalam" -> "ml"
        "Thai" -> "th"
        "Chinese (Simplified)" -> "zh-CN"
        "Vietnamese" -> "vi"
        "Malay" -> "ms"
        "Japanese" -> "ja"
        "Korean" -> "ko"
        DEFAULT_LANGUAGE_IDENTIFIER -> "en"
        else -> "en"
    }

internal fun Context.withSelectedAppLanguage(): Context {
    val language = getSharedPreferences(APP_PREFERENCES_NAME, Context.MODE_PRIVATE)
        .getString(APP_LANGUAGE_KEY, DEFAULT_LANGUAGE_IDENTIFIER)
        ?: DEFAULT_LANGUAGE_IDENTIFIER
    return withAppLanguage(language)
}

internal fun Context.withAppLanguage(languageIdentifier: String): Context {
    val configuration = Configuration(resources.configuration)
    val locale = Locale.forLanguageTag(localeTagForLanguage(languageIdentifier))
    configuration.setLocale(locale)
    configuration.setLayoutDirection(locale)
    return ContextThemeWrapper(this, 0).apply {
        applyOverrideConfiguration(configuration)
    }
}

@Composable
internal fun LocalizedAppResources(
    languageIdentifier: String,
    content: @Composable () -> Unit
) {
    val baseConfiguration = LocalConfiguration.current
    val configuration = Configuration(baseConfiguration)
    val locale = Locale.forLanguageTag(localeTagForLanguage(languageIdentifier))
    configuration.setLocale(locale)
    configuration.setLayoutDirection(locale)
    val baseContext = LocalContext.current
    val localizedContext = remember(baseContext, languageIdentifier, baseConfiguration) {
        baseContext.withAppLanguage(languageIdentifier)
    }
    CompositionLocalProvider(
        LocalConfiguration provides configuration,
        LocalContext provides localizedContext,
        content = content
    )
}
