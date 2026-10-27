package dev.tilesolver.session

import android.os.SystemClock
import android.util.Log
import dev.tilesolver.automation.SolverAccessibilityService
import dev.tilesolver.core.*
import dev.tilesolver.data.SessionDao
import dev.tilesolver.data.SessionEntity
import dev.tilesolver.model.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import dev.tilesolver.solver.TrayModel

class DefaultSessionController(
    private val frameSource: FrameSource,
    private val mapper: BoardMapper,
    private val tracker: BoardTracker,
    private val planner: MovePlanner,
    private val safety: SafetyGate,
    private val tapper: TapExecutor,
    private val sessions: SessionDao,
    private val retention: RetentionManager,
) : SessionController {
    private val mutableState = MutableStateFlow<SessionState>(
        SessionState.Stopped(StopReason.USER, "waiting for capture"),
    )
    override val state: StateFlow<SessionState> = mutableState.asStateFlow()
    private val mutex = Mutex()
    @Volatile private var stopRequested = false

    override suspend fun run(configuredPackage: String, calibration: Calibration) = mutex.withLock {
        stopRequested = false
        val started = System.currentTimeMillis()
        val deadline = SystemClock.elapsedRealtime() + SESSION_LIMIT_MS
        var board: BoardState? = null
        var lastSequence = -1L
        var lastTap = 0L
        var moves = 0
        var pendingTap: PendingTap? = null
        val startingUserTouchEpoch = SolverAccessibilityService.userTouchEpoch.get()
        var outcome = "stopped"
        try {
            // Resume with learned sprite identities, but rebuild board/tray state
            // from fresh observations. Startup calibration uses this same mapper.
            mapper.prepareForPackage(configuredPackage)
            while (!stopRequested && SystemClock.elapsedRealtime() < deadline) {
                if (SolverAccessibilityService.userTouchEpoch.get() != startingUserTouchEpoch) {
                    mutableState.value = SessionState.Stopped(StopReason.USER, "user touched the screen")
                    outcome = "user-touch"
                    return@withLock
                }
                mutableState.value = SessionState.Observing
                val frame = withTimeoutOrNull((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1)) {
                    frameSource.frames.first { it.sequence > lastSequence }
                } ?: break
                lastSequence = frame.sequence
                val rawObservation = try { mapper.map(frame, calibration, board) } finally { frame.close() }
                val priorTileCount = board?.observation?.tiles?.size
                val reconciled = tracker.reconcile(board, rawObservation)
                board = reconciled
                val observation = reconciled.observation
                Log.i(
                    TAG,
                    "frame=${frame.sequence} board=${observation.tiles.size} exposed=${reconciled.exposed.size} " +
                        "trayVision=${rawObservation.tray.map { it.value }} stable=${reconciled.trayStable} " +
                        "blocked=${observation.tiles.count { it.state == TileState.BLOCKED }} " +
                        "edges=${observation.tiles.sumOf { it.occluders.size }} pending=${pendingTap != null}",
                )
                if (!reconciled.trayStable) {
                    mutableState.value = SessionState.Paused("waiting for tray animation to settle")
                    delay(100)
                    continue
                }
                if (observation.tray.size >= calibration.trayCapacity) {
                    mutableState.value = SessionState.Lost; outcome = "lost"; return@withLock
                }
                val awaiting = pendingTap
                if (awaiting != null) {
                    when (val confirmation = TapConfirmationGate.evaluate(awaiting, observation)) {
                        TapConfirmation.Confirmed -> {
                            Log.i(TAG, "tap confirmed tile=${awaiting.move.tileId.value} tray=${observation.tray.map { it.value }}")
                            pendingTap = null
                            moves++
                        }
                        is TapConfirmation.Retry -> {
                            mutableState.value = SessionState.Verifying(awaiting.move)
                            Log.w(TAG, "tap unconfirmed; retrying tile=${awaiting.move.tileId.value}")
                            when (val retry = tapper.tap(awaiting.coordinate)) {
                                TapResult.Completed -> {
                                    pendingTap = confirmation.pending
                                    lastTap = SystemClock.elapsedRealtime()
                                    delay(120)
                                    continue
                                }
                                TapResult.Cancelled -> {
                                    mutableState.value = SessionState.Stopped(StopReason.USER, "gesture cancelled")
                                    return@withLock
                                }
                                is TapResult.Failed -> {
                                    mutableState.value = SessionState.Fault(retry.reason)
                                    outcome = retry.reason
                                    return@withLock
                                }
                            }
                        }
                        is TapConfirmation.Wait -> {
                            pendingTap = confirmation.pending
                            mutableState.value = SessionState.Verifying(awaiting.move)
                            delay(100)
                            continue
                        }
                        is TapConfirmation.Failed -> {
                            Log.e(TAG, confirmation.detail)
                            mutableState.value = SessionState.Stopped(StopReason.NO_OP, confirmation.detail)
                            outcome = "unconfirmed-tap"
                            return@withLock
                        }
                    }
                }
                if (observation.tiles.isEmpty() && moves > 0 && priorTileCount != null && priorTileCount <= 3) {
                    mutableState.value = SessionState.Won
                    outcome = "won"
                    return@withLock
                }
                if (!observation.recognizedScreen || !observation.geometryValid) {
                    mutableState.value = SessionState.Paused("waiting for a stable recognized board")
                    delay(350)
                    continue
                }
                if (board.noOpCount == 2) mapper.clearCaches()
                mutableState.value = SessionState.Planning
                val move = planner.choose(board, calibration)
                if (move == null) {
                    if (observation.tiles.isEmpty() && moves > 0) {
                        mutableState.value = SessionState.Won
                        outcome = "won"
                        return@withLock
                    }
                    mutableState.value = SessionState.Paused("waiting for a safe visible move")
                    delay(350)
                    continue
                }
                val now = SystemClock.elapsedRealtime()
                when (val decision = safety.evaluate(SafetyContext(
                    board, calibration, move, SolverAccessibilityService.foregroundPackage.get(), configuredPackage,
                    now, observation.timestampMillis, lastTap,
                ))) {
                    SafetyDecision.Allow -> Unit
                    is SafetyDecision.Remap -> { mutableState.value = SessionState.Paused(decision.reason); delay(250); continue }
                    is SafetyDecision.Stop -> { mutableState.value = SessionState.Stopped(StopReason.UNSAFE_GEOMETRY, decision.reason); outcome = decision.reason; return@withLock }
                }
                mutableState.value = SessionState.Tapping(move)
                Log.i(TAG, "tap tile=${move.tileId.value} reason=${move.rationale} score=${move.score} goal=${move.goalTileId?.value}")
                when (val result = tapper.tap(move.coordinate)) {
                    TapResult.Completed -> {
                        val pickedCluster = board.observation.tiles.firstOrNull { it.id == move.tileId }?.visualCluster
                        if (pickedCluster == null) {
                            mutableState.value = SessionState.Fault("selected tile lost its visual type")
                            outcome = "missing-cluster"
                            return@withLock
                        }
                        val before = observation.tray
                        val after = TrayModel.afterPick(before, pickedCluster, calibration.trayCapacity)
                        if (after == null) {
                            mutableState.value = SessionState.Paused("tracked tray has no safe slot")
                            outcome = "tray-capacity-guard"
                            return@withLock
                        }
                        pendingTap = PendingTap(move, pickedCluster, before, after)
                        lastTap = SystemClock.elapsedRealtime()
                        mutableState.value = SessionState.Verifying(move)
                        delay(120)
                    }
                    TapResult.Cancelled -> { mutableState.value = SessionState.Stopped(StopReason.USER, "gesture cancelled"); return@withLock }
                    is TapResult.Failed -> { mutableState.value = SessionState.Fault(result.reason); outcome = result.reason; return@withLock }
                }
            }
            if (SystemClock.elapsedRealtime() >= deadline) {
                mutableState.value = SessionState.Stopped(StopReason.TIMEOUT, "20-minute limit reached")
                outcome = "timeout"
            }
        } catch (t: Throwable) {
            mutableState.value = SessionState.Fault(t.message ?: t.javaClass.simpleName)
            outcome = "fault:${t.javaClass.simpleName}"
        } finally {
            sessions.insert(SessionEntity(startedAt = started, endedAt = System.currentTimeMillis(), outcome = outcome, moves = moves))
            retention.enforce()
        }
    }

    override suspend fun stop(reason: StopReason) { stopRequested = true; mutableState.value = SessionState.Stopped(reason) }
    fun fail(error: Throwable) { mutableState.value = SessionState.Fault(error.message ?: error.javaClass.simpleName) }

    companion object {
        internal const val SESSION_LIMIT_MS = 20 * 60 * 1000L
        private const val TAG = "TileSolverSession"
    }
}
