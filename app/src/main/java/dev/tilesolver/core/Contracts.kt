package dev.tilesolver.core

import android.content.Intent
import dev.tilesolver.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface FrameSource {
    val frames: Flow<CapturedFrame>
    suspend fun start(resultCode: Int, resultData: Intent)
    suspend fun stop()
}

interface BoardCalibrator {
    suspend fun calibrate(frame: CapturedFrame): Calibration
}

interface BoardMapper {
    fun prepareForPackage(packageName: String) {}
    suspend fun map(frame: CapturedFrame, calibration: Calibration, prior: BoardState?): BoardObservation
    fun clearCaches()
    fun resetCaches() = clearCaches()
}

interface VisualClusterer {
    fun classify(signature: VisualSignature): List<ClusterCandidate>
    fun learn(signature: VisualSignature, accepted: ClusterId? = null, forceNew: Boolean = false): ClusterId
    fun clearTransient()
    fun reset() = clearTransient()
}

data class VisualSignature(
    val perceptualHash: Long,
    val colorHistogram: FloatArray,
    val edgeHistogram: FloatArray,
    val appearance: FloatArray = floatArrayOf(),
    val appearanceMask: FloatArray = floatArrayOf(),
)

interface BoardTracker {
    fun reconcile(prior: BoardState?, observation: BoardObservation): BoardState
}

interface MovePlanner {
    fun choose(state: BoardState, calibration: Calibration): MoveDecision?
}

data class SafetyContext(
    val state: BoardState,
    val calibration: Calibration,
    val move: MoveDecision,
    val foregroundPackage: String?,
    val configuredPackage: String,
    val nowMillis: Long,
    val lastFrameMillis: Long,
    val lastTapMillis: Long,
)

interface SafetyGate { fun evaluate(context: SafetyContext): SafetyDecision }
interface TapExecutor { suspend fun tap(coordinate: BoardCoordinate): TapResult }

interface SessionController {
    val state: StateFlow<SessionState>
    suspend fun run(configuredPackage: String, calibration: Calibration)
    suspend fun stop(reason: StopReason = StopReason.USER)
}

interface RetentionManager { suspend fun enforce() }
