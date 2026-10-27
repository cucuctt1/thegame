package dev.tilesolver.capture

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import androidx.core.app.NotificationCompat
import dev.tilesolver.R
import dev.tilesolver.SolverApplication
import dev.tilesolver.automation.SolverAccessibilityService
import dev.tilesolver.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class CaptureMode { SOLVE, PREVIEW, CALIBRATE }

internal data class RecognitionProgress(val stableFrames: Int, val ready: Boolean)

internal class RecognitionStability(private val requiredStableFrames: Int = 3) {
    private var previousSignature: Long? = null
    private var stableFrames = 0

    fun observe(observation: BoardObservation, targetIsForeground: Boolean): RecognitionProgress {
        val valid = targetIsForeground && observation.recognizedScreen && observation.geometryValid && observation.tiles.size >= 3
        val signature = geometrySignature(observation)
        stableFrames = when {
            !valid -> 0
            signature == previousSignature -> stableFrames + 1
            else -> 1
        }
        previousSignature = signature.takeIf { valid }
        return RecognitionProgress(stableFrames, stableFrames >= requiredStableFrames)
    }

    private fun geometrySignature(observation: BoardObservation): Long = observation.tiles
        .sortedWith(compareBy<TileObservation> { it.bounds.top }.thenBy { it.bounds.left })
        .fold(17L) { acc, tile ->
            val box = tile.bounds
            acc * 31 + box.centerX() / 4 + (box.centerY() / 4) * 257L +
                (box.width() / 4) * 65_537L + (box.height() / 4) * 1_048_583L
        }
}

