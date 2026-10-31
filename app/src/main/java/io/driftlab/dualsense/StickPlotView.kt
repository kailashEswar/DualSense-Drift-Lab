package io.driftlab.dualsense

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import java.util.ArrayDeque
import kotlin.math.min

/**
 * Lightweight display-synced joystick plot with a short trail of real readings.
 * No simulated samples, interpolation, or artificial input smoothing.
 */
class StickPlotView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val backdrop = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(248, 250, 254) }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(220, 229, 243)
        style = Paint.Style.STROKE
        strokeWidth = 1.3f * density
    }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(224, 232, 243)
        style = Paint.Style.STROKE
        strokeWidth = 0.8f * density
    }
    private val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(173, 192, 220)
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }
    private val centerRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(76, 176, 137)
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
    }
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(86, 143, 217) }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(48, 16, 113, 240) }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(20, 107, 235) }
    private val highlight = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val history = ArrayDeque<Pair<Int, Int>>()

    var x: Int = 128
        private set
    var y: Int = 128
        private set

    fun setPosition(newX: Int, newY: Int) {
        if (x == newX && y == newY) return
        x = newX
        y = newY
        history.addLast(newX to newY)
        while (history.size > 6) history.removeFirst()
        postInvalidateOnAnimation()
    }

    fun reset() {
        x = 128
        y = 128
        history.clear()
        postInvalidateOnAnimation()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desired = (resources.displayMetrics.density * 148).toInt()
        val w = resolveSize(desired, widthMeasureSpec)
        setMeasuredDimension(w, w)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width * 0.5f
        val cy = height * 0.5f
        val radius = min(width, height) * 0.39f
        val corner = 14f * density
        canvas.drawRoundRect(1f, 1f, width - 1f, height - 1f, corner, corner, backdrop)
        canvas.drawRoundRect(1f, 1f, width - 1f, height - 1f, corner, corner, border)
        for (i in -2..2) {
            val offset = radius * i / 2f
            canvas.drawLine(cx + offset, cy - radius, cx + offset, cy + radius, grid)
            canvas.drawLine(cx - radius, cy + offset, cx + radius, cy + offset, grid)
        }
        canvas.drawCircle(cx, cy, radius, border)
        canvas.drawCircle(cx, cy, radius * 0.6f, grid)
        canvas.drawLine(cx - radius, cy, cx + radius, cy, cross)
        canvas.drawLine(cx, cy - radius, cx, cy + radius, cross)
        canvas.drawCircle(cx, cy, radius * 0.12f, centerRing)

        fun axisX(raw: Int) = cx + ((raw - 127.5f) / 127.5f) * radius
        fun axisY(raw: Int) = cy + ((raw - 127.5f) / 127.5f) * radius
        history.forEachIndexed { index, position ->
            trailPaint.alpha = 35 + index * 24
            canvas.drawCircle(axisX(position.first), axisY(position.second), 2.1f * density, trailPaint)
        }
        val px = axisX(x)
        val py = axisY(y)
        canvas.drawCircle(px, py, 13f * density, halo)
        canvas.drawCircle(px, py, 6.5f * density, dot)
        canvas.drawCircle(px - density, py - density, 1.65f * density, highlight)
    }
}
