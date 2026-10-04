package com.kolammaster.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale

internal data class RangoliStep(
    val number: Int,
    val structuralLesson: LessonStep?,
    val fillBitmap: Bitmap?,
    val animationDurationMillis: Int
)

internal data class RangoliLesson(
    val preview: Bitmap,
    val artwork: Bitmap,
    val tinted: Bitmap,
    val coordinateWidth: Float,
    val coordinateHeight: Float,
    val steps: List<RangoliStep>
) {
    companion object {
        fun load(directory: File): RangoliLesson {
            val manifest = findManifest(directory)
            val totalSteps = manifest.getInt("totalSteps")
            require(totalSteps > 0) { "Rangoli manifest has no lesson steps" }

            val previewName = manifest.getString("previewImage")
            val preview = decodeImage(File(directory, previewName))
            val artworkFile = File(directory, "artwork.webp")
            val artwork = if (artworkFile.isFile) decodeImage(artworkFile) else preview
            requireSameDimensions(preview, artwork, artworkFile.name)
            val tinted = decodeImage(File(directory, "tinted.webp"))
            requireSameDimensions(preview, tinted, "tinted.webp")

            val fillSteps = manifest.optJSONArray("fillSteps").toIntSet().sorted()
            val fillImages = findFillImages(directory)
            val pathRoot = JSONObject(File(directory, "path.json").readText(Charsets.UTF_8))
            val coordinateSpace = pathRoot.getJSONObject("coordinateSpace")
            val coordinateWidth = coordinateSpace.getDouble("width").toFloat()
            val coordinateHeight = coordinateSpace.getDouble("height").toFloat()
            require(
                coordinateWidth > 0f && coordinateHeight > 0f &&
                    coordinateWidth.isFinite() && coordinateHeight.isFinite()
            ) { "Invalid Rangoli path coordinate dimensions" }

            val pathLessons = readPathLessons(pathRoot)
            val structuralSteps = pathLessons.mapNotNull { (number, lessonJson) ->
                if (number in fillSteps ||
                    lessonJson.optString("stepType").isFillType() ||
                    lessonJson.optString("type").isFillType() ||
                    !lessonJson.hasDrawablePath()
                ) {
                    null
                } else {
                    val inheritedSpeed = lessonJson.optDouble("speed", 1.0).toFloat()
                    require(inheritedSpeed > 0f && inheritedSpeed.isFinite()) {
                        "Invalid speed for step $number"
                    }
                    val paths = readPaths(lessonJson, inheritedSpeed)
                    val strokes = paths.flatMap { it.strokes }
                    require(strokes.isNotEmpty()) {
                        "Rangoli structural step $number has no strokes"
                    }
                    val computedDuration = paths.sumOf { path ->
                        path.durationMillis.toDouble()
                    }.toInt()
                    val duration = lessonJson.optInt("durationMs", computedDuration)
                    RangoliStep(
                        number = number,
                        structuralLesson = LessonStep(number, inheritedSpeed, strokes),
                        fillBitmap = null,
                        animationDurationMillis = duration.coerceIn(500, 10_000)
                    )
                }
            }
            val fillLessonSteps = fillSteps.map { number ->
                require(number in 1..totalSteps) {
                    "Rangoli fill step $number is outside the lesson step range"
                }
                val bitmap = fillImages[number]
                    ?: throw IOException("Missing Rangoli fill image for step $number")
                val fillBitmap = decodeImage(bitmap)
                requireSameDimensions(preview, fillBitmap, bitmap.name)
                RangoliStep(number, structuralLesson = null, fillBitmap = fillBitmap, 0)
            }
            val steps = structuralSteps + fillLessonSteps
            if (steps.size != totalSteps) {
                throw IOException(
                    "Rangoli metadata describes $totalSteps steps, " +
                        "but found ${structuralSteps.size} structural and " +
                        "${fillLessonSteps.size} fill steps"
                )
            }

            return RangoliLesson(
                preview = preview,
                artwork = artwork,
                tinted = tinted,
                coordinateWidth = coordinateWidth,
                coordinateHeight = coordinateHeight,
                steps = steps
            )
        }

        private fun findManifest(directory: File): JSONObject {
            val manifests = directory.listFiles()
                ?.filter { it.isFile && it.extension.equals("json", ignoreCase = true) &&
                    !it.name.equals("path.json", ignoreCase = true) }
                .orEmpty()
                .mapNotNull { file ->
                    val json = JSONObject(file.readText(Charsets.UTF_8))
                    if (json.has("totalSteps") && json.has("fillSteps")) json else null
                }
            if (manifests.size != 1) {
                throw IOException("Expected one Rangoli lesson manifest, found ${manifests.size}")
            }
            return manifests.single()
        }

        private fun readPathLessons(root: JSONObject): Map<Int, JSONObject> {
            val entries = root.optJSONArray("lessons")
                ?: root.optJSONArray("steps")
                ?: throw IOException("Rangoli path.json has no lessons or steps")
            return buildMap {
                for (index in 0 until entries.length()) {
                    val entry = entries.getJSONObject(index)
                    val number = entry.optInt(
                        "lessonNumber",
                        entry.optInt("lesson", entry.optInt("step", 0))
                    )
                    if (number > 0) put(number, entry)
                }
            }
        }

        private fun JSONObject.hasDrawablePath(): Boolean {
            if (has("nodes") || (optJSONArray("strokes")?.length() ?: 0) > 0) {
                return true
            }
            val paths = optJSONArray("paths") ?: return false
            return (0 until paths.length()).any { index ->
                val path = paths.getJSONObject(index)
                path.has("nodes") || (path.optJSONArray("strokes")?.length() ?: 0) > 0
            }
        }

        private data class PathData(
            val strokes: List<LessonStroke>,
            val durationMillis: Int
        )

        private fun readPaths(lesson: JSONObject, inheritedSpeed: Float): List<PathData> {
            val paths = lesson.optJSONArray("paths")
            val pathObjects = if (paths != null) {
                (0 until paths.length()).map { paths.getJSONObject(it) }
            } else {
                listOf(lesson)
            }
            return pathObjects.map { path ->
                val speed = path.optDouble("speed", inheritedSpeed.toDouble()).toFloat()
                require(speed > 0f && speed.isFinite()) { "Invalid Rangoli path speed" }
                val strokes = readPathStrokes(path)
                val duration = path.optInt(
                    "durationMs",
                    ((3000f / speed) * strokes.size).toInt()
                )
                require(duration > 0) { "Invalid Rangoli path duration" }
                PathData(strokes, duration)
            }
        }

        private fun readPathStrokes(path: JSONObject): List<LessonStroke> {
            val strokeArray = path.optJSONArray("strokes")
            val strokeObjects = if (strokeArray != null) {
                (0 until strokeArray.length()).map { strokeArray.getJSONObject(it) }
            } else if (path.has("nodes")) {
                listOf(path)
            } else {
                emptyList()
            }

            return strokeObjects.map { stroke ->
                val nodesArray = stroke.getJSONArray("nodes")
                val nodes = buildList {
                    for (nodeIndex in 0 until nodesArray.length()) {
                        val node = nodesArray.getJSONObject(nodeIndex)
                        val x = node.getDouble("x").toFloat()
                        val y = node.getDouble("y").toFloat()
                        require(x.isFinite() && y.isFinite()) {
                            "Rangoli path contains invalid coordinates"
                        }
                        add(LessonPoint(x, y))
                    }
                }
                require(nodes.size >= 2) { "Rangoli stroke has fewer than two nodes" }

                val width = stroke.optDouble("width", path.optDouble("width", 18.0)).toFloat()
                require(width > 0f && width.isFinite()) { "Invalid Rangoli stroke width" }
                val segmentWidthArray = stroke.optJSONArray("segmentWidths")
                val segmentWidths = segmentWidthArray.toFloatList()
                LessonStroke(width, segmentWidths, nodes)
            }
        }

        private fun findFillImages(directory: File): Map<Int, File> {
            val pattern = Regex("""step0*(\d+)\.webp""", RegexOption.IGNORE_CASE)
            return directory.listFiles()
                ?.filter { it.isFile }
                .orEmpty()
                .mapNotNull { file ->
                    val number = pattern.matchEntire(file.name)
                        ?.groupValues
                        ?.get(1)
                        ?.toIntOrNull()
                    number?.let { it to file }
                }
                .toMap()
        }

        private fun JSONArray?.toIntSet(): Set<Int> =
            if (this == null) {
                emptySet()
            } else {
                (0 until length()).map { getInt(it) }.toSet()
            }

        private fun JSONArray?.toFloatList(): List<Float> =
            if (this == null) {
                emptyList()
            } else {
                buildList {
                    for (index in 0 until length()) {
                        val width = getDouble(index).toFloat()
                        require(width > 0f && width.isFinite()) {
                            "Invalid Rangoli segment width"
                        }
                        add(width)
                    }
                }
            }

        private fun String?.isFillType(): Boolean =
            this?.lowercase(Locale.ROOT) == "fill"

        private fun requireSameDimensions(reference: Bitmap, bitmap: Bitmap, name: String) {
            if (reference.width != bitmap.width || reference.height != bitmap.height) {
                throw IOException("Rangoli image dimensions do not match: $name")
            }
        }

        private fun decodeImage(file: File): Bitmap =
            BitmapFactory.decodeFile(file.path)
                ?: throw IOException("Could not decode ${file.name}")
    }
}
