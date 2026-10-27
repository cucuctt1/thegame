package dev.tilesolver.solver

import dev.tilesolver.core.BoardTracker
import dev.tilesolver.model.*

class DefaultBoardTracker : BoardTracker {
    override fun reconcile(prior: BoardState?, observation: BoardObservation): BoardState {
        val seen = observation.tray.sortedBy { it.value }
        val repeated = if (prior?.observedTray == seen) prior.trayConsistentFrames + 1 else 1
        val stable = repeated >= 3
        val stableObservation = if (stable || prior == null) observation else observation.copy(
            tray = prior.observation.tray,
            trayBounds = prior.observation.trayBounds,
            trayTiles = prior.observation.trayTiles,
        )
        return BoardState(
            observation = stableObservation,
            moveHistory = prior?.moveHistory.orEmpty(),
            // A repeated camera frame is not a failed gesture. The confirmation
            // gate owns gesture failure; waiting for animations must not stop play.
            noOpCount = 0,
            uncertainty = uncertainty(stableObservation),
            observedTray = seen,
            trayConsistentFrames = repeated.coerceAtMost(3),
            trayStable = stable,
        )
    }

    private fun uncertainty(observation: BoardObservation): Float {
        if (observation.tiles.isEmpty()) return 1f
        return observation.tiles.map { 1f - it.confidence }.average().toFloat().coerceIn(0f, 1f)
    }
}
