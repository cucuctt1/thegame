package dev.tilesolver.solver

import dev.tilesolver.model.ClusterId

internal object TrayModel {
    fun afterPick(tray: List<ClusterId>, pick: ClusterId, capacity: Int): List<ClusterId>? {
        val next = (tray + pick).toMutableList()
        if (next.count { it == pick } >= 3) repeat(3) { next.remove(pick) }
        return next.takeIf { it.size < capacity }
    }
}
