package com.kolammaster.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Path
import org.json.JSONObject
import java.io.File
import java.io.IOException
import kotlin.math.hypot

internal data class LessonPoint(val x: Float, val y: Float)

internal data class LessonStroke(
    val width: Float,
    val segmentWidths: List<Float>,
    val nodes: List<LessonPoint>
)

internal data class LessonStep(
    val number: Int,
    val speed: Float,
    val strokes: List<LessonStroke>,
    val identificationColor: Int? = null,
    val identificationMask: Path? = null
) {
    val animationDurationMillis: Int
        get() = strokes.sumOf { (3000f / speed).toDouble() }
            .toInt()
            .coerceIn(500, 10_000)
}

internal data class SikkuLesson(
    val preview: Bitmap,
    val dots: Bitmap,
    val tinted: Bitmap,
    val coordinateWidth: Float,
    val coordinateHeight: Float,
    val steps: List<LessonStep>,
    val dotInformation: String?
) {
    companion object {
        fun load(directory: File): SikkuLesson {
            val preview = decodeImage(File(directory, "preview.webp"))
            val dots = decodeImage(File(directory, "dots.webp"))
            val tintedFile = File(directory, "tinted.webp")
            val tinted = if (tintedFile.isFile) decodeImage(tintedFile) else preview
            if (preview.width != dots.width || preview.height != dots.height ||
                preview.width != tinted.width || preview.height != tinted.height
            ) {
                throw IOException("Sikku lesson image dimensions do not match")
            }

            val root = JSONObject(File(directory, "path.json").readText(Charsets.UTF_8))
            val coordinateSpace = root.getJSONObject("coordinateSpace")
            val coordinateWidth = coordinateSpace.getDouble("width").toFloat()
            val coordinateHeight = coordinateSpace.getDouble("height").toFloat()
            require(coordinateWidth > 0f && coordinateHeight > 0f) {
                "Invalid path coordinate dimensions"
            }

            val lessonArray = root.getJSONArray("lessons")
            val steps = buildList {
                for (lessonIndex in 0 until lessonArray.length()) {
                    val lesson = lessonArray.getJSONObject(lessonIndex)
                    val speed = lesson.getDouble("speed").toFloat()
                    require(speed > 0f && speed.isFinite()) { "Invalid lesson speed" }
                    val strokes = mutableListOf<LessonStroke>()
                    val paths = lesson.getJSONArray("paths")
                    for (pathIndex in 0 until paths.length()) {
                        val path = paths.getJSONObject(pathIndex)
                        val pathStrokes = path.getJSONArray("strokes")
                        for (strokeIndex in 0 until pathStrokes.length()) {
                            val stroke = pathStrokes.getJSONObject(strokeIndex)
                            val width = stroke.getDouble("width").toFloat()
                            require(width > 0f && width.isFinite()) { "Invalid stroke width" }
                            val nodesArray = stroke.getJSONArray("nodes")
                            val nodes = buildList {
                                for (nodeIndex in 0 until nodesArray.length()) {
                                    val node = nodesArray.getJSONObject(nodeIndex)
                                    val x = node.getDouble("x").toFloat()
                                    val y = node.getDouble("y").toFloat()
                                    require(x.isFinite() && y.isFinite()) {
                                        "Invalid path node coordinates"
                                    }
                                    add(LessonPoint(x, y))
                                }
                            }
                            require(nodes.size >= 2) { "Path stroke has fewer than two nodes" }

                            val widthArray = stroke.optJSONArray("segmentWidths")
                            val segmentWidths = if (widthArray == null) {
                                emptyList()
                            } else {
                                buildList {
                                    for (widthIndex in 0 until widthArray.length()) {
                                        val segmentWidth = widthArray.getDouble(widthIndex).toFloat()
                                        require(segmentWidth > 0f && segmentWidth.isFinite()) {
                                            "Invalid segment width"
                                        }
                                        add(segmentWidth)
                                    }
                                }
                            }
                            strokes += LessonStroke(width, segmentWidths, nodes)
                        }
                    }
                    require(strokes.isNotEmpty()) { "Lesson has no drawing strokes" }
                    add(
                        LessonStep(
                            number = lesson.getInt("lesson"),
                            speed = speed,
                            strokes = strokes,
                            identificationColor = lesson.optString("identificationColor")
                                .takeIf(String::isNotBlank)
                                ?.let(::parseIdentificationColor)
                        )
                    )
                }
            }
            require(steps.isNotEmpty()) { "Path data has no lessons" }

            val preparedSteps = if (tintedFile.isFile) {
                prepareIdentificationMasks(
                    bitmap = tinted,
                    coordinateWidth = coordinateWidth,
                    coordinateHeight = coordinateHeight,
                    steps = steps
                )
            } else {
                steps
            }

            return SikkuLesson(
                preview = preview,
                dots = dots,
                tinted = tinted,
                coordinateWidth = coordinateWidth,
                coordinateHeight = coordinateHeight,
                steps = preparedSteps,
                dotInformation = readDotInformation(directory)
            )
        }

        private fun parseIdentificationColor(value: String): Int {
            require(Regex("""#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?""").matches(value)) {
                "Invalid lesson identification color: $value"
            }
            return Color.parseColor(value)
        }

        private fun prepareIdentificationMasks(
            bitmap: Bitmap,
            coordinateWidth: Float,
            coordinateHeight: Float,
            steps: List<LessonStep>
        ): List<LessonStep> {
            val identified = steps.mapIndexedNotNull { index, step ->
                step.identificationColor?.let { index to it }
            }
            if (identified.isEmpty()) return steps

            val masks = Array(steps.size) { Path() }
            val colors = identified.map { it.second }.toIntArray()
            val colorStepIndexes = identified.map { it.first }.toIntArray()
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val intersectionCandidates = ArrayList<Int>()

            for (y in 0 until bitmap.height) {
                var runStepIndex = -1
                var runStart = 0
                for (x in 0 until bitmap.width) {
                    val color = pixels[y * bitmap.width + x]
                    val matchedStepIndex = if (Color.alpha(color) >= MIN_IDENTIFICATION_ALPHA) {
                        findColorMatch(color, colors, colorStepIndexes)
                    } else {
                        -1
                    }
                    if (matchedStepIndex != runStepIndex) {
                        if (runStepIndex >= 0) {
                            masks[runStepIndex].addRect(
                                runStart.toFloat(),
                                y.toFloat(),
                                x.toFloat(),
                                (y + 1).toFloat(),
                                Path.Direction.CW
                            )
                        }
                        runStepIndex = matchedStepIndex
                        runStart = x
                    }
                    if (Color.alpha(color) >= MIN_IDENTIFICATION_ALPHA &&
                        isIntersectionCandidate(color)
                    ) {
                        intersectionCandidates += y * bitmap.width + x
                    }
                }
                if (runStepIndex >= 0) {
                    masks[runStepIndex].addRect(
                        runStart.toFloat(),
                        y.toFloat(),
                        bitmap.width.toFloat(),
                        (y + 1).toFloat(),
                        Path.Direction.CW
                    )
                }
            }

            for (stepIndex in colorStepIndexes) {
                val step = steps[stepIndex]
                for (pixelIndex in intersectionCandidates) {
                    val x = pixelIndex % bitmap.width
                    val y = pixelIndex / bitmap.width
                    if (step.containsImagePoint(
                            x = x + 0.5f,
                            y = y + 0.5f,
                            imageWidth = bitmap.width,
                            imageHeight = bitmap.height,
                            coordinateWidth = coordinateWidth,
                            coordinateHeight = coordinateHeight
                        )
                    ) {
                        masks[stepIndex].addRect(
                            x.toFloat(),
                            y.toFloat(),
                            (x + 1).toFloat(),
                            (y + 1).toFloat(),
                            Path.Direction.CW
                        )
                    }
                }
            }

            return steps.mapIndexed { index, step ->
                step.copy(
                    identificationMask = if (step.identificationColor == null) {
                        null
                    } else {
                        masks[index]
                    }
                )
            }
        }

        private fun findColorMatch(
            pixel: Int,
            stepColors: IntArray,
            stepIndexes: IntArray
        ): Int {
            var bestDistance = Int.MAX_VALUE
            var secondBestDistance = Int.MAX_VALUE
            var bestIndex = -1
            for (index in stepColors.indices) {
                val color = stepColors[index]
                val red = Color.red(pixel) - Color.red(color)
                val green = Color.green(pixel) - Color.green(color)
                val blue = Color.blue(pixel) - Color.blue(color)
                val distance = red * red + green * green + blue * blue
                if (distance < bestDistance) {
                    secondBestDistance = bestDistance
                    bestDistance = distance
                    bestIndex = stepIndexes[index]
                } else if (distance < secondBestDistance) {
                    secondBestDistance = distance
                }
            }
            return if (bestDistance <= MAX_COLOR_DISTANCE_SQUARED &&
                (secondBestDistance == Int.MAX_VALUE ||
                    secondBestDistance - bestDistance >= MIN_COLOR_DISTANCE_MARGIN_SQUARED)
            ) {
                bestIndex
            } else {
                -1
            }
        }

        private fun isIntersectionCandidate(color: Int): Boolean =
            isNearMarker(color, WHITE_INTERSECTION_COLOR) ||
                isNearMarker(color, LEGACY_GREEN_INTERSECTION_COLOR) ||
                isRedIntersectionCandidate(color)

        private fun isNearMarker(color: Int, marker: Int): Boolean {
            val red = Color.red(color) - Color.red(marker)
            val green = Color.green(color) - Color.green(marker)
            val blue = Color.blue(color) - Color.blue(marker)
            return red * red + green * green + blue * blue <= MAX_MARKER_DISTANCE_SQUARED
        }

        private fun isRedIntersectionCandidate(color: Int): Boolean {
            val red = Color.red(color)
            val green = Color.green(color)
            val blue = Color.blue(color)
            val redDelta = red - Color.red(RED_INTERSECTION_COLOR)
            val greenDelta = green - Color.green(RED_INTERSECTION_COLOR)
            val blueDelta = blue - Color.blue(RED_INTERSECTION_COLOR)
            val distance = redDelta * redDelta + greenDelta * greenDelta + blueDelta * blueDelta
            return red >= green + MIN_RED_DOMINANCE &&
                red >= blue + MIN_RED_DOMINANCE &&
                distance <= MAX_RED_DISTANCE_SQUARED
        }

        private fun LessonStep.containsImagePoint(
            x: Float,
            y: Float,
            imageWidth: Int,
            imageHeight: Int,
            coordinateWidth: Float,
            coordinateHeight: Float
        ): Boolean {
            val scaleX = imageWidth / coordinateWidth
            val scaleY = imageHeight / coordinateHeight
            val widthScale = hypot(scaleX, scaleY) / kotlin.math.sqrt(2f)
            for (stroke in strokes) {
                for (segmentIndex in 0 until stroke.nodes.lastIndex) {
                    val start = stroke.nodes[segmentIndex]
                    val end = stroke.nodes[segmentIndex + 1]
                    val startX = start.x * scaleX
                    val startY = start.y * scaleY
                    val endX = end.x * scaleX
                    val endY = end.y * scaleY
                    val dx = endX - startX
                    val dy = endY - startY
                    val lengthSquared = dx * dx + dy * dy
                    if (lengthSquared <= 0f) {
                        continue
                    } else {
                        val projection = (
                            ((x - startX) * dx + (y - startY) * dy) / lengthSquared
                            ).coerceIn(0f, 1f)
                        val distanceX = x - (startX + projection * dx)
                        val distanceY = y - (startY + projection * dy)
                        val segmentWidth =
                            stroke.segmentWidths.getOrNull(segmentIndex) ?: stroke.width
                        val radius = segmentWidth * widthScale / 2f + PIXEL_EDGE_TOLERANCE
                        if (distanceX * distanceX + distanceY * distanceY <= radius * radius) {
                            return true
                        }
                    }
                }
            }
            return false
        }

        private const val MIN_IDENTIFICATION_ALPHA = 128
        private const val MAX_COLOR_DISTANCE_SQUARED = 24 * 24 * 3
        private const val MIN_COLOR_DISTANCE_MARGIN_SQUARED = 12 * 12
        private const val MAX_MARKER_DISTANCE_SQUARED = 32 * 32 * 3
        private const val MIN_RED_DOMINANCE = 20
        private const val MAX_RED_DISTANCE_SQUARED = 140 * 140
        private const val PIXEL_EDGE_TOLERANCE = 1f
        private val RED_INTERSECTION_COLOR = Color.rgb(255, 48, 48)
        private val WHITE_INTERSECTION_COLOR = Color.WHITE
        private val LEGACY_GREEN_INTERSECTION_COLOR = Color.rgb(0, 255, 0)

        private fun readDotInformation(directory: File): String? =
            directory.listFiles()
                ?.filter { it.isFile && it.extension.equals("json", ignoreCase = true) &&
                    !it.name.equals("path.json", ignoreCase = true) }
                .orEmpty()
                .firstNotNullOfOrNull { file ->
                    JSONObject(file.readText(Charsets.UTF_8))
                        .optString("dotInformation")
                        .takeIf(String::isNotBlank)
                }

        private fun decodeImage(file: File): Bitmap =
            BitmapFactory.decodeFile(file.path)
                ?: throw IOException("Could not decode ${file.name}")
    }
}
