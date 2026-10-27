package dev.tilesolver.vision

import android.graphics.Bitmap
import dev.tilesolver.core.*
import dev.tilesolver.model.*
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min

class OpenCvBoardMapper(private val clusterer: VisualClusterer) : BoardMapper {
    private var gamePackage: String? = null

    override fun prepareForPackage(packageName: String) {
        if (gamePackage != null && gamePackage != packageName) resetCaches()
        gamePackage = packageName
    }

    override suspend fun map(frame: CapturedFrame, calibration: Calibration, prior: BoardState?): BoardObservation {
        if (!calibration.isValid() || frame.bitmap.width != calibration.screenWidth || frame.bitmap.height != calibration.screenHeight) {
            return BoardObservation(frame.timestampMillis, 0, emptyList(), emptyList(), false, false)
        }
        val source = Mat()
        try {
            Utils.bitmapToMat(frame.bitmap, source)
            val boardTiles = detectTiles(
                source,
                calibration.board,
                false,
                prior?.observation?.tiles?.map { it.bounds }.orEmpty(),
            )
            val trayTiles = detectTiles(source, calibration.tray, true)
            val all = boardTiles + trayTiles
            val boardBounds = boardTiles.map { it.bounds }
            val brightnessReference = BrightnessAvailability.reference(boardTiles.map { it.brightness })
            val boardDimmed = boardTiles.map {
                it.inferredCovered || BrightnessAvailability.isDim(it.brightness, brightnessReference)
            }
            val blockers = boardTiles.indices.map { index ->
                TileOcclusion.occludersOf(index, boardBounds, boardDimmed)
            }
            val prepared = all.mapIndexed { index, candidate ->
                PreparedCandidate(
                    candidate = candidate,
                    signature = signature(source, candidate.bounds, blockers.getOrNull(index).orEmpty()),
                    dimmed = boardDimmed.getOrNull(index) == true,
                )
            }
            val matches = arrayOfNulls<VisualMatch>(prepared.size)
            (0 until boardTiles.size).filter { !prepared[it].dimmed }.forEach { index ->
                val visual = prepared[index]
                val candidates = clusterer.classify(visual.signature)
                val accepted = accepted(candidates, RELIABLE_MATCH_MIN, RELIABLE_MARGIN)
                    ?: accepted(candidates, STABLE_MATCH_MIN, STABLE_MARGIN)
                val cluster = clusterer.learn(
                    visual.signature,
                    accepted?.cluster,
                    forceNew = accepted == null,
                )
                matches[index] = VisualMatch(cluster, accepted?.confidence ?: DEFAULT_NEW_CONFIDENCE, candidates)
            }
            (0 until boardTiles.size).filter { prepared[it].dimmed }.forEach { index ->
                val visual = prepared[index]
                val candidates = clusterer.classify(visual.signature)
                val peek = accepted(candidates, PEEK_MATCH_MIN, PEEK_MARGIN)
                matches[index] = VisualMatch(peek?.cluster, peek?.confidence ?: 0f, candidates)
            }
            (boardTiles.size until prepared.size).forEach { index ->
                val visual = prepared[index]
                val candidates = clusterer.classify(visual.signature)
                // Reuse learned identities across stop/start. On a cold start a
                // tray-only type must not be forced into an unrelated board type.
                val nearest = candidates.firstOrNull()?.takeIf { it.confidence >= TRAY_MATCH_MIN }
                val cluster = nearest?.cluster ?: clusterer.learn(visual.signature, forceNew = true)
                matches[index] = VisualMatch(
                    cluster,
                    nearest?.confidence ?: candidates.firstOrNull()?.confidence ?: 0f,
                    candidates,
                )
            }
            val observations = prepared.mapIndexed { index, visual ->
                val candidate = visual.candidate
                val match = requireNotNull(matches[index])
                val occluders = if (candidate.inTray) emptySet() else blockers[index]
                    .map { TileId(stableId(it)) }
                    .toSet()
                TileObservation(
                    id = TileId(stableId(candidate.bounds)),
                    bounds = candidate.bounds,
                    visualCluster = match.cluster,
                    confidence = match.confidence,
                    candidates = match.candidates,
                    occluders = occluders,
                    state = if (candidate.inTray) TileState.IN_TRAY else if (occluders.isEmpty() && !visual.dimmed) TileState.EXPOSED else TileState.BLOCKED,
                    brightness = candidate.brightness,
                )
            }
            val tray = observations.filter { it.state == TileState.IN_TRAY }.sortedBy { it.bounds.left }.mapNotNull { it.visualCluster }
            return BoardObservation(
                timestampMillis = frame.timestampMillis,
                frameSignature = observations.fold(17L) { acc, tile -> acc * 31 + tile.id.value + (tile.visualCluster?.value ?: 0) },
                tiles = observations.filter { it.state != TileState.IN_TRAY },
                tray = tray,
                recognizedScreen = boardTiles.size >= 3 ||
                    (boardTiles.isNotEmpty() && prior?.observation?.recognizedScreen == true),
                geometryValid = boardTiles.all { calibration.board.contains(it.bounds) } && tray.size <= calibration.trayCapacity,
                trayBounds = observations.filter { it.state == TileState.IN_TRAY }.map { it.bounds },
                trayTiles = observations.filter { it.state == TileState.IN_TRAY },
            )
        } finally { source.release() }
    }

