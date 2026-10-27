package dev.tilesolver.solver

import dev.tilesolver.core.MovePlanner
import dev.tilesolver.model.*

/** Receding-horizon search over the visible occlusion graph and seven-slot tray. */
class BeamMovePlanner(
    private val depth: Int = 10,
    private val width: Int = 128,
    private val budgetMillis: Long = 140,
    private val nanoTime: () -> Long = System::nanoTime,
) : MovePlanner {
    private data class Node(
        val tray: List<ClusterId>,
        val remaining: List<TileObservation>,
        val removed: Set<TileId>,
        val first: TileObservation,
        val triples: Int,
        val confidenceCost: Double,
        val layerProgress: Double,
        val unlockBonus: Double,
        val depth: Int,
        val score: Double,
    )

    private data class StateKey(val tray: List<Int>, val removed: List<Long>)

    override fun choose(state: BoardState, calibration: Calibration): MoveDecision? {
        if (!state.trayStable) return null
        val started = nanoTime()
        val deadline = started + budgetMillis * 1_000_000
        val allTiles = state.observation.tiles.filter {
            calibration.board.contains(it.bounds)
        }
        // Unknown buried tiles still count as discoveries and obstruction targets.
        // They cannot be simulated as a matching pick until their face is observed.
        val layerValues = layerValues(allTiles)
        val unlocks = UnlockPotential.analyze(allTiles, state.observation.tray, calibration.trayCapacity,
            safe = { tile, tray -> robustCapacitySafe(tile, tray, calibration.trayCapacity) },
            expired = { nanoTime() >= started + budgetMillis * 1_000_000 / 4 },
        )
        val safeRoots = orderedCandidates(allTiles, emptySet(), state.observation.tray, unlocks.mapValues { it.value.value })
            .filter { robustCapacitySafe(it, state.observation.tray, calibration.trayCapacity) }
        // A certain immediate triple frees capacity and only removes blockers.
        // Do not defer that relief to collect speculative types elsewhere.
        val immediateClears = safeRoots.filter { certainlyClears(it, state.observation.tray) }
        val orderedRoots = immediateClears.ifEmpty { safeRoots }
        val roots = orderedRoots.mapNotNull { tile ->
            simulate(
                state.observation.tray, allTiles, emptySet(), tile, tile,
                0, 0.0, 1, calibration.trayCapacity, 0.0, layerValues, unlocks[tile.id]?.value ?: 0.0,
            )
        }
        if (roots.isEmpty()) return null

        val bestByFirst = mutableMapOf<TileId, Node>()
        val transpositions = mutableMapOf<StateKey, Double>()
        var frontier = roots.sortedByDescending(Node::score).take(width)
        frontier.forEach { keepBest(bestByFirst, it) }

        for (level in 1 until depth) {
            if (frontier.isEmpty() || nanoTime() >= deadline) break
            val next = ArrayList<Node>(width * 2)
            val iterationBest = mutableMapOf<TileId, Node>()
            var completed = true
            for (node in frontier) {
                if (nanoTime() >= deadline) { completed = false; break }
                for (pick in orderedCandidates(node.remaining, node.removed, node.tray)) {
                    if (nanoTime() >= deadline) { completed = false; break }
                    val child = simulate(
                        node.tray, node.remaining, node.removed, pick, node.first,
                        node.triples, node.confidenceCost, node.depth + 1, calibration.trayCapacity,
                        node.layerProgress, layerValues, node.unlockBonus,
                    ) ?: continue
                    val key = StateKey(
                        child.tray.map { it.value }.sorted(),
                        child.removed.map { it.value }.sorted(),
                    )
                    val priorScore = transpositions[key]
                    if (priorScore == null || child.score > priorScore) {
                        transpositions[key] = child.score
                        next += child
                        keepBest(iterationBest, child)
                    }
                }
            }
            // Do not let whichever root happened to run first win by being the
            // only one searched a level deeper when the time budget expires.
            if (!completed) break
            iterationBest.values.forEach { keepBest(bestByFirst, it) }
            frontier = next.sortedByDescending(Node::score).take(width)
        }

        val best = bestByFirst.values.maxWithOrNull(
            compareBy<Node> { it.remaining.isEmpty() && it.tray.isEmpty() }.thenBy { it.score }.thenBy { it.depth },
        ) ?: roots.maxByOrNull(Node::score) ?: return null
        val goal = unlocks[best.first.id]?.target
        return MoveDecision(
            tileId = best.first.id,
            coordinate = best.first.center,
            score = best.score,
            confidence = best.first.confidence,
            rationale = rationale(state.observation.tray, best.first, goal, best),
            goalTileId = goal?.id,
        )
    }

    private fun simulate(
        tray: List<ClusterId>,
        remaining: List<TileObservation>,
        removed: Set<TileId>,
        pick: TileObservation,
        first: TileObservation,
        triples: Int,
        confidenceCost: Double,
        nextDepth: Int,
        trayCapacity: Int,
        layerProgress: Double,
        layerValues: Map<TileId, Double>,
        unlockBonus: Double,
    ): Node? {
        val cluster = pick.visualCluster ?: return null
        if (!robustCapacitySafe(pick, tray, trayCapacity)) return null
        val nextTray = TrayModel.afterPick(tray, cluster, trayCapacity) ?: return null
        val nextRemoved = removed + pick.id
        val nextRemaining = remaining.filterNot { it.id == pick.id }
        // At six held cards the only next legal pick is a triple completion.
        // Require a known, identity-robust escape for EVERY plausible picked type;
        // unknown newly uncovered faces are not a promise of such an escape.
        val escapeTiles by lazy { legalTiles(nextRemaining, nextRemoved) }
        for (identity in plausibleClusters(pick)) {
            val possibleTray = TrayModel.afterPick(tray, identity, trayCapacity) ?: return null
            if (possibleTray.size == trayCapacity - 1 && escapeTiles.none { certainlyClears(it, possibleTray) }) return null
        }
        val nextTriples = triples + if (nextTray.size < tray.size) 1 else 0
        val ambiguity = (plausibleClusters(pick).size - 1).coerceAtLeast(0)
        val nextConfidenceCost = confidenceCost + (1f - pick.confidence.coerceIn(0f, 1f)) +
            ambiguity * (if (tray.size >= CAUTIOUS_TRAY_SIZE) 12.0 else 1.0) /
            nextDepth
        val nextLayerProgress = (layerProgress + layerValues.getOrDefault(pick.id, 0.0) *
            Math.pow(.82, (nextDepth - 1).toDouble())).coerceAtMost(120.0)
        val freeFraction = (trayCapacity - 1 - nextTray.size).toDouble() / (trayCapacity - 1).coerceAtLeast(1)
        val score = evaluate(
            nextTray, nextRemaining, nextRemoved, nextTriples, nextConfidenceCost, nextDepth,
        ) + nextLayerProgress * freeFraction * freeFraction + unlockBonus.coerceAtMost(120.0) / nextDepth
        return Node(
            nextTray, nextRemaining, nextRemoved, first, nextTriples,
            nextConfidenceCost, nextLayerProgress, unlockBonus, nextDepth, score,
        )
    }

    private fun evaluate(
        tray: List<ClusterId>,
        remaining: List<TileObservation>,
        removed: Set<TileId>,
        triples: Int,
        confidenceCost: Double,
        depth: Int,
    ): Double {
        val legal = legalTiles(remaining, removed)
        val trayCounts = tray.groupingBy { it }.eachCount()
        val remainingCounts = remaining.mapNotNull { it.visualCluster }.groupingBy { it }.eachCount()
        val legalCounts = legal.mapNotNull { it.visualCluster }.groupingBy { it }.eachCount()
        val singles = trayCounts.count { it.value == 1 }
        val pairs = trayCounts.count { it.value == 2 }
        val stranded = trayCounts.count { (cluster, count) ->
            remainingCounts.getOrDefault(cluster, 0) < 3 - count
        }
        val ready = trayCounts.count { (cluster, count) ->
            legalCounts.getOrDefault(cluster, 0) >= 3 - count
        }
        val nearUnlock = trayCounts.entries.fold(0) { total, entry ->
            val need = 3 - entry.value
            val value = remaining.asSequence()
                .filter { it.visualCluster == entry.key && it !in legal }
                .map { tile -> tile.occluders.count { it !in removed } }
                .minOrNull()
                ?.let { blockers -> if (blockers <= need + 1) need + 2 - blockers else 0 }
                ?: 0
            total + value
        }
        val immediateUnlocks = remaining.count { tile ->
            tile.state == TileState.BLOCKED && tile.occluders.isNotEmpty() && tile.occluders.all { it in removed }
        }
        val terminal = if (remaining.isEmpty()) {
            if (tray.isEmpty()) 100_000.0 else -2_000.0 - tray.size * 200
        } else 0.0

        return terminal +
            triples * 420.0 +
            removed.size * 13.0 +
            depth * 8.0 +
            ready * 105.0 +
            nearUnlock * 32.0 +
            pairs * 30.0 +
            immediateUnlocks * 16.0 +
            legal.size * 1.5 -
            tray.size * 48.0 -
            trayCounts.size * 26.0 -
            singles * 42.0 -
            stranded * 180.0 -
            confidenceCost * 45.0
    }

    /** Keep a few geometrically different representatives per visual type. */
    private fun orderedCandidates(
        tiles: List<TileObservation>,
        removed: Set<TileId>,
        tray: List<ClusterId>,
        unlockBonuses: Map<TileId, Double> = emptyMap(),
    ): List<TileObservation> {
        val legal = legalTiles(tiles, removed)
        // Score once, not on every comparison of two sorting passes.
        return legal.map { it to (moveOrder(it, tiles, removed, tray) + unlockBonuses.getOrDefault(it.id, 0.0)) }
            .groupBy { it.first.visualCluster }.values.flatMap { sameType ->
                sameType.sortedByDescending { it.second }.take(MAX_PER_TYPE)
            }.sortedByDescending { it.second }.map { it.first }
    }

    private fun moveOrder(
        tile: TileObservation,
        tiles: List<TileObservation>,
        removed: Set<TileId>,
        tray: List<ClusterId>,
    ): Double {
        val cluster = tile.visualCluster ?: return Double.NEGATIVE_INFINITY
        val inTray = tray.count { it == cluster }
        val directUnlocks = tiles.count { target ->
            tile.id in target.occluders && target.occluders.all { it == tile.id || it in removed }
        }
        val matchingBehind = tiles.count { target ->
            target.visualCluster == cluster && tile.id in target.occluders
        }
        val blockedByThis = tiles.filter { tile.id in it.occluders }
        val densePressure = blockedByThis.sumOf { target ->
            val remainingBlockers = target.occluders.count { it !in removed }.coerceAtLeast(1)
            1.0 / remainingBlockers
        }
        val denseWeight = (1.0 - tray.size.toDouble() / 7.0).coerceAtLeast(.15)
        val ambiguity = plausibleClusters(tile).size - 1
        return when (inTray) { 2 -> 1_000.0; 1 -> 300.0; else -> 0.0 } +
            directUnlocks * 90.0 + matchingBehind * 35.0 +
            densePressure * 85.0 * denseWeight + blockedByThis.size * 12.0 * denseWeight +
            tile.confidence * 25.0 -
            ambiguity * (if (tray.size >= CAUTIOUS_TRAY_SIZE) 240.0 else 35.0)
    }

    /** Identities close enough to the best visual match that planning must respect them. */
    private fun plausibleClusters(tile: TileObservation): Set<ClusterId> {
        val primary = tile.visualCluster ?: return emptySet()
        val bestCandidate = tile.candidates.maxOfOrNull { it.confidence } ?: tile.confidence
        return buildSet {
            add(primary)
            tile.candidates.asSequence()
                .filter { it.cluster != primary }
                .filter { it.confidence >= PLAUSIBLE_CONFIDENCE }
                .filter { bestCandidate - it.confidence <= PLAUSIBLE_MARGIN }
                .take(MAX_PLAUSIBLE_IDENTITIES - 1)
                .forEach { add(it.cluster) }
        }
    }

    /** A move is safe only if every plausible identity fits in the seven-slot tray. */
    private fun robustCapacitySafe(
        tile: TileObservation,
        tray: List<ClusterId>,
        capacity: Int,
    ): Boolean = plausibleClusters(tile).all { TrayModel.afterPick(tray, it, capacity) != null }

    private fun certainlyClears(tile: TileObservation, tray: List<ClusterId>): Boolean {
        val identities = plausibleClusters(tile)
        return identities.isNotEmpty() && identities.all { type -> tray.count { it == type } == 2 }
    }

    private fun legalTiles(tiles: List<TileObservation>, removed: Set<TileId>): List<TileObservation> =
        tiles.filter { tile ->
            tile.visualCluster != null && (
                (tile.state == TileState.EXPOSED && tile.occluders.isEmpty()) ||
                    (tile.occluders.isNotEmpty() && tile.occluders.all { it in removed })
                )
        }

    private fun layerValues(tiles: List<TileObservation>): Map<TileId, Double> {
        val children = mutableMapOf<TileId, MutableList<TileObservation>>()
        tiles.forEach { target -> target.occluders.forEach { blocker ->
            children.getOrPut(blocker) { mutableListOf() }.add(target)
        } }
        return tiles.associate { tile ->
            val visited = mutableSetOf(tile.id)
            val pending = ArrayDeque<Pair<TileId, Int>>()
            pending.add(tile.id to 0)
            var value = 0.0
            while (pending.isNotEmpty()) {
                val (parent, level) = pending.removeFirst()
                for (child in children[parent].orEmpty()) if (visited.add(child.id)) {
                    value += (if (child.visualCluster == null) 100.0 else 65.0) /
                        ((level + 1) * child.occluders.size.coerceAtLeast(1))
                    pending.add(child.id to level + 1)
                }
            }
            tile.id to value
        }
    }

    private fun keepBest(target: MutableMap<TileId, Node>, candidate: Node) {
        val current = target[candidate.first.id]
        if (current == null || candidate.score > current.score ||
            (candidate.score == current.score && candidate.depth > current.depth)
        ) target[candidate.first.id] = candidate
    }

    private fun rationale(
        tray: List<ClusterId>,
        tile: TileObservation,
        goal: TileObservation?,
        plan: Node,
    ): String = when {
        goal != null && goal.visualCluster == null -> "reveal covered tile"
        goal != null && tray.count { it == goal.visualCluster } == 2 -> "unlock covered triple"
        goal != null -> "unlock covered match"
        tray.count { it == tile.visualCluster } == 2 -> "complete triple"
        tray.count { it == tile.visualCluster } == 1 -> "extend pair"
        plan.triples > 0 -> "lookahead: route to ${plan.triples} triple(s)"
        else -> "lookahead: preserve tray capacity"
    }

    companion object {
        private const val MAX_PER_TYPE = 3
        private const val MAX_PLAUSIBLE_IDENTITIES = 3
        private const val CAUTIOUS_TRAY_SIZE = 4
        private const val PLAUSIBLE_CONFIDENCE = .70f
        private const val PLAUSIBLE_MARGIN = .08f
    }
}
