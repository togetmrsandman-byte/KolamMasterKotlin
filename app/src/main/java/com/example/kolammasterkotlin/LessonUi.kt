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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

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
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding
) {
    Box(modifier = modifier) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
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
        drawFlowerRain(progress)
    }
}

private fun DrawScope.drawFlowerRain(progress: Float) {
    val flowers = listOf(
        0.10f to 0.00f,
        0.28f to 0.23f,
        0.48f to 0.47f,
        0.69f to 0.11f,
        0.88f to 0.68f
    )
    val petalColors = listOf(
        Color(0xFFFF5A8A),
        Color(0xFFFFA6C1),
        Color(0xFFFF3D6E),
        Color(0xFFFFD3E0)
    )
    val petalRadius = 6.dp.toPx()
    val centerRadius = 2.4.dp.toPx()
    val flowerTravel = size.height + 2f * petalRadius

    flowers.forEachIndexed { flowerIndex, (xFraction, offset) ->
        val fall = (progress + offset) % 1f
        val center = Offset(
            x = size.width * xFraction,
            y = -petalRadius + flowerTravel * fall
        )
        rotate(degrees = progress * 360f + flowerIndex * 19f, pivot = center) {
            repeat(6) { petal ->
                val angle = 2f * PI.toFloat() * petal / 6f
                drawCircle(
                    color = petalColors[(flowerIndex + petal) % petalColors.size],
                    radius = petalRadius,
                    center = Offset(
                        x = center.x + cos(angle) * petalRadius * 0.82f,
                        y = center.y + sin(angle) * petalRadius * 0.82f
                    )
                )
            }
            drawCircle(color = Color(0xFFFFD54F), radius = centerRadius, center = center)
        }
    }
}