    override fun clearCaches() = clusterer.clearTransient()
    override fun resetCaches() = clusterer.reset()

    private data class Candidate(val bounds: IntRect, val inTray: Boolean, val brightness: Float, val inferredCovered: Boolean = false)
    private data class PreparedCandidate(val candidate: Candidate, val signature: VisualSignature, val dimmed: Boolean)
    private data class VisualMatch(val cluster: ClusterId?, val confidence: Float, val candidates: List<ClusterCandidate>)

    private fun accepted(candidates: List<ClusterCandidate>, minimum: Float, margin: Float): ClusterCandidate? {
        val best = candidates.firstOrNull()?.takeIf { it.confidence >= minimum } ?: return null
        val runnerUp = candidates.getOrNull(1) ?: return best
        return best.takeIf { best.confidence - runnerUp.confidence >= margin }
    }

    private fun detectTiles(
        source: Mat,
        region: IntRect,
        inTray: Boolean,
        trackedBounds: List<IntRect> = emptyList(),
    ): List<Candidate> {
        val roi = source.submat(region.top, region.bottom, region.left, region.right)
        val gray = Mat(); val edges = Mat(); val hierarchy = Mat(); val contours = mutableListOf<MatOfPoint>()
        try {
            Imgproc.cvtColor(roi, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)
            Imgproc.Canny(gray, edges, 45.0, 120.0)
            Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
            val minWidth = source.width() * .065
            val maxWidth = source.width() * .16
            val rectangles = contours.asSequence().mapNotNull { contour ->
                val rect = Imgproc.boundingRect(contour)
                val rectangularity = Imgproc.contourArea(contour) / (rect.width * rect.height).coerceAtLeast(1)
                rect.takeIf {
                    it.width in minWidth.toInt()..maxWidth.toInt() &&
                        it.height > it.width * .78 && it.height < it.width * 1.55 && rectangularity >= .35
                }
            }.map { IntRect(it.x + region.left, it.y + region.top, it.x + it.width + region.left, it.y + it.height + region.top) }
                .sortedWith(compareBy<IntRect> { it.top }.thenBy { it.left })
                .fold(mutableListOf<IntRect>()) { kept, rect ->
                    if (kept.none { overlapRatio(it, rect) > .75f }) kept += rect
                    kept
                }
            val filtered = if (inTray) rectangles else SpatialTileFilter.retainTracked(rectangles, trackedBounds)
            val detected = filtered.map { Candidate(it, inTray, surfaceBrightness(source, it)) }
            if (inTray || detected.size < 2) return detected
            val reference = BrightnessAvailability.reference(detected.map { it.brightness })
            val fronts = detected.filter { !BrightnessAvailability.isDim(it.brightness, reference) }.map { it.bounds }
            if (fronts.size < 2) return detected
            val width = fronts.map { it.width() }.sorted().let { it[it.size / 2] }
            val height = fronts.map { it.height() }.sorted().let { it[it.size / 2] }
            val edgePixels = ByteArray((edges.total()).toInt())
            edges.get(0, 0, edgePixels)
            val recovered = mutableListOf<Candidate>()
            for (contour in contours) {
                val r = Imgproc.boundingRect(contour)
                val fragment = IntRect(r.x + region.left, r.y + region.top, r.x + r.width + region.left, r.y + r.height + region.top)
                for (rect in PartialTileGeometry.proposals(fragment, width, height)) {
                    if (!region.contains(rect) || (filtered + recovered.map { it.bounds }).any { PartialTileGeometry.samePosition(rect, it) }) continue
                    if (!PartialTileGeometry.supported(rect, fronts) { x, y ->
                        region.contains(x, y) && (edgePixels[(y - region.top) * edges.cols() + x - region.left].toInt() and 0xff) > 0
                    }) continue
                    recovered += Candidate(rect, false, surfaceBrightness(source, rect), inferredCovered = true)
                    if (recovered.size >= 32) break
                }
                if (recovered.size >= 32) break
            }
            return detected + recovered
        } finally {
            contours.forEach { it.release() }; hierarchy.release(); edges.release(); gray.release(); roi.release()
        }
    }

