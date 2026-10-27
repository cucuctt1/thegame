package dev.tilesolver.vision

import org.junit.Assert.*
import org.junit.Test

class MaskedSpriteMatcherTest {
    @Test fun `covering icon does not contaminate visible part match`() {
        val original = FloatArray(256) { i -> kotlin.math.sin(i * 1.7).toFloat() }
        val mask = FloatArray(256) { if (it % 16 < 8) 1f else 0f }
        val covered = FloatArray(256) { if (mask[it] == 1f) original[it] * .5f + 2f else 100f }
        assertTrue(MaskedSpriteMatcher.similarity(covered, original, mask) > .9f)
    }

    @Test fun `tiny peek and uniform background cannot identify a face`() {
        val original = FloatArray(256) { it.toFloat() }
        assertEquals(0f, MaskedSpriteMatcher.similarity(original, original, FloatArray(256) { if (it % 16 < 3) 1f else 0f }), 0f)
        assertEquals(0f, MaskedSpriteMatcher.similarity(FloatArray(256) { 1f }, original, FloatArray(256) { 1f }), 0f)
    }
}
