package dev.tilesolver.solver

import dev.tilesolver.model.*
import org.junit.Assert.*
import org.junit.Test

class UnlockPotentialTest {
    private val calibration = Calibration(1000, 2000, IntRect(0, 100, 1000, 1500), IntRect(0, 1600, 1000, 1800), emptyList())
    private fun tile(id: Long, type: Int?, vararg blockers: Long) = TileObservation(
        TileId(id), IntRect((id * 90).toInt(), 200, (id * 90 + 80).toInt(), 290), type?.let(::ClusterId), .95f,
        occluders = blockers.map(::TileId).toSet(), state = if (blockers.isEmpty()) TileState.EXPOSED else TileState.BLOCKED,
    )
    private fun analyze(tiles: List<TileObservation>, tray: List<Int>) = UnlockPotential.analyze(
        tiles, tray.map(::ClusterId), 7, safe = { tile, held ->
            tile.visualCluster?.let { TrayModel.afterPick(held, it, 7) != null } == true
        },
    )

    @Test fun `prefers blocker chain leading to a tray triple even with one-ply main search`() {
        val neutral = tile(1, 2)
        val blocker = tile(2, 2)
        val intermediate = tile(3, 3, 2)
        val target = tile(4, 1, 3)
        val tiles = listOf(neutral, blocker, intermediate, target)
        val state = BoardState(BoardObservation(0, 1, tiles, listOf(ClusterId(1), ClusterId(1)), true, true))
        val move = BeamMovePlanner(depth = 1, nanoTime = { 0L }).choose(state, calibration)
        assertEquals(blocker.id, move?.tileId)
        assertEquals(target.id, move?.goalTileId)
        assertEquals("unlock covered triple", move?.rationale)
    }

    @Test fun `considers every blocker not just the first covering card`() {
        val targets = listOf(tile(1, 2), tile(2, 3), tile(3, 1, 1, 2))
        val result = analyze(targets, listOf(1, 1))
        assertEquals(setOf(TileId(1), TileId(2)), result.keys)
        assertEquals(3, result.getValue(TileId(1)).steps)
        assertEquals(5, result.getValue(TileId(1)).peak)
    }

    @Test fun `does not reward a tempting triple behind an overflowing chain`() {
        val tiles = listOf(tile(1, 8), tile(2, 9, 1), tile(3, 1, 2))
        // Two blockers cannot fit before the desired pair can be cleared.
        val result = analyze(tiles, listOf(1, 1, 2, 3, 4))
        assertFalse(result.values.any { it.target.id == TileId(3) })
    }

    @Test fun `blocker matches can free capacity on the way to a deeper target`() {
        val tiles = listOf(tile(1, 2), tile(2, 3, 1), tile(3, 1, 2))
        val result = analyze(tiles, listOf(1, 1, 2, 2, 4))
        assertEquals(TileId(3), result.getValue(TileId(1)).target.id)
        assertEquals(6, result.getValue(TileId(1)).peak)
    }

    @Test fun `unknown face is discovery not a promised match or overflow escape`() {
        val tiles = listOf(tile(1, 2), tile(2, null, 1))
        assertTrue(analyze(tiles, emptyList()).isNotEmpty())
        assertTrue(analyze(tiles, listOf(1, 1, 3, 4, 5)).isEmpty())
    }

    @Test fun `unknown intermediate prevents a fabricated route to a known deep match`() {
        val tiles = listOf(tile(1, 2), tile(2, null, 1), tile(3, 1, 2))
        val result = analyze(tiles, listOf(1, 1))
        assertEquals(TileId(2), result.getValue(TileId(1)).target.id)
    }

    @Test fun `missing or cyclic blockers do not become unlock opportunities`() {
        assertTrue(analyze(listOf(tile(1, 1, 99)), listOf(1, 1)).isEmpty())
        assertTrue(analyze(listOf(tile(1, 1, 2), tile(2, 2, 1)), listOf(1, 1)).isEmpty())
    }
}
