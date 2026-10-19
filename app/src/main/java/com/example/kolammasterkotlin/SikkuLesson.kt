package com.kolammaster.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.io.File
import java.io.IOException

internal data class LessonPoint(val x: Float, val y: Float)

internal data class LessonStroke(
    val width: Float,
    val segmentWidths: List<Float>,
    val nodes: List<LessonPoint>
)

internal data class LessonPath(val strokes: List<LessonStroke>)

internal data class LessonStep(
    val number: Int,
    val paths: List<LessonPath>,
    val polygonReveal: PolygonRevealData? = null
) {
    val pathCount: Int
        get() = paths.size
    val animationDurationMillis: Int
        get() = BASE_PATH_DURATION_MILLIS
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

            val lessonArray = root.getJSONArray("lessons")
            val steps = buildList {
                for (lessonIndex in 0 until lessonArray.length()) {
                    val lesson = lessonArray.getJSONObject(lessonIndex)
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
                                strokes += LessonStroke(width, segmentWidths, nodes)
                            }
                            add(LessonPath(strokes))
                        }
                    }
                    require(lessonPaths.any { it.strokes.isNotEmpty() }) {
                        "Lesson has no drawing strokes"
                    }
                    val number = lesson.getInt("lesson")
                    val step = LessonStep(
                        number = number,
                        paths = lessonPaths
                    )
                    val polygonStep = polygonDataByStep[number]
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
            require(steps.isNotEmpty()) { "Path data has no lessons" }

            val tintedFile = File(directory, "tinted.webp")
            val tinted = if (steps.any { it.polygonReveal == null } && tintedFile.isFile) {
                decodeImage(tintedFile).also {
                    if (preview.width != it.width || preview.height != it.height) {
                        throw IOException("Sikku lesson image dimensions do not match")
                    }
                }
            } else {
                preview
            }

            return SikkuLesson(
                preview = preview,
                dots = dots,
                tinted = tinted,
                coordinateWidth = coordinateWidth,
                coordinateHeight = coordinateHeight,
                steps = steps,
                dotInformation = readDotInformation(directory)
            )
        }

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
