package dev.tilesolver.safety

import dev.tilesolver.core.SafetyContext
import dev.tilesolver.model.*
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultSafetyGateTest {
    private val calibration = Calibration(1000, 2000, IntRect(100, 200, 900, 1400), IntRect(100, 1500, 900, 1700), listOf(IntRect(0, 0, 1000, 100)))
    private val board = BoardState(BoardObservation(9_900, 1, emptyList(), emptyList(), true, true))

    @Test fun `allows fresh in-board move for configured foreground package`() {
        assertTrue(gate(500f, 500f) is SafetyDecision.Allow)
    }

    @Test fun `stops coordinate outside board`() {
        assertTrue(gate(50f, 500f) is SafetyDecision.Stop)
    }

    @Test fun `stops when another package is foreground`() {
        assertTrue(gate(500f, 500f, foreground = "other") is SafetyDecision.Stop)
    }

    @Test fun `stale capture retries instead of stopping solver`() {
        val decision = DefaultSafetyGate().evaluate(
            SafetyContext(board, calibration, MoveDecision(TileId(1), BoardCoordinate(500f, 500f), 1.0, 1f, "test"), "game", "game", 20_000, 9_900, 9_000),
        )
        assertTrue(decision is SafetyDecision.Remap)
    }

    private fun gate(x: Float, y: Float, foreground: String = "game") = DefaultSafetyGate().evaluate(
        SafetyContext(board, calibration, MoveDecision(TileId(1), BoardCoordinate(x, y), 1.0, 1f, "test"), foreground, "game", 10_000, 9_900, 9_000),
    )
}
