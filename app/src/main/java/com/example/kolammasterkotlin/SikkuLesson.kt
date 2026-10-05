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

internal data class LessonStep(
    val number: Int,
    val speed: Float,
    val strokes: List<LessonStroke>
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
                            strokes = strokes
                        )
                    )
                }
            }
            require(steps.isNotEmpty()) { "Path data has no lessons" }

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
