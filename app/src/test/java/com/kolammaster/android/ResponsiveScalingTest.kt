package com.kolammaster.android

import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponsiveScalingTest {
    @Test
    fun referenceViewportKeepsUnitScaleAfterSystemInsets() {
        val insets = SystemInsetsPx(top = 88, bottom = 116)

        assertEquals(
            1f,
            calculateResponsiveScale(
                windowWidthPx = 1080,
                windowHeightPx = 2404,
                density = 2.75f,
                insets = insets
            ),
            0.0001f
        )
    }

    @Test
    fun smallerViewportUsesTheLimitingSafeAreaAxis() {
        val scale = calculateResponsiveScale(
            windowWidthPx = 900,
            windowHeightPx = 2000,
            density = 2.75f,
            insets = SystemInsetsPx(top = 88, bottom = 116)
        )

        assertTrue(scale < 1f)
        assertEquals(0.816f, scale, 0.002f)
    }

    @Test
    fun largerViewportScalesUpWithoutChangingAspectRatio() {
        val scale = calculateResponsiveScale(
            windowWidthPx = 1440,
            windowHeightPx = 3200,
            density = 2.75f,
            insets = SystemInsetsPx(top = 88, bottom = 116)
        )

        assertTrue(scale > 1f)
        assertEquals(1.333f, scale, 0.002f)
    }

    @Test
    fun narrowAndTallViewportIsWidthLimited() {
        val scale = calculateResponsiveScale(
            windowWidthPx = 900,
            windowHeightPx = 2800,
            density = 2.75f,
            insets = SystemInsetsPx(top = 88, bottom = 116)
        )

        assertEquals(0.833f, scale, 0.002f)
    }

    @Test
    fun referenceContentSizeReflectsMeasuredSystemInsets() {
        val size = referenceContentSize(
            density = 2.75f,
            insets = SystemInsetsPx(top = 88, bottom = 116)
        )

        assertEquals(392.727f, size.width.value, 0.001f)
        assertEquals(800f, size.height.value, 0.001f)
    }

    @Test
    fun scalingPreservesTheUsersFontScale() {
        val result = scaledDensity(Density(density = 2.75f, fontScale = 1.3f), 0.8f)

        assertEquals(2.2f, result.density, 0.0001f)
        assertEquals(1.3f, result.fontScale, 0.0001f)
    }
}
