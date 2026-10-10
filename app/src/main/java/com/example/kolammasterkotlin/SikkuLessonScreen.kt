package com.kolammaster.app

import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import kotlin.math.hypot
import kotlin.math.min

private val artworkColorFilter = ColorMatrixColorFilter(
    ColorMatrix(
        floatArrayOf(
            1f, 0f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f, 0f,
            0.2126f, 0.7152f, 0.0722f, 0f, 0f
        )
    )
)

internal sealed interface SikkuScreenState {
    data object Loading : SikkuScreenState
    data class Ready(val lesson: SikkuLesson) : SikkuScreenState
    data class Failed(val message: String) : SikkuScreenState
}

@Composable
internal fun SikkuLessonScreen(
    lesson: SikkuLesson,
    onPublish: () -> Unit,
    modifier: Modifier = Modifier
) {
    var started by rememberSaveable { mutableStateOf(false) }
    var complete by rememberSaveable { mutableStateOf(false) }
    var stepIndex by rememberSaveable { mutableIntStateOf(-1) }
    var replayRequest by remember { mutableIntStateOf(0) }
    var playbackSpeed by rememberSaveable { mutableFloatStateOf(1f) }
    var animatedStepIndex by remember { mutableIntStateOf(Int.MIN_VALUE) }
    var animatedReplayRequest by remember { mutableIntStateOf(Int.MIN_VALUE) }
    val progress = remember(stepIndex, replayRequest) { Animatable(0f) }

    LaunchedEffect(started, complete, stepIndex, replayRequest, playbackSpeed) {
        if (started && !complete && stepIndex >= 0) {
            if (animatedStepIndex != stepIndex || animatedReplayRequest != replayRequest) {
                progress.snapTo(0f)
                animatedStepIndex = stepIndex
                animatedReplayRequest = replayRequest
            }
            progress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = scaledAnimationDuration(
                        lesson.steps[stepIndex].animationDurationMillis,
                        playbackSpeed,
                        progress.value
                    ),
                    easing = LinearEasing
                )
            )
        } else if (started && !complete) {
            progress.snapTo(0f)
            animatedStepIndex = Int.MIN_VALUE
        } else if (!started) {
            progress.snapTo(0f)
        } else {
            progress.snapTo(1f)
        }
    }

    Column(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val inDotsStage = started && !complete && stepIndex < 0
        LessonStatusRow(
            text = when {
                complete -> "Kolam Completed"
                !started -> "Preview"
                inDotsStage -> lesson.dotInformation
                else -> "Step ${lesson.steps[stepIndex].number} of ${lesson.steps.size}"
            },
            shining = complete
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            LessonArtwork(
                lesson = lesson,
                completedSteps = if (complete) {
                    lesson.steps
                } else if (started && stepIndex > 0) {
                    lesson.steps.take(stepIndex)
                } else {
                    emptyList()
                },
                currentStep = if (started && !complete && stepIndex >= 0) {
                    lesson.steps[stepIndex]
                } else {
                    null
                },
                progress = progress.value,
                showPreview = !started,
                showDots = started,
                modifier = Modifier.fillMaxSize()
            )
            if (complete) {
                FlowerCelebration(Modifier.fillMaxSize())
            }
        }

        when {
            !started -> Button(
                onClick = { started = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Start")
            }
            else -> Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Button(
                    onClick = {
                        if (complete) {
                            complete = false
                            stepIndex = (lesson.steps.lastIndex - 1).coerceAtLeast(-1)
                        } else {
                            stepIndex = (stepIndex - 1).coerceAtLeast(-1)
                        }
                    },
                    enabled = complete || stepIndex >= 0,
                    modifier = Modifier.weight(1f),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 4.dp,
                        vertical = 10.dp
                    )
                ) {
                    Text("Previous")
                }
                Button(
                    onClick = {
                        if (complete) {
                            complete = false
                            started = false
                            stepIndex = -1
                        } else {
                            replayRequest++
                        }
                    },
                    enabled = complete || stepIndex >= 0,
                    modifier = Modifier.weight(1f),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 4.dp,
                        vertical = 10.dp
                    )
                ) {
                    Text("Replay")
                }
                PlaybackSpeedControl(
                    speed = playbackSpeed,
                    onSpeedChange = { playbackSpeed = it },
                    modifier = Modifier.weight(1f)
                )
                LessonActionButton(
                    text = when {
                        complete -> "Publish"
                        stepIndex == lesson.steps.lastIndex -> "Finish"
                        else -> "Next"
                    },
                    onClick = {
                        if (complete) {
                            onPublish()
                        } else {
                            if (stepIndex < 0) {
                                stepIndex = 0
                            } else if (stepIndex == lesson.steps.lastIndex) {
                                complete = true
                            } else {
                                stepIndex++
                            }
                        }
                    },
                    enabled = true,
                    shining = complete,
                    containerColor = if (complete) androidx.compose.ui.graphics.Color(0xFFFFC928) else null,
                    contentColor = if (complete) androidx.compose.ui.graphics.Color(0xFF392800) else null,
                    modifier = Modifier.weight(1f),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 4.dp,
                        vertical = 10.dp
                    )
                )
            }
        }
        Spacer(modifier = Modifier.height(2.dp))
    }
}

