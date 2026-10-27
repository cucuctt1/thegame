package dev.tilesolver.solver

import dev.tilesolver.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultBoardTrackerTest {
    @Test fun `retains prior tray when one contour is temporarily missed`() {
        val tracker = DefaultBoardTracker()
        val priorObservation = observation(listOf(1, 1, 2, 2, 3, 3))
        val prior = tracker.reconcile(null, priorObservation)

        val result = tracker.reconcile(prior, observation(listOf(1, 1, 2, 2, 3)))

        assertEquals(6, result.observation.tray.size)
    }

    @Test fun `accepts occupancy decrease caused by triple removal`() {
        val tracker = DefaultBoardTracker()
        val prior = tracker.reconcile(null, observation(listOf(1, 1, 2, 3, 4)))

        var result = prior
        repeat(3) { result = tracker.reconcile(result, observation(listOf(2, 3, 4))) }

        assertEquals(3, result.observation.tray.size)
        assertTrue(result.trayStable)
    }

    @Test fun `animation count cannot remain latched forever`() {
        val tracker = DefaultBoardTracker()
        var state = tracker.reconcile(null, observation(listOf(1, 1, 1, 2)))
        repeat(2) {
            state = tracker.reconcile(state, observation(listOf(2)))
            assertFalse(state.trayStable)
        }
        state = tracker.reconcile(state, observation(listOf(2)))
        assertEquals(listOf(ClusterId(2)), state.observation.tray)
        assertTrue(state.trayStable)
    }

    @Test fun `repeated observations without gestures never count as failed taps`() {
        val tracker = DefaultBoardTracker()
        var state = tracker.reconcile(null, observation(listOf(1)))
        repeat(20) { state = tracker.reconcile(state, observation(listOf(1))) }
        assertEquals(0, state.noOpCount)
    }

    @Test fun `same count with relabelled icons must settle before another tap`() {
        val tracker = DefaultBoardTracker()
        var state = tracker.reconcile(null, observation(listOf(1, 2)))
        repeat(3) { state = tracker.reconcile(state, observation(listOf(1, 2))) }
        state = tracker.reconcile(state, observation(listOf(1, 3)))
        assertFalse(state.trayStable)
    }

    private fun observation(tray: List<Int>) = BoardObservation(
        timestampMillis = 1,
        frameSignature = tray.hashCode().toLong(),
        tiles = listOf(TileObservation(TileId(1), IntRect(0, 0, 80, 90), ClusterId(9), 1f, state = TileState.EXPOSED)),
        tray = tray.map(::ClusterId),
        recognizedScreen = true,
        geometryValid = true,
    )
}
