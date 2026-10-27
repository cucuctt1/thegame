package dev.tilesolver.model

import android.graphics.Bitmap

@JvmInline value class TileId(val value: Long)
@JvmInline value class ClusterId(val value: Int)

data class BoardCoordinate(val x: Float, val y: Float)

data class IntRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    fun width() = right - left
    fun height() = bottom - top
    fun centerX() = (left + right) / 2
    fun centerY() = (top + bottom) / 2
    fun exactCenterX() = (left + right) / 2f
    fun exactCenterY() = (top + bottom) / 2f
    fun contains(x: Int, y: Int) = x >= left && x < right && y >= top && y < bottom
    fun contains(other: IntRect) = other.left >= left && other.top >= top && other.right <= right && other.bottom <= bottom
    fun intersects(other: IntRect) = left < other.right && other.left < right && top < other.bottom && other.top < bottom
}

data class NormalizedDetectionArea(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    fun isValid() = left in 0f..1f && top in 0f..1f && right in 0f..1f && bottom in 0f..1f &&
        right - left >= MIN_SIZE && bottom - top >= MIN_SIZE

    fun toPixels(width: Int, height: Int): IntRect = IntRect(
        (left * width).toInt(),
        (top * height).toInt(),
        (right * width).toInt(),
        (bottom * height).toInt(),
    )

    companion object {
        private const val MIN_SIZE = .1f

        fun fromPixels(rect: IntRect, width: Int, height: Int) = NormalizedDetectionArea(
            rect.left.toFloat() / width,
            rect.top.toFloat() / height,
            rect.right.toFloat() / width,
            rect.bottom.toFloat() / height,
        )
    }
}

data class Calibration(
    val screenWidth: Int,
    val screenHeight: Int,
    val board: IntRect,
    val tray: IntRect,
    val exclusions: List<IntRect>,
    val trayCapacity: Int = 7,
) {
    fun isValid(): Boolean = screenWidth > 0 && screenHeight > 0 &&
        board.width() > 0 && board.height() > 0 && tray.width() > 0 && tray.height() > 0 &&
        IntRect(0, 0, screenWidth, screenHeight).contains(board) &&
        IntRect(0, 0, screenWidth, screenHeight).contains(tray) && !board.intersects(tray)
}

enum class TileState { EXPOSED, BLOCKED, IN_TRAY }

data class ClusterCandidate(val cluster: ClusterId, val confidence: Float)

data class TileObservation(
    val id: TileId,
    val bounds: IntRect,
    val visualCluster: ClusterId?,
    val confidence: Float,
    val candidates: List<ClusterCandidate> = emptyList(),
    val occluders: Set<TileId> = emptySet(),
    val state: TileState,
    val brightness: Float = 1f,
) {
    val center get() = BoardCoordinate(bounds.exactCenterX(), bounds.exactCenterY())
}

data class BoardObservation(
    val timestampMillis: Long,
    val frameSignature: Long,
    val tiles: List<TileObservation>,
    val tray: List<ClusterId>,
    val recognizedScreen: Boolean,
    val geometryValid: Boolean,
    val trayBounds: List<IntRect> = emptyList(),
    val trayTiles: List<TileObservation> = emptyList(),
)

data class MoveRecord(val tileId: TileId, val cluster: ClusterId, val timestampMillis: Long)

data class BoardState(
    val observation: BoardObservation,
    val moveHistory: List<MoveRecord> = emptyList(),
    val noOpCount: Int = 0,
    val uncertainty: Float = 0f,
    val observedTray: List<ClusterId>? = null,
    val trayConsistentFrames: Int = 0,
    val trayStable: Boolean = true,
) {
    val exposed get() = observation.tiles.filter { it.state == TileState.EXPOSED && it.occluders.isEmpty() }
}

data class CapturedFrame(
    val bitmap: Bitmap,
    val timestampMillis: Long,
    val sequence: Long,
) : AutoCloseable {
    override fun close() { if (!bitmap.isRecycled) bitmap.recycle() }
}

data class MoveDecision(
    val tileId: TileId,
    val coordinate: BoardCoordinate,
    val score: Double,
    val confidence: Float,
    val rationale: String,
    val goalTileId: TileId? = null,
)

sealed interface SafetyDecision {
    data object Allow : SafetyDecision
    data class Remap(val reason: String) : SafetyDecision
    data class Stop(val reason: String) : SafetyDecision
}

sealed interface TapResult {
    data object Completed : TapResult
    data object Cancelled : TapResult
    data class Failed(val reason: String) : TapResult
}

enum class StopReason { USER, TIMEOUT, CAPTURE_LOST, PACKAGE_CHANGED, UNSAFE_GEOMETRY, UNKNOWN_SCREEN, NO_OP, WON, LOST, FAULT }

sealed interface SessionState {
    data object Idle : SessionState
    data object Calibrating : SessionState
    data object Observing : SessionState
    data object Planning : SessionState
    data class Tapping(val move: MoveDecision) : SessionState
    data class Verifying(val move: MoveDecision) : SessionState
    data class Paused(val reason: String) : SessionState
    data class Stopped(val reason: StopReason, val detail: String = "") : SessionState
    data object Won : SessionState
    data object Lost : SessionState
    data class Fault(val message: String) : SessionState
}
