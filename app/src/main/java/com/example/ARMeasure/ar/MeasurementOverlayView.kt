package com.example.ARMeasure.ar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class MeasurementOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    data class ScreenPoint(val x: Float, val y: Float)

    private var startPoint: ScreenPoint? = null
    private var endPoint: ScreenPoint? = null

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 6f
        style = Paint.Style.STROKE
    }

    private val startPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(35, 210, 130)
        style = Paint.Style.FILL
    }

    private val endPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(80, 170, 255)
        style = Paint.Style.FILL
    }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }

    fun setMeasurement(start: ScreenPoint?, end: ScreenPoint?) {
        startPoint = start
        endPoint = end
        invalidate()
    }

    fun clearMeasurement() {
        startPoint = null
        endPoint = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val start = startPoint
        val end = endPoint

        if (start != null && end != null) {
            canvas.drawLine(start.x, start.y, end.x, end.y, linePaint)
        }

        if (start != null) {
            drawMarker(canvas, start, startPaint)
        }
        if (end != null) {
            drawMarker(canvas, end, endPaint)
        }
    }

    private fun drawMarker(canvas: Canvas, point: ScreenPoint, paint: Paint) {
        canvas.drawCircle(point.x, point.y, MARKER_RADIUS, paint)
        canvas.drawCircle(point.x, point.y, MARKER_RADIUS + RING_PADDING, ringPaint)
    }

    companion object {
        private const val MARKER_RADIUS = 13f
        private const val RING_PADDING = 5f
    }
}
