package com.kolammaster.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt

internal data class LessonPoint(val x: Float, val y: Float)

internal data class LessonStroke(
    val width: Float,
    val segmentWidths: List<Float>,
    val segmentCenterOffsets: List<Float>,
    val nodes: List<LessonPoint>,
    val speed: Float = 1.0f
) {
    val durationMillis: Int
        get() = when {
            speed <= 0.33f -> 6000
            speed <= 0.4f -> 5000
            speed <= 0.5f -> 4000
            speed <= 0.67f -> 3000
            speed <= 0.75f -> 2667
            speed <= 1.0f -> 2000
            speed <= 1.25f -> 1000
            speed <= 1.5f -> 500
            else -> (2000f / speed).roundToInt()
        }
}

internal data class LessonPath(val strokes: List<LessonStroke>)

internal data class LessonStep(
    val number: Int,
    val paths: List<LessonPath>,
    val polygonReveal: PolygonRevealData? = null
) {
    val strokes: List<LessonStroke>
        get() = paths.flatMap { it.strokes }
    val strokeCount: Int
        get() = strokes.size
    val animationDurationMillis: Int
        get() = strokes.sumOf { it.durationMillis }
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
            val revealFile = File(directory, "reveal.webp")
            val preview = if (revealFile.isFile) {
                decodeImage(revealFile)
            } else {
                decodeImage(File(directory, "preview.webp"))
            }
            val dots = decodeImage(File(directory, "dots.webp"))
            if (preview.width != dots.width || preview.height != dots.height) {
                throw IOException("Sikku lesson image dimensions do not match")
            }

            val root = JSONObject(File(directory, "path.json").readText(Charsets.UTF_8))
            val coordinateSpace = root.getJSONObject("coordinateSpace")
            val coordinateWidth = coordinateSpace.getDouble("width").toFloat()
            val coordinateHeight = coordinateSpace.getDouble("height").toFloat()
            require(
                coordinateWidth > 0f && coordinateHeight > 0f &&
                    coordinateWidth.isFinite() && coordinateHeight.isFinite()
            ) {
                "Invalid path coordinate dimensions"
            }
            val polygonDataByStep = readStepPolygonData(root)
            val yellowArtwork = if (polygonDataByStep.isEmpty()) {
                null
            } else {
                createYellowArtwork(preview)
            }

            val stepArray = root.optJSONArray("steps") ?: root.optJSONArray("lessons")
                ?: throw IOException("path.json has no steps or lessons")
            val steps = buildList {
                for (lessonIndex in 0 until stepArray.length()) {
                    val lesson = stepArray.getJSONObject(lessonIndex)
                    val stepNumber = lesson.optInt("step", lesson.optInt("lesson", lessonIndex + 1))
                    val paths = lesson.getJSONArray("paths")
                    val lessonPaths = buildList {
                        for (pathIndex in 0 until paths.length()) {
                            val path = paths.getJSONObject(pathIndex)
                            val pathStrokes = path.getJSONArray("strokes")
                            val strokes = mutableListOf<LessonStroke>()
                            for (strokeIndex in 0 until pathStrokes.length()) {
                                val stroke = pathStrokes.getJSONObject(strokeIndex)
                                val width = stroke.getDouble("width").toFloat()
                                require(width > 0f && width.isFinite()) { "Invalid stroke width" }
                                val speed = stroke.optDouble("speed", path.optDouble("speed", 1.0))
                                    .toFloat()
                                    .takeIf { it > 0f && it.isFinite() } ?: 1.0f
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
                                            val segmentWidth =
                                                widthArray.getDouble(widthIndex).toFloat()
                                            require(segmentWidth > 0f && segmentWidth.isFinite()) {
                                                "Invalid segment width"
                                            }
                                            add(segmentWidth)
                                        }
                                    }
                                }

                                val offsetArray = stroke.optJSONArray("segmentCenterOffsets")
                                val segmentCenterOffsets = if (offsetArray == null) {
                                    emptyList()
                                } else {
                                    buildList {
                                        for (offsetIndex in 0 until offsetArray.length()) {
                                            add(offsetArray.getDouble(offsetIndex).toFloat())
                                        }
                                    }
                                }

                                strokes += LessonStroke(
                                    width,
                                    segmentWidths,
                                    segmentCenterOffsets,
                                    nodes,
                                    speed
                                )
                            }
                            add(LessonPath(strokes))
                        }
                    }
                    val step = LessonStep(
                        number = stepNumber,
                        paths = lessonPaths
                    )
                    val polygonStep = polygonDataByStep[step.number]
                        ?.takeIf { it.polygons.isNotEmpty() }
                    add(
                        if (polygonStep == null) {
                            step
                        } else {
                            step.copy(
                                polygonReveal = PolygonRevealData.create(
                                    polygons = polygonStep.polygons,
                                    step = step,
                                    coordinateWidth = coordinateWidth,
                                    coordinateHeight = coordinateHeight,
                                    yellowArtwork = checkNotNull(yellowArtwork),
                                    connections = polygonStep.connections
                                )
                            )
                        }
                    )
                }
            }

            val tinted = preview

            val dotInformation = root.optString("dotInformation", root.optString("dots"))
                .takeIf { it.isNotBlank() }

            return SikkuLesson(
                preview = preview,
                dots = dots,
                tinted = tinted,
                coordinateWidth = coordinateWidth,
                coordinateHeight = coordinateHeight,
                steps = steps,
                dotInformation = dotInformation
            )
        }

        private fun decodeImage(file: File): Bitmap =
            BitmapFactory.decodeFile(file.path)
                ?: throw IOException("Could not decode ${file.name}")
    }
}
