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
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot

private const val POLYGON_REVEAL_BUCKET_COUNT = 256
private const val INTERSECTION_GRID_CELL_SIZE = 64

internal data class LessonPolygon(val vertices: List<LessonPoint>)

internal class PolygonRevealData private constructor(
    val yellowArtwork: Bitmap,
    val regionPath: Path,
    private val runsByPathAndBucket: List<List<Path>>
) {
    companion object {
        fun create(
            polygons: List<LessonPolygon>,
            step: LessonStep,
            coordinateWidth: Float,
            coordinateHeight: Float,
            yellowArtwork: Bitmap
        ): PolygonRevealData {
            require(polygons.isNotEmpty()) { "Polygon step has no regions" }
            require(coordinateWidth > 0f && coordinateHeight > 0f) {
                "Invalid path coordinate dimensions"
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

            val routeScaleX = yellowArtwork.width.toDouble() / coordinateWidth
            val routeScaleY = yellowArtwork.height.toDouble() / coordinateHeight
            val routeWidthScale = hypot(routeScaleX, routeScaleY) / kotlin.math.sqrt(2.0)
            val routes = step.paths.map { path ->
                val segments = mutableListOf<RouteSegment>()
                var totalLength = 0.0
                path.strokes.forEachIndexed { strokeIndex, stroke ->
                    for (index in 0 until stroke.nodes.lastIndex) {
                        val start = stroke.nodes[index]
                        val end = stroke.nodes[index + 1]
                        val startX = start.x.toDouble() * routeScaleX
                        val startY = start.y.toDouble() * routeScaleY
                        val deltaX = (end.x.toDouble() - start.x) * routeScaleX
                        val deltaY = (end.y.toDouble() - start.y) * routeScaleY
                        val length = hypot(deltaX, deltaY)
                        if (length > 0.0 && length.isFinite()) {
                            val sourceWidth = stroke.segmentWidths.getOrNull(index) ?: stroke.width
                            segments += RouteSegment(
                                startX = startX,
                                startY = startY,
                                deltaX = deltaX,
                                deltaY = deltaY,
                                length = length,
                                startDistance = totalLength,
                                strokeIndex = strokeIndex,
                                segmentIndex = index,
                                width = sourceWidth * routeWidthScale
                            )
                            totalLength += length
                        }
                    }
                }
                Route(segments, totalLength)
            }
            require(routes.any { it.totalLength > 0.0 && it.totalLength.isFinite() }) {
                "Polygon step has no usable animation route"
            }
            val intersectionsByPath = routes.map { route ->
                if (route.totalLength <= 0.0 || !route.totalLength.isFinite()) {
                    emptyMap()
                } else {
                    buildIntersectionGrid(
                        intersections = findRouteIntersections(route.segments, route.totalLength),
                        imageWidth = yellowArtwork.width,
                        imageHeight = yellowArtwork.height
                    )
                }
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

            val runsByPathAndBucket = routes.map {
                List(POLYGON_REVEAL_BUCKET_COUNT) { Path() }
            }
            val regionPixels = IntArray(yellowArtwork.width)
            val artworkPixels = IntArray(yellowArtwork.width)
            for (y in 0 until yellowArtwork.height) {
                regionMask.getPixels(
                    regionPixels,
                    0,
                    yellowArtwork.width,
                    0,
                    y,
                    yellowArtwork.width,
                    1
                )
                yellowArtwork.getPixels(
                    artworkPixels,
                    0,
                    yellowArtwork.width,
                    0,
                    y,
                    yellowArtwork.width,
                    1
                )
                var runStart = -1
                var runBucket: Pair<Int, Int>? = null
                for (x in 0..yellowArtwork.width) {
                    val bucket = if (x < yellowArtwork.width &&
                        Color.alpha(regionPixels[x]) > 0 &&
                        Color.alpha(artworkPixels[x]) > 0
                    ) {
                        val routeMatch = nearestRoute(
                            x + 0.5,
                            y + 0.5,
                            routes
                        )
                        val ownerPath = routeMatch.pathIndex
                        var routeProgress = routeMatch.progress
                        intersectionsByPath[ownerPath][gridKey(
                            x / INTERSECTION_GRID_CELL_SIZE,
                            y / INTERSECTION_GRID_CELL_SIZE
                        )].orEmpty().forEach { intersection ->
                            val pixelX = x + 0.5
                            val pixelY = y + 0.5
                            val offsetX = pixelX - intersection.x
                            val offsetY = pixelY - intersection.y
                            val radiusSquared = intersection.radius * intersection.radius
                            if (offsetX * offsetX + offsetY * offsetY <= radiusSquared &&
                                minOf(
                                    distanceToSegmentSquared(
                                        pixelX,
                                        pixelY,
                                        intersection.firstSegment
                                    ),
                                    distanceToSegmentSquared(
                                        pixelX,
                                        pixelY,
                                        intersection.secondSegment
                                    )
                                ) <= radiusSquared
                            ) {
                                routeProgress = minOf(routeProgress, intersection.routeProgress)
                            }
                        }
                        (ownerPath to (kotlin.math.ceil(
                            routeProgress * POLYGON_REVEAL_BUCKET_COUNT - 1e-9
                        ).toInt() - 1)
                            .coerceIn(0, POLYGON_REVEAL_BUCKET_COUNT - 1))
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

            return PolygonRevealData(yellowArtwork, regionPath, runsByPathAndBucket)
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

        private fun findRouteIntersections(
            segments: List<RouteSegment>,
            totalLength: Double
        ): List<RouteIntersection> {
            val intersections = mutableListOf<RouteIntersection>()
            for (firstIndex in segments.indices) {
                val first = segments[firstIndex]
                for (secondIndex in firstIndex + 1 until segments.size) {
                    val second = segments[secondIndex]
                    if (first.strokeIndex == second.strokeIndex &&
                        second.segmentIndex == first.segmentIndex + 1
                    ) {
                        continue
                    }
                    segmentIntersections(first, second).forEach { (x, y) ->
                        intersections += RouteIntersection(
                            x = x,
                            y = y,
                            routeProgress = minOf(
                                routeProgressAt(first, x, y, totalLength),
                                routeProgressAt(second, x, y, totalLength)
                            ),
                            radius = maxOf(first.width, second.width) / 2.0 + 1.0,
                            firstSegment = first,
                            secondSegment = second
                        )
                    }
                }
            }
            return intersections
        }

        private fun buildIntersectionGrid(
            intersections: List<RouteIntersection>,
            imageWidth: Int,
            imageHeight: Int
        ): Map<Long, List<RouteIntersection>> {
            val grid = mutableMapOf<Long, MutableList<RouteIntersection>>()
            intersections.forEach { intersection ->
                val firstCellX = (intersection.x - intersection.radius).toInt()
                    .coerceIn(0, imageWidth - 1) / INTERSECTION_GRID_CELL_SIZE
                val lastCellX = (intersection.x + intersection.radius).toInt()
                    .coerceIn(0, imageWidth - 1) / INTERSECTION_GRID_CELL_SIZE
                val firstCellY = (intersection.y - intersection.radius).toInt()
                    .coerceIn(0, imageHeight - 1) / INTERSECTION_GRID_CELL_SIZE
                val lastCellY = (intersection.y + intersection.radius).toInt()
                    .coerceIn(0, imageHeight - 1) / INTERSECTION_GRID_CELL_SIZE
                for (cellY in firstCellY..lastCellY) {
                    for (cellX in firstCellX..lastCellX) {
                        grid.getOrPut(gridKey(cellX, cellY)) { mutableListOf() } += intersection
                    }
                }
            }
            return grid.mapValues { (_, intersectionsInCell) -> intersectionsInCell.toList() }
        }

        private fun gridKey(x: Int, y: Int): Long =
            (x.toLong() shl 32) or (y.toLong() and 0xffffffffL)

        private fun segmentIntersections(
            first: RouteSegment,
            second: RouteSegment
        ): List<Pair<Double, Double>> {
            val offsetX = second.startX - first.startX
            val offsetY = second.startY - first.startY
            val cross = first.deltaX * second.deltaY - first.deltaY * second.deltaX
            val offsetCrossFirst = offsetX * first.deltaY - offsetY * first.deltaX
            val epsilon = 1e-9
            if (abs(cross) > epsilon) {
                val firstProjection =
                    (offsetX * second.deltaY - offsetY * second.deltaX) / cross
                val secondProjection =
                    (offsetX * first.deltaY - offsetY * first.deltaX) / cross
                if (firstProjection !in 0.0..1.0 || secondProjection !in 0.0..1.0) {
                    return emptyList()
                }
                return listOf(
                    Pair(
                        first.startX + first.deltaX * firstProjection,
                        first.startY + first.deltaY * firstProjection
                    )
                )
            }
            if (abs(offsetCrossFirst) > epsilon) return emptyList()

            val endpoints = listOf(
                Pair(first.startX, first.startY),
                Pair(first.startX + first.deltaX, first.startY + first.deltaY),
                Pair(second.startX, second.startY),
                Pair(second.startX + second.deltaX, second.startY + second.deltaY)
            )
            return endpoints.filterIndexed { index, point ->
                distanceToSegmentSquared(point.first, point.second, first) <= epsilon &&
                    distanceToSegmentSquared(point.first, point.second, second) <= epsilon &&
                    endpoints.take(index).none { previous ->
                        val deltaX = previous.first - point.first
                        val deltaY = previous.second - point.second
                        deltaX * deltaX + deltaY * deltaY <= epsilon
                    }
            }
        }

        private fun routeProgressAt(
            segment: RouteSegment,
            x: Double,
            y: Double,
            totalLength: Double
        ): Double {
            val offsetX = x - segment.startX
            val offsetY = y - segment.startY
            val projection = (
                (offsetX * segment.deltaX + offsetY * segment.deltaY) /
                    (segment.length * segment.length)
                ).coerceIn(0.0, 1.0)
            return ((segment.startDistance + segment.length * projection) / totalLength)
                .coerceIn(0.0, 1.0)
        }

        private fun distanceToSegmentSquared(
            x: Double,
            y: Double,
            segment: RouteSegment
        ): Double {
            val offsetX = x - segment.startX
            val offsetY = y - segment.startY
            val projection = (
                (offsetX * segment.deltaX + offsetY * segment.deltaY) /
                    (segment.length * segment.length)
                ).coerceIn(0.0, 1.0)
            val differenceX = offsetX - segment.deltaX * projection
            val differenceY = offsetY - segment.deltaY * projection
            return differenceX * differenceX + differenceY * differenceY
        }
    }

    private data class RouteSegment(
        val startX: Double,
        val startY: Double,
        val deltaX: Double,
        val deltaY: Double,
        val length: Double,
        val startDistance: Double,
        val strokeIndex: Int,
        val segmentIndex: Int,
        val width: Double
    )

    private data class Route(
        val segments: List<RouteSegment>,
        val totalLength: Double
    )

    private data class RouteMatch(
        val pathIndex: Int,
        val progress: Double
    )

    private data class RouteIntersection(
        val x: Double,
        val y: Double,
        val routeProgress: Double,
        val radius: Double,
        val firstSegment: RouteSegment,
        val secondSegment: RouteSegment
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

internal fun readStepPolygons(root: JSONObject): Map<Int, List<LessonPolygon>> {
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
            val vertices = when (region) {
                is JSONObject -> region.optJSONArray("vertices")
                    ?: region.optJSONArray("points")
                    ?: region.optJSONArray("coordinates")
                    ?: throw IOException("Polygon region $regionIndex has no vertices")
                is JSONArray -> region
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
            regions += LessonPolygon(points)
        }
    }
    return polygonsByStep.mapValues { it.value.toList() }
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
