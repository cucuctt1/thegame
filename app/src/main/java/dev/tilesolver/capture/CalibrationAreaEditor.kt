package dev.tilesolver.capture

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import dev.tilesolver.model.IntRect

internal class CalibrationAreaEditor(
    context: Context,
    private val overlay: RecognitionOverlayView,
    initialBoard: IntRect,
    initialTray: IntRect,
    private val screenWidth: Int,
    private val screenHeight: Int,
    private val onAreasChanged: (IntRect, IntRect) -> Unit,
) : AutoCloseable {
    @Volatile var isDragging: Boolean = false
        private set
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var board = initialBoard
    private var tray = initialTray
    private val handles = Area.entries.flatMap { area -> Corner.entries.map { corner -> Handle(area, corner) } }
        .associateWith { handle ->
            DragHandleView(
                context,
                onStart = { isDragging = true },
                onDrag = { x, y, finished ->
                    drag(handle, x, y, finished)
                    if (finished) isDragging = false
                },
                onCancel = { isDragging = false },
            )
        }

    fun start() {
        overlay.showEditableAreas(board, tray)
        handles.forEach { (handle, view) -> windowManager.addView(view, paramsFor(handle)) }
    }

    override fun close() {
        handles.values.forEach { view -> runCatching { windowManager.removeView(view) } }
    }

    private fun drag(handle: Handle, x: Int, y: Int, finished: Boolean) {
        if (handle.area == Area.BOARD) board = resize(board, handle.corner, x, y)
        else tray = resize(tray, handle.corner, x, y)
        overlay.showEditableAreas(board, tray)
        handles.forEach { (otherHandle, view) -> windowManager.updateViewLayout(view, paramsFor(otherHandle)) }
        if (finished) onAreasChanged(board, tray)
    }

    private fun paramsFor(handle: Handle): WindowManager.LayoutParams {
        val point = pointFor(handle)
        val origin = IntArray(2)
        overlay.getLocationOnScreen(origin)
        return WindowManager.LayoutParams(
            HANDLE_WINDOW_SIZE,
            HANDLE_WINDOW_SIZE,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = point.first - origin[0] - HANDLE_WINDOW_SIZE / 2
            y = point.second - origin[1] - HANDLE_WINDOW_SIZE / 2
        }
    }

    private fun pointFor(handle: Handle): Pair<Int, Int> {
        val area = if (handle.area == Area.BOARD) board else tray
        return when (handle.corner) {
            Corner.TOP_LEFT -> area.left to area.top
            Corner.TOP_RIGHT -> area.right to area.top
            Corner.BOTTOM_LEFT -> area.left to area.bottom
            Corner.BOTTOM_RIGHT -> area.right to area.bottom
        }
    }

    private fun resize(area: IntRect, corner: Corner, rawX: Int, rawY: Int): IntRect {
        val x = rawX.coerceIn(0, screenWidth)
        val y = rawY.coerceIn(0, screenHeight)
        val minWidth = (screenWidth * MIN_AREA_FRACTION).toInt()
        val minHeight = (screenHeight * MIN_AREA_FRACTION).toInt()
        return when (corner) {
            Corner.TOP_LEFT -> area.copy(left = x.coerceAtMost(area.right - minWidth), top = y.coerceAtMost(area.bottom - minHeight))
            Corner.TOP_RIGHT -> area.copy(right = x.coerceAtLeast(area.left + minWidth), top = y.coerceAtMost(area.bottom - minHeight))
            Corner.BOTTOM_LEFT -> area.copy(left = x.coerceAtMost(area.right - minWidth), bottom = y.coerceAtLeast(area.top + minHeight))
            Corner.BOTTOM_RIGHT -> area.copy(right = x.coerceAtLeast(area.left + minWidth), bottom = y.coerceAtLeast(area.top + minHeight))
        }
    }

    private enum class Area { BOARD, TRAY }
    private enum class Corner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }
    private data class Handle(val area: Area, val corner: Corner)

    private class DragHandleView(
        context: Context,
        private val onStart: () -> Unit,
        private val onDrag: (Int, Int, Boolean) -> Unit,
        private val onCancel: () -> Unit,
    ) : View(context) {
        init { background = ColorDrawable(android.graphics.Color.TRANSPARENT) }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> onStart()
                MotionEvent.ACTION_MOVE -> onDrag(event.rawX.toInt(), event.rawY.toInt(), false)
                MotionEvent.ACTION_UP -> onDrag(event.rawX.toInt(), event.rawY.toInt(), true)
                MotionEvent.ACTION_CANCEL -> onCancel()
            }
            return true
        }
    }

    companion object {
        private const val HANDLE_WINDOW_SIZE = 144
        private const val MIN_AREA_FRACTION = .1f
    }
}
