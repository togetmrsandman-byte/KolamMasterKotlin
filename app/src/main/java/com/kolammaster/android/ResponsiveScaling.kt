package com.kolammaster.android

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlin.math.min

private const val REFERENCE_WIDTH_PX = 1080f
private const val REFERENCE_HEIGHT_PX = 2404f
private const val REFERENCE_DENSITY_DPI = 440f
private const val ANDROID_BASE_DENSITY_DPI = 160f
private const val MIN_REFERENCE_CONTENT_DP = 1f

// Vivo V21 5G values supplied by the device: wm size 1080x2404, wm density 440.
internal val VivoV21ReferenceWidthDp =
    REFERENCE_WIDTH_PX * ANDROID_BASE_DENSITY_DPI / REFERENCE_DENSITY_DPI
internal val VivoV21ReferenceHeightDp =
    REFERENCE_HEIGHT_PX * ANDROID_BASE_DENSITY_DPI / REFERENCE_DENSITY_DPI

internal data class SystemInsetsPx(
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0
)

internal val LocalResponsiveContentSize = staticCompositionLocalOf {
    DpSize.Zero
}
internal val LocalResponsiveScale = staticCompositionLocalOf { 1f }

internal fun referenceContentSize(
    density: Float,
    insets: SystemInsetsPx
): DpSize {
    require(density > 0f)
    val insetWidthDp = (insets.left + insets.right) / density
    val insetHeightDp = (insets.top + insets.bottom) / density
    return DpSize(
        width = (VivoV21ReferenceWidthDp - insetWidthDp)
            .coerceAtLeast(MIN_REFERENCE_CONTENT_DP).dp,
        height = (VivoV21ReferenceHeightDp - insetHeightDp)
            .coerceAtLeast(MIN_REFERENCE_CONTENT_DP).dp
    )
}

internal fun calculateResponsiveScale(
    windowWidthPx: Int,
    windowHeightPx: Int,
    density: Float,
    insets: SystemInsetsPx
): Float {
    require(density > 0f)
    val referenceSize = referenceContentSize(density, insets)
    val availableWidthPx = (windowWidthPx - insets.left - insets.right).coerceAtLeast(0)
    val availableHeightPx = (windowHeightPx - insets.top - insets.bottom).coerceAtLeast(0)
    val widthScale = (availableWidthPx / density) / referenceSize.width.value
    val heightScale = (availableHeightPx / density) / referenceSize.height.value
    return min(widthScale, heightScale).takeIf { it.isFinite() && it > 0f } ?: 0.01f
}

internal fun scaledDensity(baseDensity: Density, scale: Float): Density =
    Density(
        density = baseDensity.density * scale,
        fontScale = baseDensity.fontScale
    )

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ResponsiveScaleProvider(content: @Composable () -> Unit) {
    val baseDensity = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val systemBars = WindowInsets.systemBars
        val insets = SystemInsetsPx(
            left = systemBars.getLeft(baseDensity, layoutDirection),
            top = systemBars.getTop(baseDensity),
            right = systemBars.getRight(baseDensity, layoutDirection),
            bottom = systemBars.getBottom(baseDensity)
        )
        val windowWidthPx = with(baseDensity) { maxWidth.roundToPx() }
        val windowHeightPx = with(baseDensity) { maxHeight.roundToPx() }
        val requestedScale = calculateResponsiveScale(
            windowWidthPx = windowWidthPx,
            windowHeightPx = windowHeightPx,
            density = baseDensity.density,
            insets = insets
        )
        val imeVisible = WindowInsets.ime.getBottom(baseDensity) > 0
        var lastStableScale by remember { mutableFloatStateOf(requestedScale) }
        val scale = if (imeVisible) lastStableScale else requestedScale
        SideEffect {
            if (!imeVisible) lastStableScale = requestedScale
        }

        val contentSize = remember(baseDensity.density, insets) {
            referenceContentSize(baseDensity.density, insets)
        }
        val scaledDensity = remember(baseDensity.density, baseDensity.fontScale, scale) {
            scaledDensity(baseDensity, scale)
        }

        androidx.compose.runtime.CompositionLocalProvider(
            LocalDensity provides scaledDensity,
            LocalResponsiveContentSize provides contentSize,
            LocalResponsiveScale provides scale,
            content = content
        )
    }
}
