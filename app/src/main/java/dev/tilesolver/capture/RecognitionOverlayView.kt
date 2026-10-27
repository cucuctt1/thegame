package dev.tilesolver.capture

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.DashPathEffect
import android.view.View
import dev.tilesolver.model.ClusterId
import dev.tilesolver.model.IntRect
import dev.tilesolver.model.TileState

internal data class OverlayTile(val bounds: IntRect, val cluster: ClusterId?, val state: TileState)

class RecognitionOverlayView(context: Context) : View(context) {
    private val targetPaint = strokePaint(Color.argb(235, 255, 193, 7), 8f)
    private val goalPaint = strokePaint(Color.argb(245, 255, 70, 210), 7f).apply {
        pathEffect = DashPathEffect(floatArrayOf(18f, 10f), 0f)
    }
    private val boardPaint = strokePaint(Color.argb(245, 80, 230, 120), 7f)
    private val trayPaint = strokePaint(Color.argb(245, 255, 145, 60), 7f)
    private val boardHandlePaint = fillPaint(Color.argb(245, 80, 230, 120))
    private val trayHandlePaint = fillPaint(Color.argb(245, 255, 145, 60))
    private val labelBackground = fillPaint(Color.argb(220, 20, 20, 24))
    private val labelPaint = fillPaint(Color.WHITE).apply { textSize = 34f }

    private var tiles: List<OverlayTile> = emptyList()
    private var target: IntRect? = null
    private var goal: IntRect? = null
    private var label: String = "Scanning..."
    private var editableBoard: IntRect? = null
    private var editableTray: IntRect? = null

    internal fun show(tiles: List<OverlayTile>, target: IntRect?, label: String, goal: IntRect? = null) {
        post {
            this.tiles = tiles
            this.target = target
            this.goal = goal
            this.label = label
            invalidate()
        }
    }

    fun showEditableAreas(board: IntRect, tray: IntRect) {
        post {
            editableBoard = board
            editableTray = tray
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val screenLocation = IntArray(2)
        getLocationOnScreen(screenLocation)
        val restorePoint = canvas.save()
        canvas.translate(-screenLocation[0].toFloat(), -screenLocation[1].toFloat())
        goal?.let { canvas.drawRect(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat(), goalPaint) }
        target?.let { canvas.drawRect(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat(), targetPaint) }
        tiles.forEach { tile ->
            val box = tile.bounds
            canvas.drawRect(box.left.toFloat(), box.top.toFloat(), box.right.toFloat(), box.bottom.toFloat(), paintFor(tile))
        }
        editableBoard?.let { drawEditableArea(canvas, it, boardPaint, boardHandlePaint) }
        editableTray?.let { drawEditableArea(canvas, it, trayPaint, trayHandlePaint) }
        val padding = 18f
        val textWidth = labelPaint.measureText(label)
        canvas.drawRoundRect(20f, 90f, 20f + textWidth + padding * 2, 150f, 14f, 14f, labelBackground)
        canvas.drawText(label, 20f + padding, 132f, labelPaint)
        canvas.restoreToCount(restorePoint)
    }

    private fun drawEditableArea(canvas: Canvas, area: IntRect, outline: Paint, handles: Paint) {
        canvas.drawRect(area.left.toFloat(), area.top.toFloat(), area.right.toFloat(), area.bottom.toFloat(), outline)
        corners(area).forEach { point -> canvas.drawCircle(point.first, point.second, HANDLE_RADIUS, handles) }
    }

    private fun corners(area: IntRect) = listOf(
        area.left.toFloat() to area.top.toFloat(),
        area.right.toFloat() to area.top.toFloat(),
        area.left.toFloat() to area.bottom.toFloat(),
        area.right.toFloat() to area.bottom.toFloat(),
    )

    private fun strokePaint(color: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = width
    }

    private fun fillPaint(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.FILL
    }

    private fun paintFor(tile: OverlayTile): Paint {
        val color = tile.cluster?.let { clusterColor(it) } ?: Color.LTGRAY
        return strokePaint(color, if (tile.state == TileState.BLOCKED) 3f else 5f).apply {
            if (tile.state == TileState.BLOCKED) pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
        }
    }

    private fun clusterColor(cluster: ClusterId): Int {
        val hue = (cluster.value * 137.508f) % 360f
        return Color.HSVToColor(235, floatArrayOf(hue, .82f, 1f))
    }

    companion object { private const val HANDLE_RADIUS = 18f }
}
