package com.kolammaster.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale

internal data class RangoliStructuralStep(
    val number: Int,
    val paths: List<LessonPath>,
    val polygonReveal: RangoliPolygonRevealData? = null
) {
    val strokes: List<LessonStroke>
        get() = paths.flatMap { it.strokes }
    val strokeCount: Int
        get() = strokes.size
    val animationDurationMillis: Int
        get() = strokes.sumOf { it.durationMillis }
}

internal data class RangoliStep(
    val number: Int,
    val structuralLesson: RangoliStructuralStep?,
    val fillBitmap: Bitmap?,
    val animationDurationMillis: Int
)

internal data class RangoliLesson(
    val preview: Bitmap,
    val artwork: Bitmap,
    val tinted: Bitmap,
    val coordinateWidth: Float,
    val coordinateHeight: Float,
    val steps: List<RangoliStep>,
    val dotInformation: String? = null
) {
    companion object {
        fun load(directory: File, catalogueDotInformation: String? = null): RangoliLesson {
            val manifest = findManifest(directory)
            val totalSteps = manifest.getInt("totalSteps")
            require(totalSteps > 0) { "Rangoli manifest has no lesson steps" }

            val previewName = manifest.optString("previewImage", "preview.webp")
            val preview = decodeImage(File(directory, previewName))

            val revealArtworkFile = File(directory, "reveal.webp")
            require(revealArtworkFile.isFile) {
                "Rangoli lesson is missing reveal.webp"
            }
            val revealArtwork = decodeImage(revealArtworkFile)
            requireSameDimensions(preview, revealArtwork, revealArtworkFile.name)
            val artwork = revealArtwork

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

            val polygonDataByStep = readRangoliStepPolygonData(pathRoot)
            val yellowArtwork = if (polygonDataByStep.isEmpty()) {
                null
            } else {
                createRangoliYellowArtwork(revealArtwork)
            }
            val pathLessons = readPathLessons(pathRoot)
            val structuralSteps = pathLessons.mapNotNull { (number, lessonJson) ->
                if (number in fillSteps ||
                    lessonJson.optString("stepType").isFillType() ||
                    lessonJson.optString("type").isFillType() ||
                    !lessonJson.hasDrawablePath()
                ) {
                    null
                } else {
                    val paths = readPaths(lessonJson)
                    require(paths.any { it.strokes.isNotEmpty() }) {
                        "Rangoli structural step $number has no strokes"
                    }
                    val structuralLesson = RangoliStructuralStep(
                        number = number,
                        paths = paths
                    )
                    val polygonStep = polygonDataByStep[number]
                        ?.takeIf { it.polygons.isNotEmpty() }
                    val lessonWithPolygons = if (polygonStep == null) {
                        structuralLesson
                    } else {
                        structuralLesson.copy(
                            polygonReveal = RangoliPolygonRevealData.create(
                                polygons = polygonStep.polygons,
                                step = structuralLesson,
                                coordinateWidth = coordinateWidth,
                                coordinateHeight = coordinateHeight,
                                yellowArtwork = checkNotNull(yellowArtwork),
                                connections = polygonStep.connections
                            )
                        )
                    }
                    RangoliStep(
                        number = number,
                        structuralLesson = lessonWithPolygons,
                        fillBitmap = null,
                        animationDurationMillis = 2000
                    )
                }
            }
            val hasPolygonBackedSteps = structuralSteps.any {
                it.structuralLesson?.polygonReveal != null
            }
            val hasLegacyStructuralSteps = structuralSteps.any {
                it.structuralLesson?.polygonReveal == null
            }
            val tintedFile = File(directory, "tinted.webp")
            val tinted = if (tintedFile.isFile) {
                decodeImage(tintedFile).also { requireSameDimensions(preview, it, tintedFile.name) }
            } else {
                revealArtwork
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

            val dotInformation = catalogueDotInformation
                ?: pathRoot.optString("dotInformation", pathRoot.optString("dots"))
                    .takeIf { it.isNotBlank() }

            return RangoliLesson(
                preview = preview,
                artwork = artwork,
                tinted = tinted,
                coordinateWidth = coordinateWidth,
                coordinateHeight = coordinateHeight,
                steps = steps,
                dotInformation = dotInformation
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
            val entries = root.optJSONArray("steps")
                ?: root.optJSONArray("lessons")
                ?: throw IOException("Rangoli path.json has no steps or lessons")
            return buildMap {
                for (index in 0 until entries.length()) {
                    val entry = entries.getJSONObject(index)
                    val number = entry.optInt(
                        "step",
                        entry.optInt("lesson", entry.optInt("lessonNumber", 0))
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

        private fun readPaths(lesson: JSONObject): List<LessonPath> {
            val paths = lesson.optJSONArray("paths")
            val pathObjects = if (paths != null) {
                (0 until paths.length()).map { paths.getJSONObject(it) }
            } else {
                listOf(lesson)
            }
            return pathObjects.map { path ->
                val strokes = readPathStrokes(path)
                LessonPath(strokes)
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
                val speed = stroke.optDouble("speed", path.optDouble("speed", 1.0))
                    .toFloat()
                    .takeIf { it > 0f && it.isFinite() } ?: 1.0f

                val segmentWidthArray = stroke.optJSONArray("segmentWidths")
                val segmentWidths = segmentWidthArray.toSegmentWidthList()
                val offsetArray = stroke.optJSONArray("segmentCenterOffsets")
                val segmentCenterOffsets = offsetArray.toSegmentOffsetList()
                LessonStroke(width, segmentWidths, segmentCenterOffsets, nodes, speed)
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

        private fun JSONArray?.toSegmentWidthList(): List<Float> =
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

        private fun JSONArray?.toSegmentOffsetList(): List<Float> =
            if (this == null) {
                emptyList()
            } else {
                buildList {
                    for (index in 0 until length()) {
                        val offset = getDouble(index).toFloat()
                        require(offset.isFinite()) {
                            "Invalid Rangoli segment center offset"
                        }
                        add(offset)
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
