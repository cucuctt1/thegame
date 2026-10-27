package dev.tilesolver.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NormalizedDetectionAreaTest {
    @Test
    fun `saved area scales to current screen size`() {
        val area = NormalizedDetectionArea.fromPixels(IntRect(72, 320, 648, 1200), 720, 1600)

        assertTrue(area.isValid())
        assertEquals(IntRect(108, 480, 972, 1800), area.toPixels(1080, 2400))
    }

    @Test
    fun `rejects an area too small to be useful`() {
        assertFalse(NormalizedDetectionArea(.2f, .2f, .25f, .25f).isValid())
    }
}
