package dev.tilesolver.vision

import dev.tilesolver.model.IntRect
import kotlin.math.abs

/** Complete a visible strip using the size of real cards, then require image evidence. */
internal object PartialTileGeometry {
    fun proposals(fragment: IntRect, width: Int, height: Int): List<IntRect> {
        if (width <= 0 || height <= 0) return emptyList()
        val wr = fragment.width().toFloat() / width
        val hr = fragment.height().toFloat() / height
        return when {
            wr in .88f..1.12f && hr in .88f..1.12f -> listOf(fragment)
            wr in .88f..1.12f && hr in .22f.. .85f -> listOf(
                IntRect(fragment.left, fragment.top, fragment.left + width, fragment.top + height),
                IntRect(fragment.left, fragment.bottom - height, fragment.left + width, fragment.bottom),
            )
            hr in .88f..1.12f && wr in .22f.. .85f -> listOf(
                IntRect(fragment.left, fragment.top, fragment.left + width, fragment.top + height),
                IntRect(fragment.right - width, fragment.top, fragment.right, fragment.top + height),
            )
            else -> emptyList()
        }
    }

    fun supported(rect: IntRect, covers: List<IntRect>, edgeAt: (Int, Int) -> Boolean): Boolean {
        if (covers.none { rect.intersects(it) }) return false
        // Require two perpendicular outer edges. An icon, a single line or a guess
        // under an opaque card is insufficient evidence for another card.
        val sides = (0..3).map { side ->
            var visible = 0
            var hits = 0
            for (step in 2..17) {
                val x = when (side) {
                    0 -> rect.left
                    1 -> rect.right - 1
                    else -> rect.left + rect.width() * step / 20
                }
                val y = when (side) {
                    2 -> rect.top
                    3 -> rect.bottom - 1
                    else -> rect.top + rect.height() * step / 20
                }
                if (covers.any { x >= it.left - 2 && x < it.right + 2 && y >= it.top - 2 && y < it.bottom + 2 }) continue
                visible++
                if ((-2..2).any { offset -> edgeAt(x + if (side < 2) offset else 0, y + if (side >= 2) offset else 0) }) hits++
            }
            visible >= 5 && hits.toFloat() / visible >= .65f
        }
        return (sides[0] || sides[1]) && (sides[2] || sides[3])
    }

    fun samePosition(a: IntRect, b: IntRect): Boolean =
        abs(a.centerX() - b.centerX()) < minOf(a.width(), b.width()) * .18f &&
            abs(a.centerY() - b.centerY()) < minOf(a.height(), b.height()) * .18f
}
