package dev.tilesolver.automation

import dev.tilesolver.core.TapExecutor
import dev.tilesolver.model.BoardCoordinate
import dev.tilesolver.model.TapResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureGatedTapExecutorTest {
    @Test
    fun `rejects taps until capture is active`() = runBlocking {
        // Given a fresh executor whose capture has not started
        val delegate = RecordingTapExecutor()
        val executor = CaptureGatedTapExecutor(delegate)

        // When a tap is requested
        val result = executor.tap(BoardCoordinate(10f, 20f))

        // Then the tap is rejected before reaching the accessibility service
        assertEquals(TapResult.Failed("capture is not active"), result)
        assertEquals(0, delegate.tapCount)
    }

    @Test
    fun `forwards taps only while capture is active`() = runBlocking {
        // Given an executor armed by an active capture
        val delegate = RecordingTapExecutor()
        val executor = CaptureGatedTapExecutor(delegate)
        executor.setCaptureActive(true)

        // When a tap is requested and capture is later stopped
        val activeResult = executor.tap(BoardCoordinate(10f, 20f))
        executor.setCaptureActive(false)
        val stoppedResult = executor.tap(BoardCoordinate(30f, 40f))

        // Then only the active-capture tap reaches the delegate
        assertEquals(TapResult.Completed, activeResult)
        assertEquals(TapResult.Failed("capture is not active"), stoppedResult)
        assertEquals(1, delegate.tapCount)
    }

    private class RecordingTapExecutor : TapExecutor {
        var tapCount = 0

        override suspend fun tap(coordinate: BoardCoordinate): TapResult {
            tapCount++
            return TapResult.Completed
        }
    }
}
