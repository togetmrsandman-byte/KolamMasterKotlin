package com.kolammaster.app

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

internal val playbackSpeeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f)
internal const val BASE_PATH_DURATION_MILLIS = 3_000
internal val PlaybackControlHeight = 40.dp
private val DefaultPlaybackLabelFontSize = 14.sp
private val LocalPlaybackLabelFontSize = compositionLocalOf { DefaultPlaybackLabelFontSize }

internal fun playbackSpeedLabel(speed: Float): String =
    "${speed.toString().removeSuffix(".0")}×"

internal fun scaledAnimationDuration(
    durationMillis: Int,
    speed: Float,
    startingProgress: Float = 0f
): Int = (durationMillis * (1f - startingProgress.coerceIn(0f, 1f)) / speed)
    .roundToInt()
    .coerceAtLeast(1)

internal fun pathPlaybackDurationMillis(pathCount: Int): Int =
    (pathCount.coerceAtLeast(1).toLong() * BASE_PATH_DURATION_MILLIS)
        .coerceAtMost(Int.MAX_VALUE.toLong())
        .toInt()

internal class LessonViewTransform {
    var scale by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set

    fun applyGesture(pan: Offset, zoom: Float) {
        scale = (scale * zoom).coerceIn(1f, 5f)
        offset = if (scale > 1f) offset + pan else Offset.Zero
    }

    fun reset() {
        scale = 1f
        offset = Offset.Zero
    }
}

internal fun Modifier.lessonViewTransform(
    transform: LessonViewTransform,
    enabled: Boolean
): Modifier = pointerInput(enabled, transform) {
    if (enabled) {
        detectTransformGestures { _, pan, zoom, _ ->
            transform.applyGesture(pan, zoom)
        }
    }
}.graphicsLayer {
    scaleX = transform.scale
    scaleY = transform.scale
    translationX = transform.offset.x
    translationY = transform.offset.y
}

@Composable
internal fun LessonStatusRow(
    text: String?,
    modifier: Modifier = Modifier,
    alignStart: Boolean = false,
    shining: Boolean = false,
    containerColor: Color = KolamGold,
    contentColor: Color = KolamText
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .padding(top = 4.dp),
        horizontalArrangement = if (alignStart) {
            Arrangement.Start
        } else {
            Arrangement.Center
        },
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (text != null) {
            Box {
                Surface(
                    shape = ButtonDefaults.shape,
                    color = containerColor,
                    contentColor = contentColor,
                    shadowElevation = 2.dp
                ) {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(ButtonDefaults.ContentPadding)
                    )
                }
                if (shining) {
                    ShineOverlay(
                        modifier = Modifier.matchParentSize(),
                        shape = ButtonDefaults.shape
                    )
                }
            }
        }
    }
}

@Composable
internal fun LessonActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shining: Boolean = false,
    containerColor: androidx.compose.ui.graphics.Color? = null,
    contentColor: androidx.compose.ui.graphics.Color? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding
) {
    val animatedContainerColor = if (shining && containerColor != null) {
        val transition = rememberInfiniteTransition(label = "publish button color")
        val progress by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(600, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "publish button color"
        )
        KolamAccent.copy(
            red = KolamAccent.red + (KolamButtonYellow.red - KolamAccent.red) * progress,
            green = KolamAccent.green +
                (KolamButtonYellow.green - KolamAccent.green) * progress,
            blue = KolamAccent.blue +
                (KolamButtonYellow.blue - KolamAccent.blue) * progress
        )
    } else {
        null
    }

    Box(modifier = modifier) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
            colors = if (animatedContainerColor != null) {
                ButtonDefaults.buttonColors(
                    containerColor = animatedContainerColor,
                    contentColor = contentColor ?: MaterialTheme.colorScheme.onPrimary
                )
            } else if (containerColor != null) {
                ButtonDefaults.buttonColors(
                    containerColor = containerColor,
                    contentColor = contentColor ?: MaterialTheme.colorScheme.onPrimary
                )
            } else {
                ButtonDefaults.buttonColors()
            },
            contentPadding = contentPadding
        ) {
            PlaybackControlLabel(text)
        }
        if (shining) {
            ShineOverlay(
                modifier = Modifier.matchParentSize(),
                shape = ButtonDefaults.shape
            )
        }
    }
}

@Composable
internal fun PlaybackControlLabel(
    text: String,
    modifier: Modifier = Modifier,
    onTextLayout: (androidx.compose.ui.text.TextLayoutResult) -> Unit = {}
) {
    PlaybackRowLabel(text, modifier, onTextLayout)
}

