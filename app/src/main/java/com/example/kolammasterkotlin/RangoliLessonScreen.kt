package com.example.kolammasterkotlin

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import kotlin.math.min

@Composable
internal fun RangoliLessonScreen(lesson: RangoliLesson, modifier: Modifier = Modifier) {
    var started by rememberSaveable { mutableStateOf(false) }
    var stepIndex by rememberSaveable { mutableIntStateOf(0) }
    var complete by rememberSaveable { mutableStateOf(false) }
    var replayRequest by remember { mutableIntStateOf(0) }
    var playbackSpeed by rememberSaveable { mutableFloatStateOf(1f) }
    var animatedStepIndex by remember { mutableIntStateOf(Int.MIN_VALUE) }
    var animatedReplayRequest by remember { mutableIntStateOf(Int.MIN_VALUE) }
    val progress = remember { Animatable(0f) }
    val currentStep = lesson.steps.getOrNull(stepIndex)

    LaunchedEffect(started, complete, stepIndex, replayRequest, playbackSpeed) {
        val structuralStep = currentStep?.structuralLesson
        if (started && !complete && structuralStep != null) {
            if (animatedStepIndex != stepIndex || animatedReplayRequest != replayRequest) {
                progress.snapTo(0f)
                animatedStepIndex = stepIndex
                animatedReplayRequest = replayRequest
            }
            progress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = scaledAnimationDuration(
                        currentStep.animationDurationMillis,
                        playbackSpeed,
                        progress.value
                    ),
                    easing = LinearEasing
                )
            )
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
        LessonStatusRow(
            text = when {
                complete -> "Kolam Completed"
                !started -> "Preview"
                else -> "Step ${currentStep?.number} of ${lesson.steps.size}"
            },
            shining = complete
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            RangoliArtwork(
                lesson = lesson,
                currentStep = currentStep.takeIf { started && !complete },
                completedSteps = if (complete) {
                    emptyList()
                } else if (started) {
                    lesson.steps.take(stepIndex)
                } else {
                    emptyList()
                },
                progress = progress.value,
                showPreview = !started || complete,
                modifier = Modifier.fillMaxSize()
            )
            if (complete) {
                FlowerCelebration(Modifier.fillMaxSize())
            }
        }

        when {
            !started -> Button(
                onClick = {
                    started = true
                    stepIndex = 0
                },
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
                            stepIndex = (lesson.steps.lastIndex - 1).coerceAtLeast(0)
                        } else {
                            stepIndex--
                        }
                    },
                    enabled = complete || stepIndex > 0,
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
                            stepIndex = 0
                        } else {
                            replayRequest++
                        }
                    },
                    enabled = complete || currentStep?.structuralLesson != null,
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
                        if (!complete) {
                            if (stepIndex == lesson.steps.lastIndex) {
                                complete = true
                            } else {
                                stepIndex++
                            }
                        }
                    },
                    enabled = true,
                    shining = complete,
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
private fun RangoliArtwork(
    lesson: RangoliLesson,
    currentStep: RangoliStep?,
    completedSteps: List<RangoliStep>,
    progress: Float,
    showPreview: Boolean,
    modifier: Modifier
) {
    Canvas(modifier = modifier) {
        drawIntoCanvas { canvas ->
            drawRangoliLayers(
                canvas = canvas.nativeCanvas,
                width = size.width,
                height = size.height,
                lesson = lesson,
                currentStep = currentStep,
                completedSteps = completedSteps,
                progress = progress,
                showPreview = showPreview
            )
        }
    }
}

private fun drawRangoliLayers(
    canvas: AndroidCanvas,
    width: Float,
    height: Float,
    lesson: RangoliLesson,
    currentStep: RangoliStep?,
    completedSteps: List<RangoliStep>,
    progress: Float,
    showPreview: Boolean
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

    canvas.drawRect(imageRect, Paint().apply { color = Color.BLACK })
    if (showPreview) {
        canvas.drawBitmap(lesson.preview, null, imageRect, imagePaint)
        return
    }

    val structuralStepCount = lesson.steps.count { it.structuralLesson != null }
    val completedStructuralStepCount =
        completedSteps.count { it.structuralLesson != null }
    val structuralPhaseComplete = completedStructuralStepCount >= structuralStepCount

    completedSteps.forEach { step ->
        if (step.structuralLesson != null || structuralPhaseComplete) {
            drawCompletedRangoliStep(
                canvas = canvas,
                lesson = lesson,
                step = step,
                imageRect = imageRect,
                imagePaint = imagePaint
            )
        }
    }
    if (currentStep != null &&
        (currentStep.structuralLesson != null || structuralPhaseComplete)
    ) {
        drawCurrentRangoliStep(
            canvas = canvas,
            lesson = lesson,
            step = currentStep,
            imageRect = imageRect,
            imagePaint = imagePaint,
            progress = progress
        )
    }
}

private fun drawCompletedRangoliStep(
    canvas: AndroidCanvas,
    lesson: RangoliLesson,
    step: RangoliStep,
    imageRect: RectF,
    imagePaint: Paint
) {
    if (step.structuralLesson != null) {
        drawBitmapThroughPath(
            canvas = canvas,
            bitmap = lesson.artwork,
            imageRect = imageRect,
            imagePaint = imagePaint,
            step = step.structuralLesson,
            coordinateWidth = lesson.coordinateWidth,
            coordinateHeight = lesson.coordinateHeight,
            progress = 1f,
            applyArtworkColorFilter = false
        )
    } else if (step.fillBitmap != null) {
        drawFillImage(canvas, step.fillBitmap, imageRect, imagePaint)
    }
}

private fun drawCurrentRangoliStep(
    canvas: AndroidCanvas,
    lesson: RangoliLesson,
    step: RangoliStep,
    imageRect: RectF,
    imagePaint: Paint,
    progress: Float
) {
    val structuralLesson = step.structuralLesson
    if (structuralLesson != null) {
        if (progress <= 0f) return
        drawBitmapThroughPath(
            canvas = canvas,
            bitmap = lesson.tinted,
            imageRect = imageRect,
            imagePaint = imagePaint,
            step = structuralLesson,
            coordinateWidth = lesson.coordinateWidth,
            coordinateHeight = lesson.coordinateHeight,
            progress = progress,
            applyArtworkColorFilter = false
        )
    } else if (step.fillBitmap != null) {
        drawFillImage(canvas, step.fillBitmap, imageRect, imagePaint)
    }
}

private fun drawFillImage(
    canvas: AndroidCanvas,
    fillBitmap: Bitmap,
    imageRect: RectF,
    imagePaint: Paint
) {
    val fillPaint = Paint(imagePaint).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
    }
    canvas.drawBitmap(fillBitmap, null, imageRect, fillPaint)
}
