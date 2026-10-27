package dev.tilesolver.vision

import dev.tilesolver.core.VisualSignature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HashVisualClustererTest {
    @Test fun `cached classifications invalidate after learning and reset`() {
        val matcher = HashVisualClusterer()
        val a = VisualSignature(0L, floatArrayOf(1f, 0f), floatArrayOf(1f, 0f))
        val b = VisualSignature(-1L, floatArrayOf(0f, 1f), floatArrayOf(0f, 1f))
        val first = matcher.learn(a)
        assertEquals(first, matcher.classify(b).first().cluster)
        val second = matcher.learn(b, forceNew = true)
        assertEquals(second, matcher.classify(b).first().cluster)
        assertEquals(matcher.classify(a), matcher.classify(a.copy(colorHistogram = a.colorHistogram.copyOf())))
        matcher.reset()
        assertTrue(matcher.classify(a).isEmpty())
    }

    @Test fun `same hash different sprite never collides in classification cache`() {
        val matcher = HashVisualClusterer()
        val a = VisualSignature(0L, floatArrayOf(1f, 0f), floatArrayOf(1f, 0f))
        val b = a.copy(edgeHistogram = floatArrayOf(0f, 1f))
        val first = matcher.learn(a)
        val second = matcher.learn(b, forceNew = true)
        assertEquals(first, matcher.classify(a).first().cluster)
        assertEquals(second, matcher.classify(b).first().cluster)
    }

    @Test fun `transient clear preserves tray-only learned identity across resumed observations`() {
        val matcher = HashVisualClusterer()
        val a = VisualSignature(0L, floatArrayOf(1f, 0f), floatArrayOf(1f, 0f))
        val id = matcher.learn(a)
        matcher.clearTransient()
        assertEquals(id, matcher.classify(a).first().cluster)
    }
    @Test fun `groups close visual signatures and separates different icons`() {
        val clusterer = HashVisualClusterer()
        val a = VisualSignature(0x00ff00ff, floatArrayOf(1f, 0f), floatArrayOf(1f, 0f))
        val near = VisualSignature(0x00ff00fe, floatArrayOf(.99f, .01f), floatArrayOf(.98f, .02f))
        val different = VisualSignature(0xff00ff00, floatArrayOf(0f, 1f), floatArrayOf(0f, 1f))
        val first = clusterer.learn(a)
        assertEquals(first, clusterer.learn(near))
        assertNotEquals(first, clusterer.learn(different))
    }

    @Test fun `groups the same icon after the game dims its colors`() {
        val clusterer = HashVisualClusterer()
        val bright = VisualSignature(0x00ff00ff, floatArrayOf(1f, 0f), floatArrayOf(.9f, .1f))
        val dimmed = VisualSignature(0x00ff00fe, floatArrayOf(0f, 1f), floatArrayOf(.88f, .12f))

        val first = clusterer.learn(bright)

        assertEquals(first, clusterer.learn(dimmed))
    }

    @Test fun `ambiguous matcher can force a separate type instead of false merging`() {
        val clusterer = HashVisualClusterer()
        val original = VisualSignature(0x00ff00ff, floatArrayOf(1f, 0f), floatArrayOf(1f, 0f))
        val ambiguous = VisualSignature(0x00ff00fe, floatArrayOf(.99f, .01f), floatArrayOf(.98f, .02f))

        val first = clusterer.learn(original)
        val separate = clusterer.learn(ambiguous, forceNew = true)

        assertNotEquals(first, separate)
    }

    @Test fun `shifted normalized sprite remains the same type`() {
        val clusterer = HashVisualClusterer()
        val sprite = FloatArray(256) { index -> if (index % 16 in 5..9 && index / 16 in 4..11) 1f else -0.4f }
        val shifted = FloatArray(256) { index ->
            val x = index % 16; val y = index / 16
            if (x - 1 in 5..9 && y + 1 in 4..11) 1f else -0.4f
        }
        val base = VisualSignature(0x00ff00ff, floatArrayOf(1f, 0f), floatArrayOf(1f, 0f), sprite)
        val moved = VisualSignature(0x00ff00fe, floatArrayOf(.99f, .01f), floatArrayOf(.98f, .02f), shifted)

        val first = clusterer.learn(base)

        assertEquals(first, clusterer.learn(moved))
    }

    @Test fun `new session reset removes stale visual identities`() {
        val clusterer = HashVisualClusterer()
        val signature = VisualSignature(0x00ff00ff, floatArrayOf(1f, 0f), floatArrayOf(1f, 0f))
        clusterer.learn(signature)
        clusterer.learn(signature)

        clusterer.reset()

        assertEquals(1, clusterer.learn(signature).value)
    }
}