@Composable
private fun PlaybackRowLabel(
    text: String,
    modifier: Modifier,
    onTextLayout: (androidx.compose.ui.text.TextLayoutResult) -> Unit
) {
    Text(
        text = text,
        modifier = modifier.fillMaxWidth(),
        style = MaterialTheme.typography.labelLarge.copy(
            fontSize = LocalPlaybackLabelFontSize.current,
            lineHeight = (LocalPlaybackLabelFontSize.current.value * 1.25f).sp
        ),
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        textAlign = TextAlign.Center,
        onTextLayout = onTextLayout
    )
}

@Composable
internal fun PlaybackControlRow(
    labels: List<String>,
    modifier: Modifier = Modifier.fillMaxWidth(),
    content: @Composable RowScope.() -> Unit
) {
    BoxWithConstraints(modifier = modifier) {
        val textMeasurer = rememberTextMeasurer()
        val textStyle = MaterialTheme.typography.labelLarge
        val density = LocalDensity.current
        val labelFontSize = remember(labels, maxWidth, textStyle, textMeasurer, density) {
            val contentWidth = (maxWidth - 12.dp) / 4 - 9.dp
            val widthPx = with(density) { contentWidth.roundToPx() }.coerceAtLeast(0)
            var candidate = DefaultPlaybackLabelFontSize.value
            while (candidate > MIN_PLAYBACK_LABEL_FONT_SIZE &&
                labels.any { label ->
                    textMeasurer.measure(
                        text = label,
                        style = textStyle.copy(
                            fontSize = candidate.sp,
                            lineHeight = (candidate * 1.25f).sp
                        ),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Clip,
                        constraints = Constraints(maxWidth = widthPx)
                    ).didOverflowWidth
                }
            ) {
                candidate -= PLAYBACK_LABEL_FONT_SIZE_STEP
            }
            candidate.sp
        }
        CompositionLocalProvider(LocalPlaybackLabelFontSize provides labelFontSize) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                content = content
            )
        }
    }
}

private const val MIN_PLAYBACK_LABEL_FONT_SIZE = 1f
private const val PLAYBACK_LABEL_FONT_SIZE_STEP = 0.5f

internal fun dotsOrderStatusText(dotInformation: String?, localizedHeading: String): String {
    if (dotInformation.isNullOrEmpty()) {
        return localizedHeading
    }
    val englishHeading = "Dots Order"
    if (dotInformation.equals(englishHeading, ignoreCase = true)) return localizedHeading

    val hasDelimitedHeading = dotInformation.startsWith(englishHeading, ignoreCase = true) &&
        dotInformation.length > englishHeading.length &&
        dotInformation[englishHeading.length] in ":：-–—"
    if (hasDelimitedHeading) {
        return localizedHeading + dotInformation.substring(englishHeading.length)
    }
    return "$localizedHeading\n$dotInformation"
}

@Composable
internal fun AutoFitSingleLineText(
    text: String,
    modifier: Modifier = Modifier,
    maxFontSize: Float,
    minFontSize: Float,
    color: Color = Color.Unspecified,
    fontWeight: androidx.compose.ui.text.font.FontWeight? = null,
    textAlign: TextAlign = TextAlign.Center
) {
    var fontSize by remember(text, maxFontSize, minFontSize) {
        mutableFloatStateOf(maxFontSize)
    }
    Text(
        text = text,
        modifier = modifier,
        fontSize = fontSize.sp,
        color = color,
        fontWeight = fontWeight,
        maxLines = 1,
        softWrap = false,
        textAlign = textAlign,
        onTextLayout = { layout ->
            if (layout.didOverflowWidth && fontSize > minFontSize) {
                fontSize -= 1f
            }
        }
    )
}

@Composable
private fun ShineOverlay(
    modifier: Modifier,
    shape: androidx.compose.ui.graphics.Shape
) {
    val transition = rememberInfiniteTransition(label = "completion shine")
    val shinePosition by transition.animateFloat(
        initialValue = -0.5f,
        targetValue = 1.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "completion shine position"
    )
    Canvas(
        modifier = modifier.clip(shape)
    ) {
        val centerX = size.width * shinePosition
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(
                    Color.Transparent,
                    Color.White.copy(alpha = 0.34f),
                    Color.Transparent
                ),
                start = Offset(centerX - size.width * 0.22f, 0f),
                end = Offset(centerX + size.width * 0.22f, size.height)
            )
        )
    }
}

