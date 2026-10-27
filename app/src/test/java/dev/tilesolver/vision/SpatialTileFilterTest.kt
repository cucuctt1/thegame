package dev.tilesolver.vision

import dev.tilesolver.model.IntRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SpatialTileFilterTest {
    @Test
    fun `isolated card-shaped control is excluded from board cluster`() {
        // Given a connected six-card stack and an isolated card-sized plus control
        val cards = listOf(
            IntRect(100, 500, 180, 580), IntRect(180, 500, 260, 580), IntRect(260, 500, 340, 580),
            IntRect(140, 580, 220, 660), IntRect(220, 580, 300, 660), IntRect(180, 660, 260, 740),
        )
        val plusControl = IntRect(600, 280, 680, 360)

        // When spatial filtering selects the board cluster
        val filtered = SpatialTileFilter.largestConnectedCluster(cards + plusControl)

        // Then the connected cards remain and the isolated control cannot be planned
        assertEquals(cards.size, filtered.size)
        assertFalse(plusControl in filtered)
    }

    @Test
    fun `tracked isolated endgame cards survive connected-cluster filtering`() {
        val connected = listOf(
            IntRect(100, 500, 180, 590),
            IntRect(170, 520, 250, 610),
        )
        val isolatedLeftover = IntRect(520, 900, 600, 990)

        val filtered = SpatialTileFilter.retainTracked(
            connected + isolatedLeftover,
            tracked = listOf(IntRect(518, 898, 598, 988)),
        )

        assertEquals(3, filtered.size)
        assertEquals(true, isolatedLeftover in filtered)
    }

    @Test
    fun `newly exposed standalone card inside prior board envelope is retained`() {
        val connected = listOf(
            IntRect(180, 500, 260, 590),
            IntRect(250, 520, 330, 610),
        )
        val standalone = IntRect(360, 650, 440, 740)
        val priorBoard = connected + listOf(IntRect(340, 590, 420, 680))

        val filtered = SpatialTileFilter.retainTracked(connected + standalone, priorBoard)

        assertEquals(true, standalone in filtered)
    }
}
