package com.kolammaster.app

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlin.math.min

@Composable
internal fun RangoliLessonScreen(
    lesson: RangoliLesson,
    onPublish: () -> Unit,
    modifier: Modifier = Modifier
) {
    var started by rememberSaveable { mutableStateOf(false) }
    var stepIndex by rememberSaveable { mutableIntStateOf(0) }
    var completedStepIndices by remember { mutableStateOf(emptySet<Int>()) }
    var complete by rememberSaveable { mutableStateOf(false) }
    var replayRequest by remember { mutableIntStateOf(0) }
    var playbackSpeed by rememberSaveable { mutableFloatStateOf(1f) }
    var activePathIndex by remember(stepIndex, replayRequest) { mutableIntStateOf(0) }
    var animatedStepIndex by remember { mutableIntStateOf(Int.MIN_VALUE) }
    var animatedReplayRequest by remember { mutableIntStateOf(Int.MIN_VALUE) }
    val progress = remember(stepIndex, replayRequest) { Animatable(0f) }
    val hasPolygonBackedSteps = remember(lesson) {
        lesson.steps.any { it.structuralLesson?.polygonReveal != null }
    }
    val polygonPlaybacks = remember(lesson) {
        lesson.steps.mapNotNull { rangoliStep ->
            rangoliStep.structuralLesson?.let { step ->
                step.polygonReveal?.let {
                    step.number to PolygonRevealData.Playback(it)
                }
            }
        }.toMap()
    }
    val currentStep = lesson.steps.getOrNull(stepIndex)

    LaunchedEffect(started, complete, stepIndex, replayRequest, playbackSpeed) {
        val structuralStep = currentStep?.structuralLesson
        if (started && !complete && structuralStep != null) {
            if (animatedStepIndex != stepIndex || animatedReplayRequest != replayRequest) {
                progress.snapTo(0f)
                activePathIndex = 0
                structuralStep?.let { step ->
                    polygonPlaybacks[step.number]?.reset()
                }
                animatedStepIndex = stepIndex
                animatedReplayRequest = replayRequest
            }
            val loopStartedAt = SystemClock.elapsedRealtime()
            while (activePathIndex < structuralStep.pathCount) {
                val pathIndex = activePathIndex
                val durationMillis = scaledAnimationDuration(
                    currentStep.animationDurationMillis,
                    playbackSpeed,
                    progress.value
                )
                val pathStartedAt = SystemClock.elapsedRealtime()
                Log.d(
                    "PathTimingDebug",
                    "Rangoli start stepIndex=$stepIndex pathIndex=$pathIndex " +
                        "activePathIndex=$activePathIndex pathCount=${structuralStep.pathCount} " +
                        "playbackSpeed=$playbackSpeed progress=${progress.value} " +
                        "durationMillis=$durationMillis"
                )
                progress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(
                        durationMillis = durationMillis,
                        easing = LinearEasing
                    )
                )
                Log.d(
                    "PathTimingDebug",
                    "Rangoli path finished stepIndex=$stepIndex pathIndex=$pathIndex " +
                        "elapsedMillis=${SystemClock.elapsedRealtime() - pathStartedAt}"
                )
                if (activePathIndex + 1 < structuralStep.pathCount) progress.snapTo(0f)
                activePathIndex++
            }
            Log.d(
                "PathTimingDebug",
                "Rangoli loop finished stepIndex=$stepIndex pathCount=${structuralStep.pathCount} " +
                    "elapsedMillis=${SystemClock.elapsedRealtime() - loopStartedAt}"
            )
        } else if (!started) {
            progress.snapTo(0f)
            activePathIndex = 0
        } else {
            progress.snapTo(1f)
            activePathIndex = currentStep?.structuralLesson?.pathCount ?: 0
        }
    }

    Column(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        LessonStatusRow(
            text = when {
                complete -> stringResource(R.string.kolam_completed)
                !started -> stringResource(R.string.preview)
                else -> stringResource(
                    R.string.step_progress,
                    currentStep?.number ?: 0,
                    lesson.steps.size
                )
            },
            shining = complete,
            containerColor = if (complete) KolamGold else KolamButtonYellow,
            contentColor = if (complete) KolamText else KolamButtonText
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
                    if (hasPolygonBackedSteps) lesson.steps else emptyList()
                } else {
                    lesson.steps.filterIndexed { index, _ -> index in completedStepIndices }
                },
                progress = progress.value,
                activePathIndex = activePathIndex,
                showPreview = !started || (complete && !hasPolygonBackedSteps),
                polygonPlaybacks = polygonPlaybacks,
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
                Text(stringResource(R.string.start))
            }
            else -> PlaybackControlRow(
                labels = listOf(
                    stringResource(R.string.previous),
                    stringResource(R.string.replay),
                    *playbackSpeeds.map {
                        stringResource(R.string.speed_label, playbackSpeedLabel(it))
                    }.toTypedArray(),
                    stringResource(R.string.next),
                    stringResource(R.string.finish),
                    stringResource(R.string.publish)
                )
            ) {
                Button(
                    onClick = {
                        if (complete) {
                            complete = false
                            stepIndex = (lesson.steps.lastIndex - 1).coerceAtLeast(0)
                            completedStepIndices = (0 until stepIndex).toSet()
                        } else {
                            stepIndex--
                            completedStepIndices = completedStepIndices
                                .filter { it < stepIndex }
                                .toSet()
                        }
                    },
                    enabled = complete || stepIndex > 0,
                    modifier = Modifier
                        .weight(1f)
                        .height(PlaybackControlHeight),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = KolamButtonYellow,
                        contentColor = KolamButtonText,
                        disabledContainerColor = KolamButtonYellow,
                        disabledContentColor = KolamButtonText
                    ),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 4.dp,
                        vertical = 10.dp
                    )
                ) {
                    PlaybackControlLabel(stringResource(R.string.previous))
                }
                Button(
                    onClick = {
                        if (complete) {
                            complete = false
                            started = false
                            stepIndex = 0
                            completedStepIndices = emptySet()
                        } else {
                            completedStepIndices = completedStepIndices
                                .filter { it < stepIndex }
                                .toSet()
                            replayRequest++
                        }
                    },
                    enabled = complete || currentStep?.structuralLesson != null,
                    modifier = Modifier
                        .weight(1f)
                        .height(PlaybackControlHeight),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = KolamButtonYellow,
                        contentColor = KolamButtonText,
                        disabledContainerColor = KolamButtonYellow,
                        disabledContentColor = KolamButtonText
                    ),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 4.dp,
                        vertical = 10.dp
                    )
                ) {
                    PlaybackControlLabel(stringResource(R.string.replay))
                }
                PlaybackSpeedControl(
                    speed = playbackSpeed,
                    onSpeedChange = { playbackSpeed = it },
                    modifier = Modifier
                        .weight(1f)
                        .height(PlaybackControlHeight)
                )
                LessonActionButton(
                    text = when {
                        complete -> stringResource(R.string.publish)
                        stepIndex == lesson.steps.lastIndex -> stringResource(R.string.finish)
                        else -> stringResource(R.string.next)
                    },
                    onClick = {
                        if (complete) {
                            onPublish()
                        } else {
                            if (stepIndex == lesson.steps.lastIndex) {
                                completedStepIndices = lesson.steps.indices.toSet()
                                complete = true
                            } else {
                                completedStepIndices = completedStepIndices + stepIndex
                                stepIndex++
                            }
                        }
                    },
                    enabled = true,
                    shining = complete,
                    containerColor = if (complete) KolamAccent else null,
                    contentColor = if (complete) KolamButtonText else null,
                    modifier = Modifier
                        .weight(1f)
                        .height(PlaybackControlHeight),
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
    activePathIndex: Int,
    showPreview: Boolean,
    polygonPlaybacks: Map<Int, PolygonRevealData.Playback>,
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
                activePathIndex = activePathIndex,
                showPreview = showPreview,
                polygonPlaybacks = polygonPlaybacks
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
    activePathIndex: Int,
    showPreview: Boolean,
    polygonPlaybacks: Map<Int, PolygonRevealData.Playback>
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
                imagePaint = imagePaint,
                polygonPlaybacks = polygonPlaybacks
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
            progress = progress,
            activePathIndex = activePathIndex,
            polygonPlaybacks = polygonPlaybacks
        )
    }
}