@Composable
internal fun PlaybackSpeedControl(
    speed: Float,
    onSpeedChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        Button(
            onClick = { expanded = true },
            modifier = Modifier
                .fillMaxWidth()
                .height(PlaybackControlHeight),
            contentPadding = PaddingValues(
                start = 4.dp,
                top = 10.dp,
                end = 4.dp,
                bottom = 10.dp
            )
        ) {
            PlaybackControlLabel(stringResource(R.string.speed_label, playbackSpeedLabel(speed)))
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            playbackSpeeds.forEach { option ->
                DropdownMenuItem(
                    text = { Text(playbackSpeedLabel(option)) },
                    onClick = {
                        onSpeedChange(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
internal fun FlowerCelebration(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "falling flowers")
    val flowers = remember { createFlowers() }
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(5200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "flower fall progress"
    )
    Canvas(modifier = modifier) {
        drawFlowerRain(progress, flowers)
    }
}

private fun DrawScope.drawFlowerRain(progress: Float, flowers: List<CelebrationFlower>) {
    val sizePx = 11.dp.toPx()
    val travel = size.height + sizePx * 2f
    flowers.forEach { flower ->
        val fall = (progress + flower.phase) % 1f
        val center = Offset(
            x = size.width * flower.x +
                sin((progress * flower.driftCycles + flower.driftPhase) * 2f * PI.toFloat()) *
                    size.width * flower.driftAmount,
            y = -sizePx + travel * fall
        )
        rotate(
            degrees = flower.rotation + progress * flower.rotationCycles * 360f,
            pivot = center
        ) {
            when (flower.type) {
                FlowerType.Hibiscus -> drawHibiscus(center, sizePx, flower.color)
                FlowerType.Lotus -> drawLotus(center, sizePx, flower.color)
                FlowerType.Rose -> drawRose(center, sizePx, flower.color)
            }
        }
    }
}

private enum class FlowerType { Hibiscus, Lotus, Rose }

private data class CelebrationFlower(
    val x: Float,
    val phase: Float,
    val driftAmount: Float,
    val driftCycles: Int,
    val driftPhase: Float,
    val rotation: Float,
    val rotationCycles: Int,
    val type: FlowerType,
    val color: Color
)

private fun createFlowers(): List<CelebrationFlower> {
    val random = Random.Default
    val types = FlowerType.entries.toList().shuffled(random)
    val hibiscusColors = listOf(Color(0xFFE53935), Color(0xFFFFC928), Color(0xFFFF8C32))
    val roseColors = listOf(
        Color(0xFFD62828),
        Color(0xFFE95D9F),
        Color(0xFFFFC928),
        Color(0xFFFF8C32)
    )
    return List(9) { index ->
        val type = types[index % types.size]
        val color = when (type) {
            FlowerType.Hibiscus -> hibiscusColors[random.nextInt(hibiscusColors.size)]
            FlowerType.Lotus -> Color(0xFFFF83B8)
            FlowerType.Rose -> roseColors[random.nextInt(roseColors.size)]
        }
        CelebrationFlower(
            x = (index + random.nextFloat()) / 9f,
            phase = random.nextFloat(),
            driftAmount = 0.015f + random.nextFloat() * 0.045f,
            driftCycles = 1 + random.nextInt(3),
            driftPhase = random.nextFloat(),
            rotation = random.nextFloat() * 360f,
            rotationCycles = 1 + random.nextInt(2),
            type = type,
            color = color
        )
    }
}

private fun DrawScope.drawHibiscus(center: Offset, radius: Float, color: Color) {
    repeat(5) { petal ->
        rotate(degrees = petal * 72f, pivot = center) {
            drawOval(
                color = color.copy(alpha = 0.9f),
                topLeft = Offset(center.x - radius * 0.72f, center.y - radius * 1.55f),
                size = Size(radius * 1.44f, radius * 1.65f)
            )
        }
    }
    drawCircle(Color(0xFFFFD54F), radius = radius * 0.25f, center = center)
}

private fun DrawScope.drawLotus(center: Offset, radius: Float, color: Color) {
    repeat(2) { layer ->
        repeat(7) { petal ->
            rotate(degrees = petal * (360f / 7f) + layer * (180f / 7f), pivot = center) {
                val scale = if (layer == 0) 1f else 0.72f
                drawOval(
                    color = color.copy(alpha = if (layer == 0) 0.68f else 0.96f),
                    topLeft = Offset(
                        center.x - radius * 0.27f * scale,
                        center.y - radius * 1.95f * scale
                    ),
                    size = Size(radius * 0.54f * scale, radius * 2.05f * scale)
                )
            }
        }
    }
    drawCircle(Color(0xFFFFD54F), radius = radius * 0.2f, center = center)
}

private fun DrawScope.drawRose(center: Offset, radius: Float, color: Color) {
    val petalShades = listOf(
        color.copy(alpha = 0.68f),
        color.copy(alpha = 0.82f),
        color
    )
    repeat(3) { layer ->
        val count = 5 + layer
        val petalRadius = radius * (0.72f - layer * 0.14f)
        repeat(count) { petal ->
            val angle = 2f * PI.toFloat() * petal / count + layer * 0.48f
            drawCircle(
                color = petalShades[layer],
                radius = petalRadius,
                center = Offset(
                    center.x + cos(angle) * radius * (0.48f - layer * 0.12f),
                    center.y + sin(angle) * radius * (0.48f - layer * 0.12f)
                )
            )
        }
    }
    drawCircle(Color(0xFF8D2738), radius = radius * 0.18f, center = center)
}
