package dev.tilesolver.session

import dev.tilesolver.model.BoardCoordinate
import dev.tilesolver.model.BoardObservation
import dev.tilesolver.model.ClusterId
import dev.tilesolver.model.MoveDecision

internal data class PendingTap(
    val move: MoveDecision,
    val cluster: ClusterId,
    val trayBefore: List<ClusterId>,
    val expectedTray: List<ClusterId>,
    val coordinate: BoardCoordinate = move.coordinate,
    val gestureAttempts: Int = 1,
    val verificationFrames: Int = 0,
)

internal sealed interface TapConfirmation {
    data object Confirmed : TapConfirmation
    data class Retry(val pending: PendingTap) : TapConfirmation
    data class Wait(val pending: PendingTap) : TapConfirmation
    data class Failed(val detail: String) : TapConfirmation
}

internal object TapConfirmationGate {
    fun evaluate(pending: PendingTap, observation: BoardObservation): TapConfirmation {
        if (bracketChangedAsExpected(pending, observation.tray)) return TapConfirmation.Confirmed

        val selectedTileStillPresent = observation.tiles.any {
            it.id == pending.move.tileId && it.visualCluster == pending.cluster
        }
        val nextFrame = pending.verificationFrames + 1

        return when {
            // A completed accessibility gesture can take several captured frames to
            // reach the tray. Retapping immediately may hit the tile newly uncovered
            // at the same coordinate and silently add two tiles. Never retry when the
            // tray is already crowded, and require multiple unchanged observations
            // before the single low-risk retry.
            selectedTileStillPresent &&
                sameContents(observation.tray, pending.trayBefore) &&
                pending.trayBefore.size < RETRY_TRAY_LIMIT &&
                pending.gestureAttempts < MAX_GESTURE_ATTEMPTS &&
                nextFrame >= FRAMES_BEFORE_RETRY ->
                TapConfirmation.Retry(
                    pending.copy(gestureAttempts = pending.gestureAttempts + 1, verificationFrames = nextFrame),
                )
            nextFrame < MAX_VERIFICATION_FRAMES ->
                TapConfirmation.Wait(pending.copy(verificationFrames = nextFrame))
            else -> TapConfirmation.Failed(
                "tap was not confirmed by the visible bracket " +
                    "(before=${values(pending.trayBefore)}, expected=${values(pending.expectedTray)}, " +
                    "seen=${values(observation.tray)})",
            )
        }
    }

    private fun bracketChangedAsExpected(pending: PendingTap, seen: List<ClusterId>): Boolean {
        // After settling, one pick adds one tile or removes a triple (net -2).
        // Relabels with no count change and partial removal animations are not an
        // acknowledgement: accepting them can dispatch the next move too soon.
        val delta = seen.size - pending.trayBefore.size
        return delta == 1 || delta == -2
    }

    private fun sameContents(left: List<ClusterId>, right: List<ClusterId>): Boolean =
        left.map { it.value }.sorted() == right.map { it.value }.sorted()

    private fun values(items: List<ClusterId>) = items.map { it.value }

    private const val MAX_GESTURE_ATTEMPTS = 2
    private const val FRAMES_BEFORE_RETRY = 3
    private const val MAX_VERIFICATION_FRAMES = 5
    private const val RETRY_TRAY_LIMIT = 4
}