class CaptureService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var stopOverlay: Button? = null
    private var recognitionOverlay: RecognitionOverlayView? = null
    @Volatile private var areaEditor: CalibrationAreaEditor? = null
    private var workJob: Job? = null
    @Volatile private var adjustedBoard: IntRect? = null
    @Volatile private var adjustedTray: IntRect? = null
    private var areaEditorConfigured = false

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(CHANNEL, "Solver capture", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            graph.tapExecutor.setCaptureActive(false)
            scope.launch { graph.sessionController.stop(StopReason.USER); graph.frameSource.stop(); stopSelf() }
            return START_NOT_STICKY
        }
        val mode = intent?.getStringExtra(EXTRA_MODE)?.let { runCatching { CaptureMode.valueOf(it) }.getOrNull() } ?: CaptureMode.SOLVE
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data = intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        val gamePackage = intent?.getStringExtra(EXTRA_GAME_PACKAGE).orEmpty()
        if (resultCode != Activity.RESULT_OK || data == null || gamePackage.isBlank()) { stopSelf(); return START_NOT_STICKY }

        val stopIntent = PendingIntent.getService(this, 2, Intent(this, javaClass).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_pause)
            .setContentTitle(when (mode) {
                CaptureMode.SOLVE -> "Tile Solver is active"
                CaptureMode.PREVIEW -> "Tile Solver preview"
                CaptureMode.CALIBRATE -> "Tile Solver calibration"
            })
            .setContentText("Waiting for a stable recognized game board")
            .setOngoing(true).addAction(0, "Stop", stopIntent).build()
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        mutableActiveMode.value = mode
        if (mode != CaptureMode.SOLVE) showRecognitionOverlay()
        showEmergencyStop()
        workJob?.cancel()
        workJob = scope.launch {
            try {
                graph.boardMapper.prepareForPackage(gamePackage)
                graph.frameSource.start(resultCode, data)
                when (mode) {
                    CaptureMode.SOLVE -> {
                        val calibration = awaitStableCalibration(gamePackage)
                        graph.tapExecutor.setCaptureActive(true)
                        graph.sessionController.run(gamePackage, calibration)
                    }
                    CaptureMode.PREVIEW, CaptureMode.CALIBRATE -> inspectContinuously(gamePackage, mode)
                }
            } catch (t: Throwable) {
                if (t !is CancellationException) graph.sessionController.fail(t)
            } finally {
                graph.tapExecutor.setCaptureActive(false)
                mutableActiveMode.value = null
                graph.frameSource.stop(); stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        graph.tapExecutor.setCaptureActive(false)
        stopOverlay?.let { runCatching { getSystemService(WindowManager::class.java).removeView(it) } }
        recognitionOverlay?.let { runCatching { getSystemService(WindowManager::class.java).removeView(it) } }
        areaEditor?.close()
        stopOverlay = null
        recognitionOverlay = null
        areaEditor = null
        mutableActiveMode.value = null
        scope.cancel(); super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    private val graph get() = (application as SolverApplication).graph

    private suspend fun awaitStableCalibration(gamePackage: String): Calibration {
        var calibration: Calibration? = null
        var lastSequence = -1L
        val stability = RecognitionStability()
        while (currentCoroutineContext().isActive) {
            val frame = graph.frameSource.frames.first { it.sequence > lastSequence }
            lastSequence = frame.sequence
            try {
                val currentCalibration = calibration ?: graph.calibrator.calibrate(frame).also { calibration = it }
                val observation = graph.boardMapper.map(frame, currentCalibration, null)
                val progress = stability.observe(
                    observation,
                    SolverAccessibilityService.foregroundPackage.get() == gamePackage,
                )
                if (progress.ready) return currentCalibration
            } finally { frame.close() }
            delay(SCAN_INTERVAL_MS)
        }
        throw CancellationException("capture stopped")
    }

    private suspend fun inspectContinuously(gamePackage: String, mode: CaptureMode) {
        var calibration: Calibration? = null
        var board: BoardState? = null
        var lastSequence = -1L
        val stability = RecognitionStability()
        while (currentCoroutineContext().isActive) {
            val frame = graph.frameSource.frames.first { it.sequence > lastSequence }
            lastSequence = frame.sequence
            if (mode == CaptureMode.CALIBRATE && areaEditor?.isDragging == true) {
                frame.close()
                delay(DRAG_POLL_INTERVAL_MS)
                continue
            }
            try {
                val baseCalibration = calibration ?: graph.calibrator.calibrate(frame).also { calibration = it }
                if (mode == CaptureMode.CALIBRATE && !areaEditorConfigured) {
                    areaEditorConfigured = true
                    recognitionOverlay?.post {
                        areaEditor = CalibrationAreaEditor(
                            this@CaptureService,
                            requireNotNull(recognitionOverlay),
                            baseCalibration.board,
                            baseCalibration.tray,
                            baseCalibration.screenWidth,
                            baseCalibration.screenHeight,
                        ) { boardArea, trayArea ->
                            adjustedBoard = boardArea
                            adjustedTray = trayArea
                            if (!boardArea.intersects(trayArea)) {
                                scope.launch {
                                    graph.settings.setDetectionAreas(
                                        NormalizedDetectionArea.fromPixels(boardArea, baseCalibration.screenWidth, baseCalibration.screenHeight),
                                        NormalizedDetectionArea.fromPixels(trayArea, baseCalibration.screenWidth, baseCalibration.screenHeight),
                                    )
                                }
                            }
                        }.also { it.start() }
                    }
                }
                val currentCalibration = baseCalibration.copy(
                    board = adjustedBoard ?: baseCalibration.board,
                    tray = adjustedTray ?: baseCalibration.tray,
                )
                val observation = graph.boardMapper.map(frame, currentCalibration, board)
                board = graph.tracker.reconcile(board, observation)
                val targetIsForeground = SolverAccessibilityService.foregroundPackage.get() == gamePackage
                val progress = stability.observe(observation, targetIsForeground)
                val overlayTiles = if (targetIsForeground) {
                    (observation.tiles + observation.trayTiles).map { OverlayTile(it.bounds, it.visualCluster, it.state) }
                } else emptyList()
                if (mode == CaptureMode.CALIBRATE) {
                    val status = when {
                        !targetIsForeground -> "Waiting for game"
                        progress.ready -> "Saved: ${observation.tiles.size} board, ${observation.tray.size}/7 tray"
                        else -> "Recognized ${observation.tiles.size} cards • ${progress.stableFrames}/3 stable"
                    }
                    recognitionOverlay?.show(overlayTiles, null, status)
                } else {
                    val move = if (progress.ready) graph.planner.choose(requireNotNull(board), currentCalibration) else null
                    val target = move?.let { selected -> observation.tiles.firstOrNull { it.id == selected.tileId }?.bounds }
                    val goal = move?.goalTileId?.let { goalId -> observation.tiles.firstOrNull { it.id == goalId }?.bounds }
                    val status = when {
                        !targetIsForeground -> "Waiting for game"
                        !progress.ready -> "Scanning ${observation.tiles.size} cards…"
                        move == null -> "No safe move yet"
                        else -> "Next: ${move.rationale}"
                    }
                    recognitionOverlay?.show(overlayTiles, target, status, goal)
                }
            } finally { frame.close() }
            delay(if (mode == CaptureMode.CALIBRATE) CALIBRATION_SCAN_INTERVAL_MS else SCAN_INTERVAL_MS)
        }
    }

    private fun showRecognitionOverlay() {
        if (!Settings.canDrawOverlays(this) || recognitionOverlay != null) return
        val view = RecognitionOverlayView(this)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            setFitInsetsTypes(0)
        }
        getSystemService(WindowManager::class.java).addView(view, params)
        recognitionOverlay = view
    }

    private fun showEmergencyStop() {
        if (!Settings.canDrawOverlays(this) || stopOverlay != null) return
        val button = Button(this).apply {
            text = "STOP"
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(0xD9B00020.toInt())
            setOnClickListener { startService(Intent(this@CaptureService, CaptureService::class.java).setAction(ACTION_STOP)) }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.END; x = 16; y = 120 }
        getSystemService(WindowManager::class.java).addView(button, params)
        stopOverlay = button
    }

    companion object {
        const val ACTION_STOP = "dev.tilesolver.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val EXTRA_GAME_PACKAGE = "gamePackage"
        const val EXTRA_MODE = "mode"
        private const val CHANNEL = "solver_capture"
        private const val NOTIFICATION_ID = 41
        private const val SCAN_INTERVAL_MS = 250L
        private const val CALIBRATION_SCAN_INTERVAL_MS = 1_000L
        private const val DRAG_POLL_INTERVAL_MS = 100L

        private val mutableActiveMode = MutableStateFlow<CaptureMode?>(null)
        val activeMode: StateFlow<CaptureMode?> = mutableActiveMode.asStateFlow()
    }
}
