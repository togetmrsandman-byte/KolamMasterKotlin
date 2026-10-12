package com.kolammaster.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val MidnightColorScheme = darkColorScheme(
    primary = KolamButtonYellow,
    onPrimary = KolamButtonText,
    primaryContainer = KolamNavy,
    onPrimaryContainer = KolamWhite,
    secondary = KolamSolarAmber,
    onSecondary = KolamMidnight,
    secondaryContainer = KolamElevated,
    onSecondaryContainer = KolamWhite,
    tertiary = KolamSilver,
    onTertiary = KolamMidnight,
    background = KolamMidnight,
    onBackground = KolamWhite,
    surface = KolamNavy,
    onSurface = KolamWhite,
    surfaceVariant = KolamElevated,
    onSurfaceVariant = KolamSilver,
    outline = KolamMutedNavy,
    outlineVariant = KolamMutedNavy,
    error = KolamError,
    onError = KolamMidnight
)

@Composable
@Suppress("UNUSED_PARAMETER")
fun KolamMasterKotlinTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = MidnightColorScheme,
        typography = Typography,
        content = content
    )
}