@Composable
private fun LessonArtwork(
    lesson: SikkuLesson,
    completedSteps: List<LessonStep>,
    currentStep: LessonStep?,
    progress: Float,
    showPreview: Boolean,
    showDots: Boolean,
    modifier: Modifier
) {
    Canvas(modifier = modifier) {
        drawIntoCanvas { canvas ->
            drawLessonLayers(
                canvas = canvas.nativeCanvas,
                width = size.width,
                height = size.height,
                lesson = lesson,
                completedSteps = completedSteps,
                currentStep = currentStep,
                progress = progress,
                showPreview = showPreview,
                showDots = showDots
            )
        }
    }
}

private fun drawLessonLayers(
    canvas: AndroidCanvas,
    width: Float,
    height: Float,
    lesson: SikkuLesson,
    completedSteps: List<LessonStep>,
    currentStep: LessonStep?,
    progress: Float,
    showPreview: Boolean,
    showDots: Boolean
) {
    val scale = min(width / lesson.preview.width, height / lesson.preview.height)
    val drawnWidth = lesson.preview.width * scale
    val drawnHeight = lesson.preview.height * scale
    val imageRect = RectF(
        (width - drawnWidth) / 2f,
        (height - drawnHeight) / 2f,
        (width + drawnWidth) / 2f,
        (height + drawnHeight) / 2f
    )
    val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    if (showPreview) {
        canvas.drawBitmap(lesson.preview, null, imageRect, imagePaint)
    } else {
        canvas.drawRect(imageRect, Paint().apply { color = Color.BLACK })
    }

    for (completedStep in completedSteps) {
        drawBitmapThroughPath(
            canvas = canvas,
            bitmap = lesson.preview,
            imageRect = imageRect,
            imagePaint = imagePaint,
            coordinateWidth = lesson.coordinateWidth,
            coordinateHeight = lesson.coordinateHeight,
            step = completedStep,
            progress = 1f,
            applyArtworkColorFilter = true
        )
    }

    if (currentStep != null && progress > 0f) {
        drawBitmapThroughPath(
            canvas = canvas,
            bitmap = lesson.tinted,
            imageRect = imageRect,
            imagePaint = imagePaint,
            coordinateWidth = lesson.coordinateWidth,
            coordinateHeight = lesson.coordinateHeight,
            step = currentStep,
            progress = progress,
            applyArtworkColorFilter = true
        )
    }

    if (showDots) {
        val dotsPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
        }
        canvas.drawBitmap(lesson.dots, null, imageRect, dotsPaint)
    }
}

internal fun drawBitmapThroughPath(
    canvas: AndroidCanvas,
    bitmap: android.graphics.Bitmap,
    imageRect: RectF,
    imagePaint: Paint,
    step: LessonStep,
    coordinateWidth: Float,
    coordinateHeight: Float,
    progress: Float,
    applyArtworkColorFilter: Boolean
) {
    val revealMask = createRevealMask(
        imageRect = imageRect,
        coordinateWidth = coordinateWidth,
        coordinateHeight = coordinateHeight,
        step = step,
        progress = progress
    ) ?: return
    val saveCount = canvas.save()
    canvas.clipPath(revealMask)
    val artworkPaint = Paint(imagePaint).apply {
        if (applyArtworkColorFilter) colorFilter = artworkColorFilter
    }
    canvas.drawBitmap(bitmap, null, imageRect, artworkPaint)
    canvas.restoreToCount(saveCount)
}

internal fun createRevealMask(
    imageRect: RectF,
    coordinateWidth: Float,
    coordinateHeight: Float,
    step: LessonStep,
    progress: Float
): Path? {
    val scaleX = imageRect.width() / coordinateWidth
    val scaleY = imageRect.height() / coordinateHeight
    val widthScale = hypot(scaleX, scaleY) / kotlin.math.sqrt(2f)
    var totalLength = 0f
    for (stroke in step.strokes) {
        for (index in 0 until stroke.nodes.lastIndex) {
            val start = stroke.nodes[index]
            val end = stroke.nodes[index + 1]
            totalLength += hypot(end.x - start.x, end.y - start.y)
        }
    }
    if (totalLength <= 0f) return null

    var remaining = totalLength * progress.coerceIn(0f, 1f)
    val maskPath = Path()
    val segmentPath = Path()
    val segmentFill = Path()
    val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    var hasMask = false

    for (stroke in step.strokes) {
        for (segmentIndex in 0 until stroke.nodes.lastIndex) {
            if (remaining <= 0f) return maskPath.takeIf { hasMask }

            val start = stroke.nodes[segmentIndex]
            val end = stroke.nodes[segmentIndex + 1]
            val segmentLength = hypot(end.x - start.x, end.y - start.y)
            if (segmentLength <= 0f) continue

            val visibleLength = min(remaining, segmentLength)
            val fraction = visibleLength / segmentLength
            val visibleEndX = start.x + (end.x - start.x) * fraction
            val visibleEndY = start.y + (end.y - start.y) * fraction
            val segmentWidth = stroke.segmentWidths.getOrNull(segmentIndex) ?: stroke.width
            strokePaint.strokeWidth = segmentWidth * widthScale
            segmentPath.reset()
            segmentPath.moveTo(
                imageRect.left + start.x * scaleX,
                imageRect.top + start.y * scaleY
            )
            segmentPath.lineTo(
                imageRect.left + visibleEndX * scaleX,
                imageRect.top + visibleEndY * scaleY
            )
            segmentFill.reset()
            strokePaint.getFillPath(segmentPath, segmentFill)
            if (hasMask) {
                check(maskPath.op(segmentFill, Path.Op.UNION)) { "Could not combine path segments" }
            } else {
                maskPath.set(segmentFill)
                hasMask = true
            }
            remaining -= visibleLength
        }
    }
    return maskPath.takeIf { hasMask }
}
