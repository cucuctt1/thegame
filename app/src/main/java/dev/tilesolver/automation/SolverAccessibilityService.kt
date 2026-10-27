package dev.tilesolver.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import dev.tilesolver.model.*
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicReference
import android.os.SystemClock
import kotlin.coroutines.resume

class SolverAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() { instance.set(this) }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event?.packageName?.toString()?.let { foregroundPackage.set(it) }
        if (event?.eventType == AccessibilityEvent.TYPE_TOUCH_INTERACTION_START && SystemClock.elapsedRealtime() > injectedGestureUntil.get()) {
            userTouchEpoch.incrementAndGet()
        }
    }
    override fun onInterrupt() = Unit
    override fun onDestroy() { instance.compareAndSet(this, null); super.onDestroy() }

    suspend fun tapPoint(point: BoardCoordinate): TapResult = suspendCancellableCoroutine { continuation ->
        injectedGestureUntil.set(SystemClock.elapsedRealtime() + 300)
        val path = Path().apply { moveTo(point.x, point.y) }
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 60)).build()
        val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { if (continuation.isActive) continuation.resume(TapResult.Completed) }
            override fun onCancelled(gestureDescription: GestureDescription?) { if (continuation.isActive) continuation.resume(TapResult.Cancelled) }
        }, null)
        if (!accepted && continuation.isActive) continuation.resume(TapResult.Failed("gesture rejected"))
    }

    companion object {
        val instance = AtomicReference<SolverAccessibilityService?>(null)
        val foregroundPackage = AtomicReference<String?>(null)
        val userTouchEpoch = java.util.concurrent.atomic.AtomicLong()
        private val injectedGestureUntil = java.util.concurrent.atomic.AtomicLong()
    }
}

class AccessibilityTapExecutor : dev.tilesolver.core.TapExecutor {
    override suspend fun tap(coordinate: BoardCoordinate): TapResult =
        SolverAccessibilityService.instance.get()?.tapPoint(coordinate) ?: TapResult.Failed("accessibility service unavailable")
}

class CaptureGatedTapExecutor(
    private val delegate: dev.tilesolver.core.TapExecutor,
) : dev.tilesolver.core.TapExecutor {
    @Volatile private var captureActive = false

    fun setCaptureActive(active: Boolean) {
        captureActive = active
    }

    override suspend fun tap(coordinate: BoardCoordinate): TapResult =
        if (captureActive) delegate.tap(coordinate) else TapResult.Failed("capture is not active")
}
