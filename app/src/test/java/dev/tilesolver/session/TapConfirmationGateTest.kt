package dev.tilesolver.session

import dev.tilesolver.model.BoardCoordinate
import dev.tilesolver.model.BoardObservation
import dev.tilesolver.model.ClusterId
import dev.tilesolver.model.IntRect
import dev.tilesolver.model.MoveDecision
import dev.tilesolver.model.TileId
import dev.tilesolver.model.TileObservation
import dev.tilesolver.model.TileState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TapConfirmationGateTest {
    @Test fun confirmsWhenVisibleTrayChangesEvenIfIdentityDiffers() {
        val pending = pending(before = listOf(1), expected = listOf(1, 2))

        assertTrue(TapConfirmationGate.evaluate(pending, observation(listOf(1, 2))) is TapConfirmation.Confirmed)
        assertTrue(TapConfirmationGate.evaluate(pending, observation(listOf(1, 3))) is TapConfirmation.Confirmed)
    }

    @Test fun waitsWhenDetectorAlsoResurrectsAStaleTile() {
        val pending = pending(before = emptyList(), expected = listOf(17), cluster = 17)

        assertTrue(TapConfirmationGate.evaluate(pending, observation(listOf(16, 17))) is TapConfirmation.Wait)
    }

    @Test fun waitsForThreeUnchangedFramesBeforeSingleRetry() {
        val first = TapConfirmationGate.evaluate(pending(), observation(emptyList())) as TapConfirmation.Wait
        val second = TapConfirmationGate.evaluate(first.pending, observation(emptyList())) as TapConfirmation.Wait
        val third = TapConfirmationGate.evaluate(second.pending, observation(emptyList())) as TapConfirmation.Retry
        val fourth = TapConfirmationGate.evaluate(third.pending, observation(emptyList())) as TapConfirmation.Wait
        val fifth = TapConfirmationGate.evaluate(fourth.pending, observation(emptyList()))

        assertEquals(2, third.pending.gestureAttempts)
        assertTrue(fifth is TapConfirmation.Failed)
    }

    @Test fun `never retries when tray already has four tiles`() {
        var result: TapConfirmation = TapConfirmationGate.evaluate(
            pending(before = listOf(1, 2, 3, 4)),
            observation(listOf(1, 2, 3, 4)),
        )
        repeat(4) {
            assertTrue(result is TapConfirmation.Wait)
            result = TapConfirmationGate.evaluate((result as TapConfirmation.Wait).pending, observation(listOf(1, 2, 3, 4)))
        }
        assertTrue(result is TapConfirmation.Failed)
    }

    @Test fun waitsForTrayWhenBoardAlreadyChanged() {
        val result = TapConfirmationGate.evaluate(pending(), observation(emptyList(), selectedPresent = false))

        assertTrue(result is TapConfirmation.Wait)
    }

    @Test fun confirmsTripleRemovalUsingVisibleTray() {
        val pending = pending(before = listOf(4, 4), expected = emptyList(), cluster = 4)

        assertTrue(TapConfirmationGate.evaluate(pending, observation(emptyList())) is TapConfirmation.Confirmed)
    }

    @Test fun waitsForPartialTripleAnimationToFinish() {
        val pending = pending(before = listOf(4, 4, 9), expected = listOf(9), cluster = 4)

        assertTrue(TapConfirmationGate.evaluate(pending, observation(listOf(4, 9))) is TapConfirmation.Wait)
    }

    @Test fun waitsWhenRecognitionDropsAndRelabelsExistingTiles() {
        val pending = pending(before = listOf(240, 241, 19, 8), expected = listOf(240, 241, 19, 8, 8), cluster = 8)

        assertTrue(TapConfirmationGate.evaluate(pending, observation(listOf(19, 8, 8))) is TapConfirmation.Wait)
    }

    @Test fun relabelWithoutOccupancyChangeCannotConfirmTap() {
        assertTrue(TapConfirmationGate.evaluate(pending(before = listOf(1, 2)), observation(listOf(3, 3))) is TapConfirmation.Wait)
    }

    private fun pending(
        before: List<Int> = emptyList(),
        expected: List<Int> = listOf(7),
        cluster: Int = 7,
    ) = PendingTap(
        move = MoveDecision(TileId(10), BoardCoordinate(50f, 50f), 1.0, 1f, "test"),
        cluster = ClusterId(cluster),
        trayBefore = before.map(::ClusterId),
        expectedTray = expected.map(::ClusterId),
    )

    private fun observation(tray: List<Int>, selectedPresent: Boolean = true) = BoardObservation(
        timestampMillis = 1,
        frameSignature = 1,
        tiles = if (selectedPresent) listOf(
            TileObservation(
                id = TileId(10), bounds = IntRect(0, 0, 100, 100), visualCluster = ClusterId(7),
                confidence = 1f, state = TileState.EXPOSED,
            ),
        ) else emptyList(),
        tray = tray.map(::ClusterId),
        recognizedScreen = true,
        geometryValid = true,
    )
}