    private fun signature(source: Mat, rect: IntRect, covers: List<IntRect> = emptyList()): VisualSignature {
        val insetX = max(2, rect.width() / 7); val insetY = max(2, rect.height() / 7)
        val safe = IntRect(rect.left + insetX, rect.top + insetY, rect.right - insetX, rect.bottom - insetY)
        val crop = source.submat(safe.top, safe.bottom, safe.left, safe.right)
        val gray = Mat(); val normalizedGray = Mat(); val small = Mat(); val edge = Mat(); val appearanceMat = Mat()
        try {
            Imgproc.cvtColor(crop, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.equalizeHist(gray, normalizedGray)
            Imgproc.resize(normalizedGray, small, Size(9.0, 8.0))
            val hashPixels = ByteArray(72)
            small.get(0, 0, hashPixels)
            var hash = 0L
            for (r in 0 until 8) for (c in 0 until 8) {
                if ((hashPixels[r * 9 + c + 1].toInt() and 0xff) >= (hashPixels[r * 9 + c].toInt() and 0xff)) {
                    hash = hash or (1L shl (r * 8 + c))
                }
            }
            val color = FloatArray(24)
            val channels = crop.channels()
            val cropPixels = ByteArray((crop.total() * channels).toInt())
            crop.get(0, 0, cropPixels)
            val rowStep = max(1, crop.rows() / 24)
            val columnStep = max(1, crop.cols() / 24)
            for (r in 0 until crop.rows() step rowStep) for (c in 0 until crop.cols() step columnStep) {
                val pixel = (r * crop.cols() + c) * channels
                for (channel in 0..2) {
                    val value = cropPixels[pixel + channel].toInt() and 0xff
                    color[channel * 8 + min(7, value / 32)]++
                }
            }
            normalize(color)
            Imgproc.Canny(normalizedGray, edge, 50.0, 130.0)
            val edgeSmall = Mat()
            Imgproc.resize(edge, edgeSmall, Size(8.0, 8.0), 0.0, 0.0, Imgproc.INTER_AREA)
            val edgePixels = ByteArray(64)
            edgeSmall.get(0, 0, edgePixels)
            val edgeHist = FloatArray(64) { (edgePixels[it].toInt() and 0xff) / 255f }
            edgeSmall.release()
            normalize(edgeHist)
            Imgproc.resize(normalizedGray, appearanceMat, Size(16.0, 16.0), 0.0, 0.0, Imgproc.INTER_AREA)
            val appearanceMean = Core.mean(appearanceMat).`val`[0]
            val appearanceBytes = ByteArray(256)
            appearanceMat.get(0, 0, appearanceBytes)
            val appearance = FloatArray(256)
            var variance = 0.0
            for (index in appearance.indices) {
                val centered = (appearanceBytes[index].toInt() and 0xff) - appearanceMean
                appearance[index] = centered.toFloat()
                variance += centered * centered
            }
            val scale = kotlin.math.sqrt(variance / appearance.size).coerceAtLeast(1.0).toFloat()
            appearance.indices.forEach { appearance[it] /= scale }
            val mask = if (covers.isEmpty()) floatArrayOf() else FloatArray(256) { index ->
                val x = safe.left + (index % 16 + .5f) * safe.width() / 16
                val y = safe.top + (index / 16 + .5f) * safe.height() / 16
                if (covers.any { x >= it.left - 3 && x <= it.right + 3 && y >= it.top - 3 && y <= it.bottom + 3 }) 0f else 1f
            }
            return VisualSignature(hash, color, edgeHist, appearance, mask)
        } finally { appearanceMat.release(); edge.release(); small.release(); normalizedGray.release(); gray.release(); crop.release() }
    }

    private fun surfaceBrightness(source: Mat, rect: IntRect): Float {
        // Sample the paper rim, not the sprite: dark speakers/gamepads are still
        // playable. A lower-middle percentile also resists a bright covering card
        // contaminating some of a locked card's perimeter.
        val samples = mutableListOf<Float>()
        fun sample(x: Float, y: Float) {
            val pixel = source.get(rect.top + (rect.height() * y).toInt(), rect.left + (rect.width() * x).toInt())
            samples += ((.2126 * pixel[0] + .7152 * pixel[1] + .0722 * pixel[2]) / 255.0).toFloat()
        }
        for (step in 2..8) {
            sample(.055f, step / 10f)
            sample(.945f, step / 10f)
            sample(step / 10f, .055f)
            sample(step / 10f, .89f)
        }
        return samples.sorted()[(samples.size * .40f).toInt()]
    }

    private fun normalize(values: FloatArray) { val sum = values.sum().coerceAtLeast(1f); values.indices.forEach { values[it] /= sum } }
    private fun stableId(r: IntRect) = (r.centerX().toLong() shl 32) xor (r.centerY().toLong() and 0xffffffffL)
    private fun overlapRatio(a: IntRect, b: IntRect): Float {
        val left=max(a.left,b.left); val top=max(a.top,b.top); val right=min(a.right,b.right); val bottom=min(a.bottom,b.bottom)
        if (right <= left || bottom <= top) return 0f
        return (right-left)*(bottom-top).toFloat() / min(a.width()*a.height(), b.width()*b.height()).coerceAtLeast(1)
    }

    companion object {
        private const val RELIABLE_MATCH_MIN = .86f
        private const val RELIABLE_MARGIN = .08f
        private const val STABLE_MATCH_MIN = .78f
        private const val STABLE_MARGIN = .025f
        private const val TRAY_MATCH_MIN = .62f
        private const val PEEK_MATCH_MIN = .87f
        private const val PEEK_MARGIN = .08f
        private const val DEFAULT_NEW_CONFIDENCE = .72f
    }
}

internal object BrightnessAvailability {
    fun reference(values: List<Float>): Float {
        if (values.isEmpty()) return 1f
        val sorted = values.sorted()
        return sorted[((sorted.lastIndex * .75f).toInt()).coerceIn(sorted.indices)]
    }

