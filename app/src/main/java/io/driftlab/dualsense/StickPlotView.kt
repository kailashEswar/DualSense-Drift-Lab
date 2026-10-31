package io.driftlab.dualsense

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import kotlin.math.min

/** Passive live-stick plot. No virtual stick and no simulated controller readings. */
class StickPlotView(context: Context) : View(context) {
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(65, 86, 117); style = Paint.Style.STROKE; strokeWidth = 2f
    }
    private val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(55, 73, 99); style = Paint.Style.STROKE; strokeWidth = 1f
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(68, 194, 251) }
    private val center = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(104, 235, 186) }
    var x: Int = 128
        private set
    var y: Int = 128
        private set
    fun setPosition(newX: Int, newY: Int) { x = newX; y = newY; invalidate() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desired = (resources.displayMetrics.density * 144).toInt()
        val w = resolveSize(desired, widthMeasureSpec)
        setMeasuredDimension(w, w)
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) * 0.41f
        canvas.drawCircle(cx, cy, radius, ring)
        canvas.drawCircle(cx, cy, radius * 0.6f, cross)
        canvas.drawLine(cx - radius, cy, cx + radius, cy, cross)
        canvas.drawLine(cx, cy - radius, cx, cy + radius, cross)
        canvas.drawCircle(cx, cy, 5f, center)
        val px = cx + ((x - 127.5f) / 127.5f) * radius
        val py = cy + ((y - 127.5f) / 127.5f) * radius
        canvas.drawCircle(px, py, 9f, dot)
    }
}
