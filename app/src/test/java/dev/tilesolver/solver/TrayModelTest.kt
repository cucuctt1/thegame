package dev.tilesolver.solver

import dev.tilesolver.model.ClusterId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrayModelTest {
    @Test fun `third matching tile clears all three tracked slots`() {
        val match = ClusterId(1)

        assertEquals(emptyList<ClusterId>(), TrayModel.afterPick(listOf(match, match), match, 7))
    }

    @Test fun `unmatched seventh tile is rejected even if vision misses tray slots`() {
        val six = (1..6).map(::ClusterId)

        assertNull(TrayModel.afterPick(six, ClusterId(7), 7))
    }
}
