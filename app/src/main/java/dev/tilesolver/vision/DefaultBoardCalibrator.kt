package dev.tilesolver.vision

import dev.tilesolver.core.BoardCalibrator
import dev.tilesolver.data.SettingsStore
import dev.tilesolver.model.Calibration
import dev.tilesolver.model.CapturedFrame
import dev.tilesolver.model.IntRect
import kotlinx.coroutines.flow.first

/** Initial game-specific calibration. The UI exposes these rectangles for correction before autoplay. */
class DefaultBoardCalibrator(private val settings: SettingsStore) : BoardCalibrator {
    override suspend fun calibrate(frame: CapturedFrame): Calibration {
        val w = frame.bitmap.width
        val h = frame.bitmap.height
        val defaultBoard = IntRect((w * .08f).toInt(), (h * .40f).toInt(), (w * .92f).toInt(), (h * .75f).toInt())
        val defaultTray = IntRect((w * .04f).toInt(), (h * .27f).toInt(), (w * .96f).toInt(), (h * .38f).toInt())
        val savedBoard = settings.boardArea.first()?.toPixels(w, h)
        val savedTray = settings.trayArea.first()?.toPixels(w, h)
        return Calibration(
            screenWidth = w,
            screenHeight = h,
            board = savedBoard ?: defaultBoard,
            tray = savedTray ?: defaultTray,
            exclusions = listOf(
                IntRect(0, 0, w, (h * .12f).toInt()),
                IntRect(0, (h * .90f).toInt(), w, h),
            ),
        )
    }
}