    fun isDim(value: Float, reference: Float): Boolean = value < max(.42f, reference * .68f)
}

internal object TileOcclusion {
    fun occludersOf(candidateIndex: Int, rectangles: List<IntRect>, dimmed: List<Boolean>): List<IntRect> {
        if (candidateIndex !in rectangles.indices) return emptyList()
        if (dimmed.getOrNull(candidateIndex) != true) return emptyList()
        val candidate = rectangles[candidateIndex]
        val overlaps = rectangles.indices.filter { it != candidateIndex && overlapRatio(candidate, rectangles[it]) > .12f }
        val exposedBlockers = overlaps.filter { dimmed.getOrNull(it) == false }
        if (exposedBlockers.isNotEmpty()) return exposedBlockers.map(rectangles::get)

        // Screen Y order is not stack order. Leave unresolved dim-on-dim relations
        // unknown instead of inventing a path the planner cannot actually execute.
        return emptyList()
    }

    private fun overlapRatio(a: IntRect, b: IntRect): Float {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        if (right <= left || bottom <= top) return 0f
        return (right - left) * (bottom - top).toFloat() /
            min(a.width() * a.height(), b.width() * b.height()).coerceAtLeast(1)
    }
}

internal object SpatialTileFilter {
    fun retainTracked(rectangles: List<IntRect>, tracked: List<IntRect>): List<IntRect> {
        val connected = largestConnectedCluster(rectangles)
        if (tracked.isEmpty()) return connected
        val recovered = rectangles.filter { candidate -> tracked.any { prior -> sameTileGeometry(candidate, prior) } }
        val anchors = (connected + tracked).distinct()
        val newlyExposed = rectangles.filter { candidate ->
            sameCardSize(candidate, anchors) && insideBoardEnvelope(candidate, anchors)
        }
        return (connected + recovered + newlyExposed).distinct()
            .sortedWith(compareBy<IntRect> { it.top }.thenBy { it.left })
    }

