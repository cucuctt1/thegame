package dev.tilesolver.capture

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.WindowManager
import dev.tilesolver.core.FrameSource
import dev.tilesolver.model.CapturedFrame
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class MediaProjectionFrameSource(private val context: Context) : FrameSource {
    private val frameChannel = Channel<CapturedFrame>(
        capacity = Channel.CONFLATED,
        onUndeliveredElement = CapturedFrame::close,
    )
    override val frames: Flow<CapturedFrame> = frameChannel.receiveAsFlow()
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private val processing = AtomicBoolean(false)
    private val sequence = AtomicLong()
    private val frameLock = Any()
    private val lastCaptureMillis = AtomicLong(0L)

    override suspend fun start(resultCode: Int, resultData: Intent) {
        stop()
        while (true) frameChannel.tryReceive().getOrNull()?.close() ?: break
        lastCaptureMillis.set(0L)
        val bounds = context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        val density = context.resources.displayMetrics.densityDpi
        val imageReader = ImageReader.newInstance(bounds.width(), bounds.height(), PixelFormat.RGBA_8888, 3)
        val worker = HandlerThread("solver-capture").apply { start() }
        val mediaProjection = context.getSystemService(MediaProjectionManager::class.java)
            .getMediaProjection(resultCode, resultData)
            ?: throw IllegalStateException("MediaProjection permission was not granted")
        val callback = object : MediaProjection.Callback() {
            override fun onStop() { releaseResources(stopProjection = false) }
        }
        val workerHandler = Handler(worker.looper)
        mediaProjection.registerCallback(callback, workerHandler)
        imageReader.setOnImageAvailableListener({ source ->
            val now = SystemClock.elapsedRealtime()
            val previous = lastCaptureMillis.get()
            if (now - previous < MIN_FRAME_INTERVAL_MS || !lastCaptureMillis.compareAndSet(previous, now)) {
                source.acquireLatestImage()?.close()
                return@setOnImageAvailableListener
            }
            if (!processing.compareAndSet(false, true)) { source.acquireLatestImage()?.close(); return@setOnImageAvailableListener }
            try {
                synchronized(frameLock) {
                    val image = source.acquireLatestImage()
                    try {
                        if (image != null) {
                            val plane = image.planes[0]
                            val pixelStride = plane.pixelStride
                            val rowStride = plane.rowStride
                            val paddedWidth = bounds.width() + (rowStride - pixelStride * bounds.width()) / pixelStride
                            val padded = Bitmap.createBitmap(paddedWidth, bounds.height(), Bitmap.Config.ARGB_8888)
                            padded.copyPixelsFromBuffer(plane.buffer)
                            val frameBitmap = Bitmap.createBitmap(padded, 0, 0, bounds.width(), bounds.height())
                            if (frameBitmap !== padded) padded.recycle()
                            val frame = CapturedFrame(frameBitmap, now, sequence.incrementAndGet())
                            if (frameChannel.trySend(frame).isFailure) frame.close()
                        }
                    } finally { image?.close() }
                }
            } catch (_: IllegalStateException) {
                // The ImageReader was closed while a queued callback was being drained.
            } finally { processing.set(false) }
        }, workerHandler)
        projection = mediaProjection; projectionCallback = callback; reader = imageReader; thread = worker
        display = mediaProjection.createVirtualDisplay(
            "TileSolverCapture", bounds.width(), bounds.height(), density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader.surface, null, workerHandler,
        )
    }

    override suspend fun stop() = releaseResources(stopProjection = true)

    @Synchronized private fun releaseResources(stopProjection: Boolean) {
        val activeProjection = projection
        val activeCallback = projectionCallback
        projection = null
        projectionCallback = null
        synchronized(frameLock) {
            reader?.setOnImageAvailableListener(null, null)
            display?.release(); display = null
            reader?.close(); reader = null
        }
        if (activeProjection != null && activeCallback != null) {
            runCatching { activeProjection.unregisterCallback(activeCallback) }
        }
        if (stopProjection) runCatching { activeProjection?.stop() }
        thread?.quitSafely(); thread = null
        processing.set(false)
    }

    companion object { private const val MIN_FRAME_INTERVAL_MS = 250L }
}
