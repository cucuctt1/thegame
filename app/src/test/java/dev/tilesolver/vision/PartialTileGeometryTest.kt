package dev.tilesolver.vision

import dev.tilesolver.model.IntRect
import org.junit.Assert.*
import org.junit.Test

class PartialTileGeometryTest {
    @Test fun `side strip reconstructs full lower card but requires two visible edges`() {
        val lower = IntRect(100, 100, 180, 190)
        val upper = IntRect(135, 100, 215, 190)
        val strip = IntRect(100, 100, 135, 190)
        assertTrue(lower in PartialTileGeometry.proposals(strip, 80, 90))
        assertTrue(PartialTileGeometry.supported(lower, listOf(upper)) { x, y ->
            x == 100 || y == 100 || y == 189
        })
        assertFalse(PartialTileGeometry.supported(lower, listOf(upper)) { x, _ -> x == 100 })
    }

    @Test fun `opaque top tile gives no evidence about a fully hidden tile`() {
        val rect = IntRect(100, 100, 180, 190)
        assertFalse(PartialTileGeometry.supported(rect, listOf(rect)) { _, _ -> true })
    }
}
