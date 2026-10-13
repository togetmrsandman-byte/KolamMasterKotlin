package com.kolammaster.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackControlLabelInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ContactComposeTestActivity>()

    @Test
    fun playbackLabelsShareTheLargestFittingSizeAndRecompute() {
        val labels = listOf(
            "Previous",
            "Replay",
            "Speed 1.5×",
            "A long translated playback action"
        )
        val width = mutableIntStateOf(320)
        val fontScale = mutableStateOf(1f)
        val latestLayouts = AtomicReference<List<PlaybackLabelLayout?>>(
            List(labels.size) { null }
        )

        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale.value)
            ) {
                PlaybackControlRow(
                    labels = labels,
                    modifier = Modifier.width(width.intValue.dp)
                ) {
                    labels.forEach { text ->
                        Box(Modifier.weight(1f)) {
                            PlaybackControlLabel(text) { layout ->
                                val index = labels.indexOf(text)
                                latestLayouts.updateAndGet { current ->
                                    current.toMutableList().apply {
                                        this[index] = PlaybackLabelLayout(
                                            overflowed = layout.didOverflowWidth,
                                            lineCount = layout.lineCount,
                                            fontSizeSp = layout.layoutInput.style.fontSize.value,
                                            fontScale = layout.layoutInput.density.fontScale
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        composeRule.waitForIdle()
        val narrow = requireNotNull(latestLayouts.get()).filterNotNull()
        assertEquals(4, narrow.size)
        assertTrue(narrow.all { !it.overflowed && it.lineCount == 1 })
        assertEquals(1, narrow.map { it.fontSizeSp }.distinct().size)
        val narrowFontSize = narrow.first().fontSizeSp
        labels.forEach { composeRule.onNodeWithText(it).assertExists() }

        composeRule.runOnIdle { width.intValue = 480 }
        composeRule.waitForIdle()
        val wide = requireNotNull(latestLayouts.get()).filterNotNull()
        assertTrue(wide.all { !it.overflowed && it.lineCount == 1 })
        assertTrue(wide.first().fontSizeSp > narrowFontSize)
        assertEquals(1, wide.map { it.fontSizeSp }.distinct().size)

        composeRule.runOnIdle { fontScale.value = 2f }
        composeRule.waitForIdle()
        val scaled = requireNotNull(latestLayouts.get()).filterNotNull()
        assertTrue(scaled.all { it.fontScale == 2f && !it.overflowed && it.lineCount == 1 })
        assertEquals(1, scaled.map { it.fontSizeSp }.distinct().size)
        assertTrue(scaled.first().fontSizeSp < wide.first().fontSizeSp)
    }

    @Test
    fun longActionLabelStaysSingleLineInsideButtonBounds() {
        val text = "An unusually long translated publish action"
        var buttonHeightPx = 0

        composeRule.setContent {
            Box(
                Modifier
                    .width(180.dp)
                    .onSizeChanged { buttonHeightPx = it.height }
            ) {
                LessonActionButton(text = text, onClick = {})
            }
        }

        val action = composeRule.onNodeWithText(text)
        action.assertExists()
        action.assertHasClickAction()
        composeRule.waitForIdle()

        val density = composeRule.activity.resources.displayMetrics.density
        assertTrue("Action button height unexpectedly grew", buttonHeightPx / density <= 60f)
    }
}

private data class PlaybackLabelLayout(
    val overflowed: Boolean,
    val lineCount: Int,
    val fontSizeSp: Float,
    val fontScale: Float
)
