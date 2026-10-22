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
import kotlin.math.floor
import kotlin.math.hypot

private const val POLYGON_REVEAL_BUCKET_COUNT = 256

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
    private val runsByPathAndBucket: List<List<Path>>,
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

            val routeScaleX = yellowArtwork.width.toDouble() / coordinateWidth
            val routeScaleY = yellowArtwork.height.toDouble() / coordinateHeight
            val allStrokes = step.paths.flatMap { it.strokes }
            val routes = allStrokes.map { stroke ->
                val segments = mutableListOf<RouteSegment>()
                var totalLength = 0.0
                for (index in 0 until stroke.nodes.lastIndex) {
                    val start = stroke.nodes[index]
                    val end = stroke.nodes[index + 1]
                    val centerOffset = stroke.segmentCenterOffsets.getOrNull(index) ?: 0f

                    val rawDeltaX = (end.x.toDouble() - start.x.toDouble())
                    val rawDeltaY = (end.y.toDouble() - start.y.toDouble())
                    val rawLength = hypot(rawDeltaX, rawDeltaY)

                    val (startXCoord, startYCoord) = if (abs(centerOffset) > 0.001 && rawLength > 0.0) {
                        val nx = -rawDeltaY / rawLength
                        val ny = rawDeltaX / rawLength
                        (start.x.toDouble() + nx * centerOffset) to (start.y.toDouble() + ny * centerOffset)
                    } else {
                        start.x.toDouble() to start.y.toDouble()
                    }

                    val deltaX = rawDeltaX * routeScaleX
                    val deltaY = rawDeltaY * routeScaleY
                    val startX = startXCoord * routeScaleX
                    val startY = startYCoord * routeScaleY
                    val length = hypot(deltaX, deltaY)

                    if (length > 0.0 && length.isFinite()) {
                        segments += RouteSegment(
                            startX = startX,
                            startY = startY,
                            deltaX = deltaX,
                            deltaY = deltaY,
                            length = length,
                            startDistance = totalLength
                        )
                        totalLength += length
                    }
                }
                Route(segments, totalLength)
            }
            require(routes.any { it.totalLength > 0.0 && it.totalLength.isFinite() }) {
                "Polygon step has no usable animation route"
            }

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

            val routePath = Path()
            routes.forEach { route ->
                route.segments.forEachIndexed { segIdx, seg ->
                    val x1 = seg.startX.toFloat()
                    val y1 = seg.startY.toFloat()
                    val x2 = (seg.startX + seg.deltaX).toFloat()
                    val y2 = (seg.startY + seg.deltaY).toFloat()
                    if (segIdx == 0) {
                        routePath.moveTo(x1, y1)
                    }
                    routePath.lineTo(x2, y2)
                }
            }
            regionPath.addPath(routePath)

            data class ProcessedPolygon(
                val path: Path,
                val strokeIndex: Int,
                val hole: Boolean
            )
            val processedPolygons = polygons.mapIndexed { index, polygon ->
                val strokeIndex = polygon.id?.toIntOrNull()?.coerceIn(0, routes.lastIndex) ?: (index % routes.size)
                val pPath = Path().apply {
                    val signedArea = polygon.vertices.indices.sumOf { idx ->
                        val pt = polygon.vertices[idx]
                        val next = polygon.vertices[(idx + 1) % polygon.vertices.size]
                        (pt.x.toDouble() * next.y - next.x.toDouble() * pt.y)
                    }
                    val ordered = if (signedArea > 0.0) polygon.vertices else polygon.vertices.asReversed()
                    ordered.forEachIndexed { ptIdx, pt ->
                        if (ptIdx == 0) moveTo(pt.x, pt.y) else lineTo(pt.x, pt.y)
                    }
                    close()
                }
                ProcessedPolygon(pPath, strokeIndex, polygon.hole)
            }

            fun findStrokeForPoint(px: Double, py: Double): Int? {
                var matched: Int? = null
                for (pp in processedPolygons) {
                    if (isPointInPath(pp.path, px.toFloat(), py.toFloat())) {
                        if (pp.hole) {
                            matched = null
                        } else {
                            matched = pp.strokeIndex
                        }
                    }
                }
                return matched
            }

            val regionMask = Bitmap.createBitmap(
                yellowArtwork.width,
                yellowArtwork.height,
                Bitmap.Config.ARGB_8888
            )
            Canvas(regionMask).drawPath(
                regionPath,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
            )

            val runsByPathAndBucket = routes.map { List(POLYGON_REVEAL_BUCKET_COUNT) { Path() } }
            val regionPixels = IntArray(yellowArtwork.width)
            val artworkPixels = IntArray(yellowArtwork.width)

            fun revealBucketForProgress(progress: Double): Int =
                (kotlin.math.ceil(progress * POLYGON_REVEAL_BUCKET_COUNT - 1e-9).toInt() - 1)
                    .coerceIn(0, POLYGON_REVEAL_BUCKET_COUNT - 1)

            fun nearestRouteOnPath(
                x: Double,
                y: Double,
                routes: List<Route>,
                pathIndex: Int
            ): RouteMatch {
                val route = routes.getOrNull(pathIndex) ?: return nearestRoute(x, y, routes)
                if (route.totalLength <= 0.0 || !route.totalLength.isFinite()) {
                    return nearestRoute(x, y, routes)
                }

                var nearestDistanceSquared = Double.POSITIVE_INFINITY
                var nearestProgress = 0.0
                route.segments.forEach { segment ->
                    val offsetX = x - segment.startX
                    val offsetY = y - segment.startY
                    val projection = (
                        (offsetX * segment.deltaX + offsetY * segment.deltaY) /
                            (segment.length * segment.length)
                        ).coerceIn(0.0, 1.0)
                    val differenceX = offsetX - segment.deltaX * projection
                    val differenceY = offsetY - segment.deltaY * projection
                    val distanceSquared = differenceX * differenceX + differenceY * differenceY
                    val nearestScale = if (nearestDistanceSquared.isFinite()) abs(nearestDistanceSquared) else 0.0
                    val tieTolerance = maxOf(abs(distanceSquared), nearestScale, 1.0) * 1e-12
                    if (distanceSquared < nearestDistanceSquared - tieTolerance) {
                        nearestDistanceSquared = distanceSquared
                        nearestProgress = (segment.startDistance + segment.length * projection) / route.totalLength
                    }
                }
                return RouteMatch(pathIndex = pathIndex, progress = nearestProgress.coerceIn(0.0, 1.0))
            }

            for (y in 0 until yellowArtwork.height) {
                regionMask.getPixels(regionPixels, 0, yellowArtwork.width, 0, y, yellowArtwork.width, 1)
                yellowArtwork.getPixels(artworkPixels, 0, yellowArtwork.width, 0, y, yellowArtwork.width, 1)
                var runStart = -1
                var runBucket: Pair<Int, Int>? = null
                for (x in 0..yellowArtwork.width) {
                    val bucket = if (x < yellowArtwork.width &&
                        Color.alpha(regionPixels[x]) > 0 &&
                        Color.alpha(artworkPixels[x]) > 0
                    ) {
                        val px = x + 0.5
                        val py = y + 0.5
                        val strokeOverride = findStrokeForPoint(px, py)
                        val routeMatch = if (strokeOverride != null) {
                            nearestRouteOnPath(px, py, routes, strokeOverride)
                        } else {
                            nearestRoute(px, py, routes)
                        }
                        val ownerPath = routeMatch.pathIndex
                        val revealProgress = routeMatch.progress
                        (ownerPath to revealBucketForProgress(revealProgress))
                    } else {
                        null
                    }

                    if (bucket != runBucket) {
                        if (runStart >= 0) {
                            val (pathIndex, pathBucket) = checkNotNull(runBucket)
                            runsByPathAndBucket[pathIndex][pathBucket].addRect(
                                runStart.toFloat(),
                                y.toFloat(),
                                x.toFloat(),
                                y + 1f,
                                Path.Direction.CW
                            )
                        }
                        runStart = if (bucket != null) x else -1
                        runBucket = bucket
                    }
                }
            }

            regionMask.recycle()

            return PolygonRevealData(
                yellowArtwork,
                regionPath,
                runsByPathAndBucket,
                connections
            )
        }

        private fun nearestRoute(
            x: Double,
            y: Double,
            routes: List<Route>
        ): RouteMatch {
            var nearestDistanceSquared = Double.POSITIVE_INFINITY
            var nearestPathIndex = -1
            var nearestProgress = 0.0
            routes.forEachIndexed { pathIndex, route ->
                if (route.totalLength > 0.0 && route.totalLength.isFinite()) {
                    route.segments.forEach { segment ->
                        val offsetX = x - segment.startX
                        val offsetY = y - segment.startY
                        val projection = (
                            (offsetX * segment.deltaX + offsetY * segment.deltaY) /
                                (segment.length * segment.length)
                            ).coerceIn(0.0, 1.0)
                        val differenceX = offsetX - segment.deltaX * projection
                        val differenceY = offsetY - segment.deltaY * projection
                        val distanceSquared = differenceX * differenceX + differenceY * differenceY
                        val nearestScale =
                            if (nearestDistanceSquared.isFinite()) abs(nearestDistanceSquared) else 0.0
                        val tieTolerance = maxOf(abs(distanceSquared), nearestScale, 1.0) * 1e-12
                        if (distanceSquared < nearestDistanceSquared - tieTolerance) {
                            nearestDistanceSquared = distanceSquared
                            nearestPathIndex = pathIndex
                            nearestProgress =
                                (segment.startDistance + segment.length * projection) /
                                    route.totalLength
                        }
                    }
                }
            }
            check(nearestPathIndex >= 0) { "Artwork pixel has no usable animation path" }
            return RouteMatch(
                pathIndex = nearestPathIndex,
                progress = nearestProgress.coerceIn(0.0, 1.0)
            )
        }

    }

    private data class RouteSegment(
        val startX: Double,
        val startY: Double,
        val deltaX: Double,
        val deltaY: Double,
        val length: Double,
        val startDistance: Double
    )

    private data class Route(
        val segments: List<RouteSegment>,
        val totalLength: Double
    )

    private data class RouteMatch(
        val pathIndex: Int,
        val progress: Double
    )

    internal class Playback(private val data: PolygonRevealData) {
        private val visiblePaths = data.runsByPathAndBucket.map { Path() }
        private val lastBuckets = IntArray(data.runsByPathAndBucket.size) { -1 }

        fun reset() {
            visiblePaths.forEach(Path::reset)
            lastBuckets.fill(-1)
        }

        fun pathAt(pathIndex: Int, progress: Float): Path? {
            if (pathIndex !in visiblePaths.indices) return null
            val boundedProgress = progress.coerceIn(0f, 1f)
            if (boundedProgress <= 0f) return null
            val targetBucket = floor(boundedProgress * POLYGON_REVEAL_BUCKET_COUNT)
                .toInt()
                .minus(1)
                .coerceIn(0, POLYGON_REVEAL_BUCKET_COUNT - 1)
            val visiblePath = visiblePaths[pathIndex]
            if (targetBucket < lastBuckets[pathIndex]) {
                visiblePath.reset()
                lastBuckets[pathIndex] = -1
            }
            for (bucket in (lastBuckets[pathIndex] + 1)..targetBucket) {
                visiblePath.addPath(data.runsByPathAndBucket[pathIndex][bucket])
            }
            lastBuckets[pathIndex] = targetBucket
            return visiblePath
        }
    }
}

private fun isPointInPath(path: Path, x: Float, y: Float): Boolean {
    val bounds = RectF()
    path.computeBounds(bounds, true)
    if (!bounds.contains(x, y)) return false
    val region = android.graphics.Region()
    val clip = android.graphics.Region(
        bounds.left.toInt() - 1,
        bounds.top.toInt() - 1,
        bounds.right.toInt() + 1,
        bounds.bottom.toInt() + 1
    )
    region.setPath(path, clip)
    return region.contains(x.toInt(), y.toInt())
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
