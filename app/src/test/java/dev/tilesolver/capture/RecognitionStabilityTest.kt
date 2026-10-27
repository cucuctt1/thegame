package dev.tilesolver.capture

import dev.tilesolver.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecognitionStabilityTest {
    @Test
    fun `requires three stable valid frames in target app`() {
        // Given a new recognition gate and a valid three-card board
        val stability = RecognitionStability(requiredStableFrames = 3)
        val observation = recognizedBoard(tileCount = 3)

        // When the same valid board is observed three times in the target app
        val first = stability.observe(observation, targetIsForeground = true)
        val second = stability.observe(observation, targetIsForeground = true)
        val third = stability.observe(observation, targetIsForeground = true)

        // Then readiness is withheld until the third stable frame
        assertFalse(first.ready)
        assertFalse(second.ready)
        assertTrue(third.ready)
        assertEquals(3, third.stableFrames)
    }

    @Test
    fun `wrong foreground or invalid board resets stability`() {
        // Given recognition that is almost stable
        val stability = RecognitionStability(requiredStableFrames = 3)
        val observation = recognizedBoard(tileCount = 3)
        stability.observe(observation, targetIsForeground = true)
        stability.observe(observation, targetIsForeground = true)

        // When the target app leaves the foreground and later returns
        val reset = stability.observe(observation, targetIsForeground = false)
        val restarted = stability.observe(observation, targetIsForeground = true)

        // Then the old loading/foreground evidence cannot arm the solver
        assertEquals(0, reset.stableFrames)
        assertFalse(reset.ready)
        assertEquals(1, restarted.stableFrames)
        assertFalse(restarted.ready)
    }

    @Test
    fun `changing type ids do not block stable geometry`() {
        val stability = RecognitionStability(requiredStableFrames = 3)

        stability.observe(recognizedBoard(3), targetIsForeground = true)
        val changed = stability.observe(recognizedBoard(3).copy(frameSignature = 99), targetIsForeground = true)

        assertEquals(2, changed.stableFrames)
        assertFalse(changed.ready)
    }

    @Test
    fun `moving card geometry resets stability`() {
        val stability = RecognitionStability(requiredStableFrames = 3)
        val original = recognizedBoard(3)
        stability.observe(original, targetIsForeground = true)
        stability.observe(original, targetIsForeground = true)

        val moved = original.copy(tiles = original.tiles.mapIndexed { index, tile ->
            if (index == 0) tile.copy(bounds = IntRect(80, 80, 88, 88)) else tile
        })
        val result = stability.observe(moved, targetIsForeground = true)

        assertEquals(1, result.stableFrames)
        assertFalse(result.ready)
    }

    private fun recognizedBoard(tileCount: Int): BoardObservation = BoardObservation(
        timestampMillis = 1L,
        frameSignature = tileCount.toLong(),
        tiles = List(tileCount) { index ->
            TileObservation(
                id = TileId(index.toLong()),
                bounds = IntRect(index * 10, 10, index * 10 + 8, 18),
                visualCluster = ClusterId(index),
                confidence = .9f,
                state = TileState.EXPOSED,
            )
        },
        tray = emptyList(),
        recognizedScreen = true,
        geometryValid = true,
    )
}
