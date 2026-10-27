package dev.tilesolver.vision

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrightnessAvailabilityTest {
    @Test
    fun `dimmed covered card is blocked relative to bright cards`() {
        val reference = BrightnessAvailability.reference(listOf(.31f, .36f, .78f, .82f, .86f, .9f))

        assertTrue(BrightnessAvailability.isDim(.36f, reference))
        assertFalse(BrightnessAvailability.isDim(.78f, reference))
    }

    @Test
    fun `absolute floor still blocks dim card when most cards are dark`() {
        val reference = BrightnessAvailability.reference(listOf(.35f, .4f, .45f))

        assertTrue(BrightnessAvailability.isDim(.35f, reference))
        assertFalse(BrightnessAvailability.isDim(.45f, reference))
    }
}
