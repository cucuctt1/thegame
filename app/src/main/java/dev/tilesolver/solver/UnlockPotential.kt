package dev.tilesolver.solver

import dev.tilesolver.model.*

/** Bounded, goal-directed search through the *observed* underlayer graph.
 * A bonus requires a legal blocker-removal route, not merely geometric overlap.
 * Unknown faces may be uncovered but are never used as hypothetical matches.
 */
internal object UnlockPotential {
    data class Opportunity(val target: TileObservation, val value: Double, val steps: Int, val peak: Int)
    private data class Route(
        val tray: List<ClusterId>, val removed: Set<TileId>, val first: TileId?,
        val peak: Int, val triples: Int, val confidence: Float,
    )

    fun analyze(
        tiles: List<TileObservation>, tray: List<ClusterId>, capacity: Int,
        safe: (TileObservation, List<ClusterId>) -> Boolean,
        expired: () -> Boolean = { false },
    ): Map<TileId, Opportunity> {
        val byId = tiles.associateBy { it.id }
        val results = mutableMapOf<TileId, Opportunity>()
        val targets = tiles.filter { it.state == TileState.BLOCKED && it.occluders.isNotEmpty() }
            .sortedByDescending { target ->
                when (tray.count { it == target.visualCluster }) { 2 -> 300; 1 -> 100; else -> 0 } +
                    if (target.visualCluster == null) 40 else 20
            }.take(12)
        for (target in targets) {
            if (expired()) break
            val ancestors = ancestors(target, byId) ?: continue
            if (ancestors.size > 8) continue
            var routes = listOf(Route(tray, emptySet(), null, tray.size, 0, 1f))
            val seen = mutableSetOf<Pair<Set<TileId>, TileId?>>()
            repeat(ancestors.size + 1) {
                val next = mutableListOf<Route>()
                for (route in routes) {
                    if (expired()) return results
                    val unlocked = ancestors.all { it in route.removed }
                    if (unlocked) {
                        val first = route.first ?: continue
                        val type = target.visualCluster
                        val after = if (type == null) route.tray else {
                            if (!safe(target, route.tray)) continue
                            TrayModel.afterPick(route.tray, type, capacity) ?: continue
                        }
                        val completes = type != null && after.size < route.tray.size
                        val steps = route.removed.size + if (type == null) 0 else 1
                        // Occupancy includes the instant before a triple disappears.
                        val peak = maxOf(route.peak, route.tray.size + if (type == null) 0 else 1)
                        val headroom = capacity - 1 - after.size
                        if (headroom <= 0 && !completes) continue
                        val benefit = when {
                            completes -> 320.0
                            type == null -> 45.0
                            after.count { it == type } == 2 -> 90.0
                            else -> 25.0
                        }
                        val confidence = if (type == null) route.confidence else minOf(route.confidence, target.confidence)
                        val value = (benefit + route.triples * 80) * confidence *
                            (1.0 - (peak - 3).coerceAtLeast(0) * .14) / (1 + .30 * steps)
                        val opportunity = Opportunity(target, value, steps, peak)
                        if (value > (results[first]?.value ?: 0.0)) results[first] = opportunity
                        continue
                    }
                    for (id in ancestors) {
                        if (id in route.removed) continue
                        val tile = byId.getValue(id)
                        val type = tile.visualCluster ?: continue
                        val legal = if (tile.occluders.isEmpty()) tile.state == TileState.EXPOSED
                            else tile.occluders.all { it in route.removed }
                        if (!legal || !safe(tile, route.tray)) continue
                        val after = TrayModel.afterPick(route.tray, type, capacity) ?: continue
                        val removed = route.removed + id
                        val first = route.first ?: id
                        if (!seen.add(removed to first)) continue
                        next += Route(after, removed, first, maxOf(route.peak, route.tray.size + 1),
                            route.triples + if (after.size < route.tray.size) 1 else 0,
                            minOf(route.confidence, tile.confidence))
                    }
                }
                routes = next.sortedWith(compareBy<Route> { it.tray.size }.thenBy { it.peak })
                    .take(48)
            }
        }
        return results
    }

    private fun ancestors(target: TileObservation, byId: Map<TileId, TileObservation>): Set<TileId>? {
        val result = linkedSetOf<TileId>()
        val visiting = mutableSetOf<TileId>()
        fun visit(tile: TileObservation): Boolean {
            if (!visiting.add(tile.id)) return false
            for (id in tile.occluders) {
                val blocker = byId[id] ?: return false
                if (id !in result && !visit(blocker)) return false
                result += id
            }
            visiting.remove(tile.id)
            return true
        }
        return result.takeIf { visit(target) }
    }
}
