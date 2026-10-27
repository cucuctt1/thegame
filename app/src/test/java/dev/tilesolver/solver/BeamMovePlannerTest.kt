package dev.tilesolver.solver

import dev.tilesolver.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BeamMovePlannerTest {
    private val calibration = Calibration(1000, 2000, IntRect(0, 100, 1000, 1500), IntRect(0, 1600, 1000, 1800), emptyList())

    @Test fun `completes a triple before introducing a new type`() {
        val pair = ClusterId(1); val fresh = ClusterId(2)
        val winning = tile(1, 100, pair); val risky = tile(2, 300, fresh)
        val state = state(listOf(winning, risky), listOf(pair, pair))
        assertEquals(winning.id, BeamMovePlanner().choose(state, calibration)?.tileId)
    }

    @Test fun `never selects a blocked tile`() {
        val blocked = tile(1, 100, ClusterId(1), TileState.BLOCKED)
        val legal = tile(2, 300, ClusterId(2))
        assertEquals(legal.id, BeamMovePlanner().choose(state(listOf(blocked, legal)), calibration)?.tileId)
    }

    @Test fun `returns no move when all tiles blocked`() {
        val state = state(listOf(tile(1, 100, ClusterId(1), TileState.BLOCKED)))
        assertTrue(BeamMovePlanner().choose(state, calibration) == null)
    }

    @Test fun `does not fill the seventh tray slot with an unmatched type`() {
        val sixDifferentItems = (1..6).map(::ClusterId)
        val fresh = tile(1, 100, ClusterId(7))

        assertTrue(BeamMovePlanner().choose(state(listOf(fresh), sixDifferentItems), calibration) == null)
    }

    @Test fun `allows a seventh pick when it completes and clears a triple`() {
        val pair = ClusterId(1)
        val tray = listOf(pair, pair, ClusterId(2), ClusterId(3), ClusterId(4), ClusterId(5))
        val completing = tile(1, 100, pair)

        assertEquals(completing.id, BeamMovePlanner().choose(state(listOf(completing), tray), calibration)?.tileId)
    }

    @Test fun `prefers a move that unlocks blocked tiles`() {
        val revealing = tile(1, 100, ClusterId(1))
        val neutral = tile(2, 300, ClusterId(2))
        val blockedA = tile(3, 500, ClusterId(3), TileState.BLOCKED).copy(occluders = setOf(revealing.id))
        val blockedB = tile(4, 700, ClusterId(3), TileState.BLOCKED).copy(occluders = setOf(revealing.id))

        assertEquals(revealing.id, BeamMovePlanner().choose(state(listOf(revealing, neutral, blockedA, blockedB)), calibration)?.tileId)
    }

    @Test fun `aims at blocker of a covered tile that completes tray triple`() {
        val pair = ClusterId(1)
        val blocker = tile(1, 100, ClusterId(2))
        val neutral = tile(2, 300, ClusterId(3))
        val coveredGoal = tile(3, 500, pair, TileState.BLOCKED).copy(
            occluders = setOf(blocker.id),
            confidence = .8f,
        )

        val move = BeamMovePlanner().choose(state(listOf(blocker, neutral, coveredGoal), listOf(pair, pair)), calibration)

        assertEquals(blocker.id, move?.tileId)
        assertEquals(coveredGoal.id, move?.goalTileId)
        assertEquals("unlock covered triple", move?.rationale)
    }

    @Test fun `at high occupancy chooses a survivable triple route`() {
        val pair = ClusterId(1)
        val tray = listOf(pair, pair, ClusterId(2), ClusterId(3), ClusterId(4), ClusterId(5))
        val completing = tile(1, 100, pair)
        val fresh = tile(2, 300, ClusterId(6))

        val move = BeamMovePlanner().choose(state(listOf(fresh, completing), tray), calibration)

        assertEquals(completing.id, move?.tileId)
    }

    @Test fun `at high occupancy prefers identity robust match over ambiguous predicted match`() {
        val pair = ClusterId(1)
        val tray = listOf(pair, pair, ClusterId(2), ClusterId(3))
        val ambiguous = tile(1, 100, pair).copy(
            candidates = listOf(
                ClusterCandidate(pair, .81f),
                ClusterCandidate(ClusterId(9), .76f),
            ),
        )
        val robust = tile(2, 300, ClusterId(2)).copy(
            candidates = listOf(ClusterCandidate(ClusterId(2), .90f)),
        )

        assertEquals(robust.id, BeamMovePlanner().choose(state(listOf(ambiguous, robust), tray), calibration)?.tileId)
    }

    @Test fun `at six slots rejects a move whose alternate identity would overflow`() {
        val pair = ClusterId(1)
        val tray = listOf(pair, pair, ClusterId(2), ClusterId(3), ClusterId(4), ClusterId(5))
        val ambiguous = tile(1, 100, pair).copy(
            candidates = listOf(
                ClusterCandidate(pair, .82f),
                ClusterCandidate(ClusterId(9), .76f),
            ),
        )

        assertTrue(BeamMovePlanner().choose(state(listOf(ambiguous), tray), calibration) == null)
    }

    @Test fun `early game prioritizes a tile embedded in a dense blocked stack`() {
        val dense = tile(1, 100, ClusterId(1))
        val neutral = tile(2, 700, ClusterId(2))
        val otherBlocker = TileId(99)
        val covered = (3L..6L).mapIndexed { index, id ->
            tile(id, 180 + index * 80, ClusterId(10 + index), TileState.BLOCKED).copy(
                occluders = setOf(dense.id, otherBlocker),
            )
        }

        val move = BeamMovePlanner(depth = 1).choose(state(listOf(dense, neutral) + covered), calibration)

        assertEquals(dense.id, move?.tileId)
    }

    @Test fun `unknown buried tiles contribute to discovery without becoming imaginary matches`() {
        val isolated = tile(1, 100, ClusterId(1))
        val covering = tile(2, 300, ClusterId(1))
        val unknown = tile(3, 350, ClusterId(8), TileState.BLOCKED).copy(
            visualCluster = null, confidence = 0f, occluders = setOf(covering.id),
        )
        assertEquals(covering.id, BeamMovePlanner(depth = 2).choose(state(listOf(isolated, covering, unknown)), calibration)?.tileId)
    }

    @Test fun `does not plan through an unsettled tray`() {
        assertTrue(BeamMovePlanner().choose(state(listOf(tile(1, 100, ClusterId(1)))).copy(trayStable = false), calibration) == null)
    }

    @Test fun `budget expiry mid iteration retains last fully compared search level`() {
        val state = state(listOf(tile(1, 100, ClusterId(1)), tile(2, 300, ClusterId(2)), tile(3, 500, ClusterId(2))), listOf(ClusterId(1)))
        val baseline = BeamMovePlanner(depth = 1, nanoTime = { 0L }).choose(state, calibration)
        var calls = 0
        val interrupted = BeamMovePlanner(depth = 5, budgetMillis = 1, nanoTime = {
            if (++calls <= 4) 0L else 2_000_000L
        }).choose(state, calibration)
        assertEquals(baseline, interrupted)
    }

    @Test fun `does not spend last spare slot on discovery without a known escape`() {
        val fresh = tile(1, 100, ClusterId(6))
        val unknown = tile(2, 300, ClusterId(9), TileState.BLOCKED).copy(
            visualCluster = null, occluders = setOf(fresh.id), confidence = 0f,
        )
        val tray = listOf(1, 1, 2, 3, 4).map(::ClusterId)
        assertEquals(null, BeamMovePlanner(nanoTime = { 0L }).choose(state(listOf(fresh, unknown), tray), calibration))
    }

    @Test fun `last spare slot can unlock a known immediate triple clear`() {
        val blocker = tile(1, 100, ClusterId(6))
        val goal = tile(2, 300, ClusterId(1), TileState.BLOCKED).copy(occluders = setOf(blocker.id))
        val tray = listOf(1, 1, 2, 3, 4).map(::ClusterId)
        assertEquals(blocker.id, BeamMovePlanner(nanoTime = { 0L }).choose(state(listOf(blocker, goal), tray), calibration)?.tileId)
    }

    @Test fun `uncertain escape identity cannot justify using last spare slot`() {
        val blocker = tile(1, 100, ClusterId(6))
        val goal = tile(2, 300, ClusterId(1), TileState.BLOCKED).copy(
            occluders = setOf(blocker.id),
            candidates = listOf(ClusterCandidate(ClusterId(1), .85f), ClusterCandidate(ClusterId(8), .81f)),
        )
        val tray = listOf(1, 1, 2, 3, 4).map(::ClusterId)
        assertEquals(null, BeamMovePlanner(nanoTime = { 0L }).choose(state(listOf(blocker, goal), tray), calibration))
    }

    @Test fun `large speculative stack cannot outweigh an available certain triple`() {
        val clear = tile(1, 100, ClusterId(1))
        val explore = tile(2, 300, ClusterId(2))
        val hidden = (3L..30L).map { id ->
            tile(id, 500, ClusterId(9), TileState.BLOCKED).copy(
                visualCluster = null, confidence = 0f, occluders = setOf(explore.id),
            )
        }
        val tray = listOf(1, 1, 3, 4).map(::ClusterId)
        assertEquals(clear.id, BeamMovePlanner(depth = 1, nanoTime = { 0L }).choose(state(listOf(clear, explore) + hidden, tray), calibration)?.tileId)
    }

    @Test fun `replanning clears a layered board without accumulating unrelated singles`() {
        fun card(id: Long, type: Int, blocker: Long? = null) = tile(id, 100 + id.toInt() * 70, ClusterId(type),
            if (blocker == null) TileState.EXPOSED else TileState.BLOCKED).copy(
            occluders = blocker?.let { setOf(TileId(it)) }.orEmpty(),
        )
        var remaining = listOf(card(1, 1), card(2, 2), card(3, 1), card(4, 2, 1), card(5, 1, 2),
            card(6, 3, 3), card(7, 2, 4), card(8, 3, 5), card(9, 3, 6))
        var tray = emptyList<ClusterId>()
        val planner = BeamMovePlanner(nanoTime = { 0L })
        repeat(9) {
            val move = requireNotNull(planner.choose(state(remaining, tray), calibration))
            val picked = remaining.single { it.id == move.tileId }
            assertTrue(picked.state == TileState.EXPOSED && picked.occluders.isEmpty())
            tray = requireNotNull(TrayModel.afterPick(tray, requireNotNull(picked.visualCluster), 7))
            remaining = remaining.filterNot { it.id == picked.id }.map { tile ->
                val blockers = tile.occluders - picked.id
                tile.copy(occluders = blockers, state = if (blockers.isEmpty()) TileState.EXPOSED else TileState.BLOCKED)
            }
        }
        assertTrue(remaining.isEmpty() && tray.isEmpty())
    }

    private fun tile(id: Long, x: Int, cluster: ClusterId, state: TileState = TileState.EXPOSED) = TileObservation(
        TileId(id), IntRect(x, 200, x + 90, 300), cluster, .95f, state = state,
    )
    private fun state(tiles: List<TileObservation>, tray: List<ClusterId> = emptyList()) = BoardState(
        BoardObservation(100, 1, tiles, tray, true, true),
    )
}
