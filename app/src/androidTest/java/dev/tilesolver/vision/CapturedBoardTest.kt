package dev.tilesolver.vision

import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.tilesolver.model.*
import dev.tilesolver.solver.BeamMovePlanner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CapturedBoardTest {
    @Test fun repeatedRecognitionCachePreservesDetectionsAndMeasuresCost() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().context
        val bitmap = context.assets.open("stuck-board.png").use { BitmapFactory.decodeStream(it) }
        val frame = CapturedFrame(bitmap, 100, 1)
        val calibration = Calibration(720, 1600, IntRect(70, 290, 650, 1060), IntRect(35, 1200, 600, 1305), emptyList())
        val cached = OpenCvBoardMapper(HashVisualClusterer())
        val uncached = OpenCvBoardMapper(HashVisualClusterer(cacheCapacity = 0))
        val cachedTimes = mutableListOf<Long>()
        val uncachedTimes = mutableListOf<Long>()
        try {
            var cachedState: BoardState? = null
            var uncachedState: BoardState? = null
            repeat(16) { iteration ->
                // Alternate order, warm both paths, and do not assert wall-clock timing.
                for (useCache in if (iteration % 2 == 0) listOf(true, false) else listOf(false, true)) {
                    val mapper = if (useCache) cached else uncached
                    val before = System.nanoTime()
                    val observation = mapper.map(frame, calibration, if (useCache) cachedState else uncachedState)
                    val duration = (System.nanoTime() - before) / 1_000_000
                    if (useCache) cachedState = BoardState(observation) else uncachedState = BoardState(observation)
                    if (iteration >= 10) (if (useCache) cachedTimes else uncachedTimes).add(duration)
                }
                assertEquals("cache must not change detections", uncachedState!!.observation, cachedState!!.observation)
            }
            Log.i("TileSolverFixture", "recognition median cached=${cachedTimes.sorted()[cachedTimes.size / 2]}ms " +
                "uncached=${uncachedTimes.sorted()[uncachedTimes.size / 2]}ms")
        } finally { frame.close() }
    }

    @Test fun capturedBoardRetainsIsolatedCardsAndLayerDependencies() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().context
        val bitmap = context.assets.open("stuck-board.png").use { BitmapFactory.decodeStream(it) }
        val calibration = Calibration(720, 1600, IntRect(70, 290, 650, 1060), IntRect(35, 1200, 600, 1305), emptyList())
        val mapper = OpenCvBoardMapper(HashVisualClusterer())
        val frame = CapturedFrame(bitmap, 100, 1)
        try {
            var prior: BoardState? = null
            repeat(3) {
                prior = BoardState(mapper.map(frame, calibration, prior))
            }
            val observed = requireNotNull(prior).observation
            Log.i("TileSolverFixture", observed.tiles.joinToString("\n") {
                "${it.bounds} type=${it.visualCluster} ${it.state} blockers=${it.occluders}"
            })
            assertEquals("empty tray", 0, observed.tray.size)
            assertTrue("isolated bottom left ball", observed.tiles.any { it.bounds.contains(120, 990) && it.state == TileState.EXPOSED })
            assertTrue("isolated bottom right ring", observed.tiles.any { it.bounds.contains(600, 990) && it.state == TileState.EXPOSED })
            assertTrue("dark icon is not a locked card: top left speaker", observed.tiles.any { it.bounds.contains(120, 380) && it.state == TileState.EXPOSED })
            assertTrue("dark icon is not a locked card: center right speaker", observed.tiles.any { it.bounds.contains(440, 790) && it.state == TileState.EXPOSED })
            assertTrue("lower lipstick remains locked", observed.tiles.any { it.bounds.contains(275, 560) && it.state == TileState.BLOCKED })
            assertTrue("covered center cards need obstruction edges", observed.tiles.count { it.state == TileState.BLOCKED && it.occluders.isNotEmpty() } >= 3)
            val move = BeamMovePlanner().choose(requireNotNull(prior), calibration)
            assertNotNull("board has playable moves", move)
            assertTrue(observed.tiles.any { it.id == move!!.tileId && it.state == TileState.EXPOSED })
        } finally { frame.close() }
    }
}
