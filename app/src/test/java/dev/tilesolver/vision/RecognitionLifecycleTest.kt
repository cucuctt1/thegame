package dev.tilesolver.vision

import dev.tilesolver.core.VisualClusterer
import dev.tilesolver.core.VisualSignature
import dev.tilesolver.model.*
import dev.tilesolver.session.DefaultSessionController
import org.junit.Assert.assertEquals
import org.junit.Test

class RecognitionLifecycleTest {
    @Test fun `same game capture restart preserves learned identities while package switch resets them`() {
        var resets = 0
        val clusterer = object : VisualClusterer {
            override fun classify(signature: VisualSignature) = emptyList<ClusterCandidate>()
            override fun learn(signature: VisualSignature, accepted: ClusterId?, forceNew: Boolean) = ClusterId(1)
            override fun clearTransient() {}
            override fun reset() { resets++ }
        }
        val mapper = OpenCvBoardMapper(clusterer)
        mapper.prepareForPackage("game.a") // calibration
        mapper.prepareForPackage("game.a") // solver
        mapper.prepareForPackage("game.a") // stop/start
        assertEquals(0, resets)
        mapper.prepareForPackage("game.b")
        assertEquals(1, resets)
    }

    @Test fun `session limit is twenty minutes`() {
        assertEquals(1_200_000L, DefaultSessionController.SESSION_LIMIT_MS)
    }
}