private fun drawCompletedRangoliStep(
    canvas: AndroidCanvas,
    lesson: RangoliLesson,
    step: RangoliStep,
    imageRect: RectF,
    imagePaint: Paint,
    polygonPlaybacks: Map<Int, PolygonRevealData.Playback>
) {
    if (step.structuralLesson != null) {
        val structuralLesson = step.structuralLesson
        val polygonReveal = structuralLesson.polygonReveal
        if (polygonReveal != null) {
            drawPolygonArtwork(
                canvas = canvas,
                imageRect = imageRect,
                data = polygonReveal,
                playback = null,
                pathIndex = 0,
                progress = 1f,
                artwork = lesson.artwork
            )
        } else {
            structuralLesson.paths.indices.forEach { pathIndex ->
                drawBitmapThroughPath(
                    canvas = canvas,
                    bitmap = lesson.artwork,
                    imageRect = imageRect,
                    imagePaint = imagePaint,
                    step = structuralLesson,
                    pathIndex = pathIndex,
                    coordinateWidth = lesson.coordinateWidth,
                    coordinateHeight = lesson.coordinateHeight,
                    progress = 1f,
                    applyArtworkColorFilter = false
                )
            }
        }
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
    progress: Float,
    activePathIndex: Int,
    polygonPlaybacks: Map<Int, PolygonRevealData.Playback>
) {
    val structuralLesson = step.structuralLesson
    if (structuralLesson != null) {
        val polygonReveal = structuralLesson.polygonReveal
        for (pathIndex in 0..activePathIndex.coerceAtMost(structuralLesson.pathCount - 1)) {
            val pathProgress = if (pathIndex < activePathIndex) 1f else progress
            if (polygonReveal != null) {
                drawPolygonArtwork(
                    canvas = canvas,
                    imageRect = imageRect,
                    data = polygonReveal,
                    playback = polygonPlaybacks[structuralLesson.number],
                    pathIndex = pathIndex,
                    progress = pathProgress
                )
            } else {
                drawBitmapThroughPath(
                    canvas = canvas,
                    bitmap = lesson.tinted,
                    imageRect = imageRect,
                    imagePaint = imagePaint,
                    step = structuralLesson,
                    pathIndex = pathIndex,
                    coordinateWidth = lesson.coordinateWidth,
                    coordinateHeight = lesson.coordinateHeight,
                    progress = pathProgress,
                    applyArtworkColorFilter = false
                )
            }
        }
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
