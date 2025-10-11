package com.example.stocktracker.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

data class ChartSegment(val value: Float, val colorResId: Int)

class DonutChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rectF = RectF()
    private var segments = emptyList<ChartSegment>()
    private val strokeWidth = 35f

    init {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = strokeWidth
    }

    fun setData(newSegments: List<ChartSegment>) {
        segments = newSegments
        invalidate() // Redraw the view
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (segments.isEmpty()) return

        val totalValue = segments.sumOf { it.value.toDouble() }.toFloat()
        if (totalValue == 0f) return

        var startAngle = -90f

        val diameter = (width.coerceAtMost(height) - strokeWidth).toFloat()
        val left = (width - diameter) / 2
        val top = (height - diameter) / 2
        rectF.set(left, top, left + diameter, top + diameter)


        segments.forEach { segment ->
            paint.color = ContextCompat.getColor(context, segment.colorResId)
            val sweepAngle = (segment.value / totalValue) * 360f
            canvas.drawArc(rectF, startAngle, sweepAngle, false, paint)
            startAngle += sweepAngle
        }
    }
}
