package com.kolammaster.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.math.BigDecimal
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

internal data class LessonPolygon(
    val vertices: List<LessonPoint>,
    val id: String? = null,
    val hole: Boolean = false
)

internal data class LessonConnectionEndpoint(
    val polygonId: String,
    val vertexIndex: Int,
    val point: LessonPoint
)

internal data class LessonConnection(
    val stepNumber: Int,
    val id: String?,
    val from: LessonConnectionEndpoint,
    val to: LessonConnectionEndpoint,
    val revealAtFirstCrossing: Boolean = false
)

internal data class LessonPolygonStep(
    val polygons: List<LessonPolygon>,
    val connections: List<LessonConnection>
)

internal class PolygonRevealData private constructor(
    val yellowArtwork: Bitmap,
    val regionPath: Path,
    val strokes: List<LessonStroke>,
    val coordinateWidth: Float,
    val coordinateHeight: Float,
    val connections: List<LessonConnection>
) {
    companion object {
        fun create(
            polygons: List<LessonPolygon>,
            step: LessonStep,
            coordinateWidth: Float,
            coordinateHeight: Float,
            yellowArtwork: Bitmap,
            connections: List<LessonConnection> = emptyList()
        ): PolygonRevealData {
            require(polygons.isNotEmpty()) { "Polygon step has no regions" }
            require(coordinateWidth > 0f && coordinateHeight > 0f) {
                "Invalid path coordinate dimensions"
            }

            val allStrokes = step.paths.flatMap { it.strokes }

            val regionPath = Path().apply { fillType = Path.FillType.WINDING }
            polygons.forEach { polygon ->
                require(polygon.vertices.size >= 3) {
                    "Polygon region has fewer than three vertices"
                }
                require(polygon.vertices.all { point ->
                    point.x in 0f..yellowArtwork.width.toFloat() &&
                        point.y in 0f..yellowArtwork.height.toFloat()
                }) { "Polygon region is outside the source image" }
                val signedArea = polygon.vertices.indices.sumOf { index ->
                    val point = polygon.vertices[index]
                    val next = polygon.vertices[(index + 1) % polygon.vertices.size]
                    (point.x.toDouble() * next.y - next.x.toDouble() * point.y)
                }
                require(abs(signedArea) > 0.001) { "Polygon region has no area" }
                val orderedVertices = if (signedArea > 0.0) {
                    polygon.vertices
                } else {
                    polygon.vertices.asReversed()
                }
                val path = Path().apply {
                    orderedVertices.forEachIndexed { index, point ->
                        if (index == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
                    }
                    close()
                }
                regionPath.addPath(path)
            }

            // Include offset stroke lines in regionPath to prevent boundary clipping
            val routeScaleX = yellowArtwork.width.toDouble() / coordinateWidth
            val routeScaleY = yellowArtwork.height.toDouble() / coordinateHeight
            allStrokes.forEach { stroke ->
                for (index in 0 until stroke.nodes.lastIndex) {
                    val start = stroke.nodes[index]
                    val end = stroke.nodes[index + 1]
                    val centerOffset = stroke.segmentCenterOffsets.getOrNull(index)?.toDouble() ?: 0.0
                    val rawDx = (end.x - start.x).toDouble() * routeScaleX
                    val rawDy = (end.y - start.y).toDouble() * routeScaleY
                    val len = hypot(rawDx, rawDy)
                    if (len > 0.0) {
                        val nx = -rawDy / len
                        val ny = rawDx / len
                        val x1 = (start.x.toDouble() * routeScaleX) + nx * centerOffset
                        val y1 = (start.y.toDouble() * routeScaleY) + ny * centerOffset
                        val x2 = (end.x.toDouble() * routeScaleX) + nx * centerOffset
                        val y2 = (end.y.toDouble() * routeScaleY) + ny * centerOffset
                        val p = Path().apply {
                            moveTo(x1.toFloat(), y1.toFloat())
                            lineTo(x2.toFloat(), y2.toFloat())
                        }
                        regionPath.addPath(p)
                    }
                }
            }

            return PolygonRevealData(
                yellowArtwork,
                regionPath,
                allStrokes,
                coordinateWidth,
                coordinateHeight,
                connections
            )
        }
    }

    internal class Playback(private val data: PolygonRevealData) {
        fun reset() {}

        fun pathAt(pathIndex: Int, progress: Float): Path? {
            val stroke = data.strokes.getOrNull(pathIndex) ?: return null
            if (stroke.nodes.size < 2) return null

            val routeScaleX = data.yellowArtwork.width.toDouble() / data.coordinateWidth
            val routeScaleY = data.yellowArtwork.height.toDouble() / data.coordinateHeight
            val widthScale = hypot(routeScaleX, routeScaleY) / kotlin.math.sqrt(2.0)

            var totalLength = 0.0
            for (index in 0 until stroke.nodes.lastIndex) {
                val start = stroke.nodes[index]
                val end = stroke.nodes[index + 1]
                val dx = (end.x - start.x).toDouble() * routeScaleX
                val dy = (end.y - start.y).toDouble() * routeScaleY
                totalLength += hypot(dx, dy)
            }
            if (totalLength <= 0.0) return null

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

            for (segmentIndex in 0 until stroke.nodes.lastIndex) {
                if (remaining <= 0.0) break

                val start = stroke.nodes[segmentIndex]
                val end = stroke.nodes[segmentIndex + 1]
                val rawDx = (end.x - start.x).toDouble() * routeScaleX
                val rawDy = (end.y - start.y).toDouble() * routeScaleY
                val segmentLength = hypot(rawDx, rawDy)
                if (segmentLength <= 0.0) continue

                val visibleLength = min(remaining, segmentLength)
                val fraction = visibleLength / segmentLength
                val visibleEndX = (start.x.toDouble() * routeScaleX) + ((end.x.toDouble() * routeScaleX) - (start.x.toDouble() * routeScaleX)) * fraction
                val visibleEndY = (start.y.toDouble() * routeScaleY) + ((end.y.toDouble() * routeScaleY) - (start.y.toDouble() * routeScaleY)) * fraction

                val centerOffset = stroke.segmentCenterOffsets.getOrNull(segmentIndex)?.toDouble() ?: 0.0
                val segmentWidth = stroke.segmentWidths.getOrNull(segmentIndex) ?: stroke.width
                val scaledWidth = (segmentWidth * widthScale) - 2.0
                if (scaledWidth <= 0.0) {
                    remaining -= visibleLength
                    continue
                }
                strokePaint.strokeWidth = scaledWidth.toFloat()

                val startXCoord = start.x.toDouble() * routeScaleX
                val startYCoord = start.y.toDouble() * routeScaleY

                val (shiftedStartX, shiftedStartY) = if (abs(centerOffset) > 0.001 && segmentLength > 0.0) {
                    val nx = -rawDy / segmentLength
                    val ny = rawDx / segmentLength
                    (startXCoord + nx * centerOffset) to (startYCoord + ny * centerOffset)
                } else {
                    startXCoord to startYCoord
                }

                val (shiftedEndX, shiftedEndY) = if (abs(centerOffset) > 0.001 && segmentLength > 0.0) {
                    val nx = -rawDy / segmentLength
                    val ny = rawDx / segmentLength
                    val partialDx = visibleEndX - startXCoord
                    val partialDy = visibleEndY - startYCoord
                    (startXCoord + partialDx + nx * centerOffset) to (startYCoord + partialDy + ny * centerOffset)
                } else {
                    visibleEndX to visibleEndY
                }

                segmentPath.reset()
                segmentPath.moveTo(shiftedStartX.toFloat(), shiftedStartY.toFloat())
                segmentPath.lineTo(shiftedEndX.toFloat(), shiftedEndY.toFloat())

                segmentFill.reset()
                strokePaint.getFillPath(segmentPath, segmentFill)

                if (hasMask) {
                    maskPath.op(segmentFill, Path.Op.UNION)
                } else {
                    maskPath.set(segmentFill)
                    hasMask = true
                }

                remaining -= visibleLength
            }
            return maskPath.takeIf { hasMask }
        }
    }
}

internal fun createYellowArtwork(source: Bitmap): Bitmap {
    var nonOpaquePixels = 0L
    val sourceRow = IntArray(source.width)
    for (y in 0 until source.height) {
        source.getPixels(sourceRow, 0, source.width, 0, y, source.width, 1)
        nonOpaquePixels += sourceRow.count { Color.alpha(it) < 255 }
    }
    val totalPixels = source.width.toLong() * source.height
    val hasTransparency = nonOpaquePixels >= (totalPixels / 100).coerceAtLeast(1) ||
        Color.alpha(source.getPixel(0, 0)) < 250 ||
        Color.alpha(source.getPixel(source.width - 1, 0)) < 250 ||
        Color.alpha(source.getPixel(0, source.height - 1)) < 250 ||
        Color.alpha(source.getPixel(source.width - 1, source.height - 1)) < 250

    val edgeStride = maxOf(1, maxOf(source.width, source.height) / 256)
    var backgroundLuminance = 0L
    var backgroundSamples = 0
    for (x in 0 until source.width step edgeStride) {
        backgroundLuminance += luminance(source.getPixel(x, 0))
        backgroundLuminance += luminance(source.getPixel(x, source.height - 1))
        backgroundSamples += 2
    }
    for (y in edgeStride until source.height - 1 step edgeStride) {
        backgroundLuminance += luminance(source.getPixel(0, y))
        backgroundLuminance += luminance(source.getPixel(source.width - 1, y))
        backgroundSamples += 2
    }
    val darkBackground = backgroundLuminance / backgroundSamples < 128
    val yellow = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
    val yellowRow = IntArray(source.width)
    for (y in 0 until source.height) {
        source.getPixels(sourceRow, 0, source.width, 0, y, source.width, 1)
        for (x in 0 until source.width) {
            val pixel = sourceRow[x]
            val alpha = if (hasTransparency) {
                Color.alpha(pixel)
            } else {
                val brightness = luminance(pixel)
                if (darkBackground) brightness else 255 - brightness
            }
            yellowRow[x] = Color.argb(alpha, 255, 255, 0)
        }
        yellow.setPixels(yellowRow, 0, source.width, 0, y, source.width, 1)
    }
    return yellow
}

private fun luminance(color: Int): Int =
    (0.2126f * Color.red(color) + 0.7152f * Color.green(color) +
        0.0722f * Color.blue(color) + 0.5f).toInt().coerceIn(0, 255)

internal fun readStepPolygonData(root: JSONObject): Map<Int, LessonPolygonStep> {
    val polygonValue = root.opt("polygons") ?: return emptyMap()
    if (polygonValue == JSONObject.NULL) return emptyMap()
    val entries = polygonValue as? JSONArray
        ?: throw IOException("path.json polygons field is not an array")
    val polygonsByStep = linkedMapOf<Int, MutableList<LessonPolygon>>()
    for (entryIndex in 0 until entries.length()) {
        val entry = entries.optJSONObject(entryIndex)
            ?: throw IOException("Polygon entry $entryIndex is not an object")
        val stepNumber = sequenceOf("lesson", "lessonNumber", "step", "stepNumber", "teachingStep")
            .firstNotNullOfOrNull { key ->
                if (entry.has(key)) entry.optInt(key).takeIf { it > 0 } else null
            } ?: throw IOException("Polygon entry $entryIndex has no valid teaching step")

        val regionArray = entry.optJSONArray("regions")
            ?: entry.optJSONArray("polygons")
            ?: if (entry.has("vertices") || entry.has("points")) {
                JSONArray().put(entry)
            } else {
                throw IOException("Polygon entry for step $stepNumber has no regions")
            }
        if (regionArray.length() == 0) {
            throw IOException("Polygon entry for step $stepNumber has no regions")
        }

        val regions = polygonsByStep.getOrPut(stepNumber) { mutableListOf() }
        val firstRegionItem = regionArray.opt(0)
        val isDirectVertexList = when (firstRegionItem) {
            is JSONObject -> firstRegionItem.has("x") && firstRegionItem.has("y")
            is JSONArray -> firstRegionItem.opt(0) is Number
            else -> false
        }
        val regionEntries = if (isDirectVertexList) {
            listOf(regionArray)
        } else {
            (0 until regionArray.length()).map { regionArray.get(it) }
        }
        for ((regionIndex, region) in regionEntries.withIndex()) {
            val (vertices, polygonId, isHole) = when (region) {
                is JSONObject -> Triple(
                    region.optJSONArray("vertices")
                        ?: region.optJSONArray("points")
                        ?: region.optJSONArray("coordinates")
                        ?: throw IOException("Polygon region $regionIndex has no vertices"),
                    jsonIdentifier(region.opt("id")),
                    region.opt("hole") as? Boolean ?: false
                )
                is JSONArray -> Triple(region, null, false)
                else -> throw IOException(
                    "Polygon region $regionIndex for step $stepNumber is invalid"
                )
            }
            if (vertices.length() < 3) {
                throw IOException("Polygon region $regionIndex has fewer than three vertices")
            }
            val points = buildList {
                for (vertexIndex in 0 until vertices.length()) {
                    val vertex = vertices.get(vertexIndex)
                    val point = when (vertex) {
                        is JSONObject -> LessonPoint(
                            vertex.getDouble("x").toFloat(),
                            vertex.getDouble("y").toFloat()
                        )
                        is JSONArray -> LessonPoint(
                            vertex.getDouble(0).toFloat(),
                            vertex.getDouble(1).toFloat()
                        )
                        else -> throw IOException(
                            "Polygon vertex $vertexIndex for step $stepNumber is invalid"
                        )
                    }
                    if (!point.x.isFinite() || !point.y.isFinite()) {
                        throw IOException("Polygon vertex has invalid coordinates")
                    }
                    add(point)
                }
            }
            regions += LessonPolygon(points, polygonId, isHole)
        }
    }

    val connectionsByStep = linkedMapOf<Int, MutableList<LessonConnection>>()
    val connectionGroups = root.optJSONArray("connections")
    if (connectionGroups != null) {
        for (groupIndex in 0 until connectionGroups.length()) {
            val group = connectionGroups.optJSONObject(groupIndex) ?: continue
            val stepNumber = exactPositiveInt(group.opt("step")) ?: continue
            val stepPolygons = polygonsByStep[stepNumber] ?: continue
            val connectionEntries = group.optJSONArray("connections") ?: continue
            for (connectionIndex in 0 until connectionEntries.length()) {
                val entry = connectionEntries.optJSONObject(connectionIndex) ?: continue
                val from = resolveConnectionEndpoint(entry.optJSONObject("from"), stepPolygons)
                    ?: continue
                val to = resolveConnectionEndpoint(entry.optJSONObject("to"), stepPolygons)
                    ?: continue
                connectionsByStep.getOrPut(stepNumber) { mutableListOf() } += LessonConnection(
                    stepNumber = stepNumber,
                    id = jsonIdentifier(entry.opt("id")),
                    from = from,
                    to = to,
                    revealAtFirstCrossing = entry.optBoolean("revealAtFirstCrossing", false)
                )
            }
        }
    }

    return polygonsByStep.mapValues { (stepNumber, polygons) ->
        LessonPolygonStep(
            polygons = polygons.toList(),
            connections = connectionsByStep[stepNumber]?.toList().orEmpty()
        )
    }
}

private fun resolveConnectionEndpoint(
    endpoint: JSONObject?,
    polygons: List<LessonPolygon>
): LessonConnectionEndpoint? {
    endpoint ?: return null
    val polygonId = jsonIdentifier(endpoint.opt("polygonId")) ?: return null
    val vertexIndex = exactNonNegativeInt(endpoint.opt("vertexIndex")) ?: return null
    val matches = polygons.filter { it.id == polygonId }
    if (matches.size != 1) return null
    val point = matches.single().vertices.getOrNull(vertexIndex) ?: return null
    return LessonConnectionEndpoint(polygonId, vertexIndex, point)
}

private fun jsonIdentifier(value: Any?): String? =
    when (value) {
        is String -> value.takeIf(String::isNotBlank)
        is Number -> try {
            BigDecimal(value.toString()).stripTrailingZeros().toPlainString()
        } catch (_: NumberFormatException) {
            null
        }
        else -> null
    }

private fun exactPositiveInt(value: Any?): Int? =
    exactNonNegativeInt(value)?.takeIf { it > 0 }

private fun exactNonNegativeInt(value: Any?): Int? {
    if (value !is Number) return null
    return try {
        BigDecimal(value.toString()).intValueExact().takeIf { it >= 0 }
    } catch (_: ArithmeticException) {
        null
    } catch (_: NumberFormatException) {
        null
    }
}

internal fun drawPolygonArtwork(
    canvas: Canvas,
    imageRect: RectF,
    data: PolygonRevealData,
    playback: PolygonRevealData.Playback?,
    pathIndex: Int,
    progress: Float,
    artwork: Bitmap = data.yellowArtwork,
    colorFilter: ColorFilter? = null
) {
    val revealPath = if (playback == null) null else playback.pathAt(pathIndex, progress) ?: return

    val saveCount = canvas.save()
    canvas.translate(imageRect.left, imageRect.top)
    canvas.scale(
        imageRect.width() / artwork.width,
        imageRect.height() / artwork.height
    )
    canvas.clipPath(data.regionPath)
    if (revealPath != null) canvas.clipPath(revealPath)
    canvas.drawBitmap(
        artwork,
        null,
        RectF(
            0f,
            0f,
            artwork.width.toFloat(),
            artwork.height.toFloat()
        ),
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            this.colorFilter = colorFilter
        }
    )
    canvas.restoreToCount(saveCount)
}
