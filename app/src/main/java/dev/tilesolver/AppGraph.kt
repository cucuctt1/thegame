package dev.tilesolver

import android.app.Application
import dev.tilesolver.automation.AccessibilityTapExecutor
import dev.tilesolver.automation.CaptureGatedTapExecutor
import dev.tilesolver.capture.MediaProjectionFrameSource
import dev.tilesolver.data.*
import dev.tilesolver.safety.DefaultSafetyGate
import dev.tilesolver.session.DefaultSessionController
import dev.tilesolver.solver.BeamMovePlanner
import dev.tilesolver.solver.DefaultBoardTracker
import dev.tilesolver.vision.*
import kotlinx.coroutines.*

class AppGraph(app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = SettingsStore(app)
    val database = AppDatabase.get(app)
    val retention = RetentionManagerImpl(app, database.diagnosticDao())
    val frameSource = MediaProjectionFrameSource(app)
    val calibrator = DefaultBoardCalibrator(settings)
    val clusterer = HashVisualClusterer()
    val boardMapper = OpenCvBoardMapper(clusterer)
    val tracker = DefaultBoardTracker()
    val planner = BeamMovePlanner()
    val safety = DefaultSafetyGate()
    val accessibilityTapExecutor = AccessibilityTapExecutor()
    val tapExecutor = CaptureGatedTapExecutor(accessibilityTapExecutor)
    val sessionController = DefaultSessionController(
        frameSource, boardMapper, tracker, planner, safety, tapExecutor,
        database.sessionDao(), retention,
    )
}

fun CoroutineScope.launchSafely(block: suspend CoroutineScope.() -> Unit) = launch(CoroutineExceptionHandler { _, _ -> }, block = block)
