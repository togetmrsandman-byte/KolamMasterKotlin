package com.kolammaster.app

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

internal val playbackSpeeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f)

internal fun playbackSpeedLabel(speed: Float): String =
    "${speed.toString().removeSuffix(".0")}×"

internal fun scaledAnimationDuration(
    durationMillis: Int,
    speed: Float,
    startingProgress: Float = 0f
): Int = (durationMillis * (1f - startingProgress.coerceIn(0f, 1f)) / speed)
    .roundToInt()
    .coerceAtLeast(1)

@Composable
internal fun LessonStatusRow(
    text: String?,
    modifier: Modifier = Modifier,
    alignStart: Boolean = false,
    shining: Boolean = false
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
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
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
    Box(modifier = modifier) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
            colors = if (containerColor != null) {
                ButtonDefaults.buttonColors(
                    containerColor = containerColor,
                    contentColor = contentColor ?: MaterialTheme.colorScheme.onPrimary
                )
            } else {
                ButtonDefaults.buttonColors()
            },
            contentPadding = contentPadding
        ) {
            Text(text)
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
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(
                start = 4.dp,
                top = 10.dp,
                end = 4.dp,
                bottom = 10.dp
            )
        ) {
            Text("Speed ${playbackSpeedLabel(speed)}", maxLines = 1)
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
