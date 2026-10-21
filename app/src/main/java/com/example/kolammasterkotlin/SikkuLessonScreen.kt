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
import android.os.SystemClock
import android.util.Log
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
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.res.stringResource
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
    var completedStepIndices by remember { mutableStateOf(emptySet<Int>()) }
    var replayRequest by remember { mutableIntStateOf(0) }
    var playbackSpeed by rememberSaveable { mutableFloatStateOf(1f) }
    var activePathIndex by remember(stepIndex, replayRequest) { mutableIntStateOf(0) }
    var animatedStepIndex by remember { mutableIntStateOf(Int.MIN_VALUE) }
    var animatedReplayRequest by remember { mutableIntStateOf(Int.MIN_VALUE) }
    val progress = remember(stepIndex, replayRequest) { Animatable(0f) }
    val polygonPlaybacks = remember(lesson) {
        lesson.steps.mapNotNull { step ->
            step.polygonReveal?.let { step.number to PolygonRevealData.Playback(it) }
        }.toMap()
    }

    LaunchedEffect(started, complete, stepIndex, replayRequest, playbackSpeed) {
        if (started && !complete && stepIndex >= 0) {
            if (animatedStepIndex != stepIndex || animatedReplayRequest != replayRequest) {
                progress.snapTo(0f)
                activePathIndex = 0
                lesson.steps.getOrNull(stepIndex)?.let { step ->
                    polygonPlaybacks[step.number]?.reset()
                }
                animatedStepIndex = stepIndex
                animatedReplayRequest = replayRequest
            }
            val step = lesson.steps[stepIndex]
            val loopStartedAt = SystemClock.elapsedRealtime()
            while (activePathIndex < step.pathCount) {
                val pathIndex = activePathIndex
                val durationMillis = scaledAnimationDuration(
                    step.animationDurationMillis,
                    playbackSpeed,
                    progress.value
                )
                val pathStartedAt = SystemClock.elapsedRealtime()
                Log.d(
                    "PathTimingDebug",
                    "Sikku start stepIndex=$stepIndex pathIndex=$pathIndex " +
                        "activePathIndex=$activePathIndex pathCount=${step.pathCount} " +
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
                    "Sikku path finished stepIndex=$stepIndex pathIndex=$pathIndex " +
                        "elapsedMillis=${SystemClock.elapsedRealtime() - pathStartedAt}"
                )
                if (activePathIndex + 1 < step.pathCount) progress.snapTo(0f)
                activePathIndex++
            }
            Log.d(
                "PathTimingDebug",
                "Sikku loop finished stepIndex=$stepIndex pathCount=${step.pathCount} " +
                    "elapsedMillis=${SystemClock.elapsedRealtime() - loopStartedAt}"
            )
        } else if (started && !complete) {
            progress.snapTo(0f)
            activePathIndex = 0
            animatedStepIndex = Int.MIN_VALUE
        } else if (!started) {
            progress.snapTo(0f)
            activePathIndex = 0
        } else {
            progress.snapTo(1f)
            activePathIndex = lesson.steps.getOrNull(stepIndex)?.pathCount ?: 0
        }
    }

    Column(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val inDotsStage = started && !complete && stepIndex < 0
        LessonStatusRow(
            text = when {
                complete -> stringResource(R.string.kolam_completed)
                !started -> stringResource(R.string.preview)
                inDotsStage -> dotsOrderStatusText(
                    lesson.dotInformation,
                    stringResource(R.string.dots_order_heading)
                )
                else -> stringResource(
                    R.string.step_progress,
                    lesson.steps[stepIndex].number,
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
            LessonArtwork(
                lesson = lesson,
                completedSteps = if (complete) {
                    lesson.steps
                } else {
                    lesson.steps.filterIndexed { index, _ -> index in completedStepIndices }
                },
                currentStep = if (started && !complete && stepIndex >= 0) {
                    lesson.steps[stepIndex]
                } else {
                    null
                },
                progress = progress.value,
                activePathIndex = activePathIndex,
                showPreview = !started,
                showDots = started,
                polygonPlaybacks = polygonPlaybacks,
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
                            stepIndex = (lesson.steps.lastIndex - 1).coerceAtLeast(-1)
                            completedStepIndices = (0 until stepIndex).toSet()
                        } else {
                            stepIndex = (stepIndex - 1).coerceAtLeast(-1)
                            completedStepIndices = completedStepIndices
                                .filter { it < stepIndex }
                                .toSet()
                        }
                    },
                    enabled = complete || stepIndex >= 0,
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
                            stepIndex = -1
                            completedStepIndices = emptySet()
                        } else {
                            completedStepIndices = completedStepIndices
                                .filter { it < stepIndex }
                                .toSet()
                            replayRequest++
                        }
                    },
                    enabled = complete || stepIndex >= 0,
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
                            if (stepIndex < 0) {
                                stepIndex = 0
                            } else if (stepIndex == lesson.steps.lastIndex) {
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
private fun LessonArtwork(
    lesson: SikkuLesson,
    completedSteps: List<LessonStep>,
    currentStep: LessonStep?,
    progress: Float,
    activePathIndex: Int,
    showPreview: Boolean,
    showDots: Boolean,
    polygonPlaybacks: Map<Int, PolygonRevealData.Playback>,
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
                activePathIndex = activePathIndex,
                showPreview = showPreview,
                showDots = showDots,
                polygonPlaybacks = polygonPlaybacks
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
    activePathIndex: Int,
    showPreview: Boolean,
    showDots: Boolean,
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

    if (showPreview) {
        canvas.drawBitmap(lesson.preview, null, imageRect, imagePaint)
    } else {
        canvas.drawRect(imageRect, Paint().apply { color = Color.BLACK })
    }

    for (completedStep in completedSteps) {
        val polygonReveal = completedStep.polygonReveal
        if (polygonReveal != null) {
            drawPolygonArtwork(
                canvas = canvas,
                imageRect = imageRect,
                data = polygonReveal,
                playback = null,
                pathIndex = 0,
                progress = 1f,
                artwork = lesson.preview,
                colorFilter = artworkColorFilter
            )
        } else {
            completedStep.paths.indices.forEach { pathIndex ->
                drawBitmapThroughPath(
                    canvas = canvas,
                    bitmap = lesson.preview,
                    imageRect = imageRect,
                    imagePaint = imagePaint,
                    coordinateWidth = lesson.coordinateWidth,
                    coordinateHeight = lesson.coordinateHeight,
                    step = completedStep,
                    pathIndex = pathIndex,
                    progress = 1f,
                    applyArtworkColorFilter = true
                )
            }
        }
    }

    if (currentStep != null && currentStep.pathCount > 0) {
        val polygonReveal = currentStep.polygonReveal
        for (pathIndex in 0..activePathIndex.coerceAtMost(currentStep.pathCount - 1)) {
            val pathProgress = if (pathIndex < activePathIndex) 1f else progress
            if (polygonReveal != null) {
                drawPolygonArtwork(
                    canvas = canvas,
                    imageRect = imageRect,
                    data = polygonReveal,
                    playback = polygonPlaybacks[currentStep.number],
                    pathIndex = pathIndex,
                    progress = pathProgress
                )
            } else {
                drawBitmapThroughPath(
                    canvas = canvas,
                    bitmap = lesson.tinted,
                    imageRect = imageRect,
                    imagePaint = imagePaint,
                    coordinateWidth = lesson.coordinateWidth,
                    coordinateHeight = lesson.coordinateHeight,
                    step = currentStep,
                    pathIndex = pathIndex,
                    progress = pathProgress,
                    applyArtworkColorFilter = true
                )
            }
        }
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
    pathIndex: Int,
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
        pathIndex = pathIndex,
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
    pathIndex: Int,
    progress: Float
): Path? {
    val scaleX = imageRect.width() / coordinateWidth
    val scaleY = imageRect.height() / coordinateHeight
    val widthScale = hypot(scaleX, scaleY) / kotlin.math.sqrt(2f)
    var totalLength = 0f
    for (stroke in step.paths.getOrNull(pathIndex)?.strokes.orEmpty()) {
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

    for (stroke in step.paths.getOrNull(pathIndex)?.strokes.orEmpty()) {
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
