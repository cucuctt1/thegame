package dev.tilesolver.vision

import dev.tilesolver.model.IntRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TileOcclusionTest {
    @Test fun `screen ordering cannot invent depth between two dim cards`() {
        val a = IntRect(100, 100, 180, 190)
        val b = IntRect(140, 140, 220, 230)
        assertTrue(TileOcclusion.occludersOf(1, listOf(a, b), listOf(true, true)).isEmpty())
    }
    @Test
    fun `upper overlapping tile blocks lower tile`() {
        val upperBall = IntRect(320, 915, 400, 1005)
        val lowerBlueTile = IntRect(360, 958, 440, 1048)

        assertEquals(listOf(upperBall), TileOcclusion.occludersOf(1, listOf(upperBall, lowerBlueTile), listOf(false, true)))
        assertTrue(TileOcclusion.occludersOf(0, listOf(upperBall, lowerBlueTile), listOf(false, true)).isEmpty())
    }

    @Test
    fun `bright overlapping tile blocks dim tile regardless of screen order`() {
        val lockedHigherOnScreen = IntRect(320, 900, 400, 990)
        val exposedLowerOnScreen = IntRect(360, 940, 440, 1030)

        assertEquals(
            listOf(exposedLowerOnScreen),
            TileOcclusion.occludersOf(0, listOf(lockedHigherOnScreen, exposedLowerOnScreen), listOf(true, false)),
        )
    }
}