    fun largestConnectedCluster(rectangles: List<IntRect>): List<IntRect> {
        if (rectangles.size < 2) return rectangles
        val unvisited = rectangles.toMutableSet()
        val components = mutableListOf<List<IntRect>>()
        while (unvisited.isNotEmpty()) {
            val pending = ArrayDeque<IntRect>()
            val component = mutableListOf<IntRect>()
            pending += unvisited.first().also(unvisited::remove)
            while (pending.isNotEmpty()) {
                val current = pending.removeFirst()
                component += current
                val neighbors = unvisited.filter { isNeighbor(current, it) }
                neighbors.forEach { neighbor ->
                    unvisited.remove(neighbor)
                    pending += neighbor
                }
            }
            components += component
        }
        return components.maxWithOrNull(compareBy<List<IntRect>> { it.size }.thenBy { component -> component.sumOf { it.width() * it.height() } })
            .orEmpty()
            .sortedWith(compareBy<IntRect> { it.top }.thenBy { it.left })
    }

    private fun isNeighbor(a: IntRect, b: IntRect): Boolean {
        val maxWidth = max(a.width(), b.width()).toFloat()
        val maxHeight = max(a.height(), b.height()).toFloat()
        return kotlin.math.abs(a.centerX() - b.centerX()) <= maxWidth * 1.8f &&
            kotlin.math.abs(a.centerY() - b.centerY()) <= maxHeight * 1.8f
    }

    private fun sameTileGeometry(candidate: IntRect, prior: IntRect): Boolean {
        val widthRatio = candidate.width().toFloat() / prior.width().coerceAtLeast(1)
        val heightRatio = candidate.height().toFloat() / prior.height().coerceAtLeast(1)
        return widthRatio in .72f..1.28f && heightRatio in .72f..1.28f &&
            kotlin.math.abs(candidate.centerX() - prior.centerX()) <= max(candidate.width(), prior.width()) * .35f &&
            kotlin.math.abs(candidate.centerY() - prior.centerY()) <= max(candidate.height(), prior.height()) * .35f
    }

    private fun sameCardSize(candidate: IntRect, anchors: List<IntRect>): Boolean {
        if (anchors.isEmpty()) return false
        val widths = anchors.map { it.width() }.sorted()
        val heights = anchors.map { it.height() }.sorted()
        val width = widths[widths.size / 2].coerceAtLeast(1)
        val height = heights[heights.size / 2].coerceAtLeast(1)
        return candidate.width().toFloat() / width in .72f..1.28f &&
            candidate.height().toFloat() / height in .72f..1.28f
    }

    private fun insideBoardEnvelope(candidate: IntRect, anchors: List<IntRect>): Boolean {
        if (anchors.isEmpty()) return false
        val paddingX = anchors.maxOf { it.width() } * 2
        val paddingY = anchors.maxOf { it.height() } * 2
        return candidate.centerX() in (anchors.minOf { it.left } - paddingX)..(anchors.maxOf { it.right } + paddingX) &&
            candidate.centerY() in (anchors.minOf { it.top } - paddingY)..(anchors.maxOf { it.bottom } + paddingY)
    }
}
