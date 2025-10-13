package com.example.stocktracker.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.example.stocktracker.R
import kotlin.math.min

data class ChartSegment(val value: Float, val colorResId: Int)

class DonutChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rectF = RectF() // Reusable rect
    private var segments = emptyList<ChartSegment>()
    private val normalStrokeWidth = 25f
    private val wideStrokeWidth = 35f // 为最宽的色块设置更宽的笔触

    init {
        paint.style = Paint.Style.STROKE
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

        // 找出最大的色块
        val maxSegment = segments.maxByOrNull { it.value }

        val spacingAngle = 5.0f
        var startAngle = -125f

        val viewSize = min(width, height).toFloat()
        val centerX = width / 2f
        val centerY = height / 2f

        // 定义一个固定的内圈半径，所有色块的内边缘都将对齐到这里
        // 这个半径是根据视图大小和最宽的笔触计算出来的
        val innerRadius = (viewSize / 2f) - wideStrokeWidth

        segments.forEach { segment ->
            val isMax = segment == maxSegment
            val currentStrokeWidth = if (isMax) wideStrokeWidth else normalStrokeWidth
            paint.strokeWidth = currentStrokeWidth
            paint.color = ContextCompat.getColor(context, segment.colorResId)

            // 路径的半径是笔触的中心线
            // 我们动态计算它，以确保笔触的内边缘始终在 `innerRadius`
            val pathRadius = innerRadius + (currentStrokeWidth / 2f)

            // 根据路径半径计算出用于绘制圆弧的矩形
            rectF.set(
                centerX - pathRadius,
                centerY - pathRadius,
                centerX + pathRadius,
                centerY + pathRadius
            )

            val sweepAngle = (segment.value / totalValue) * 360f
            val angleToDraw = (sweepAngle - spacingAngle).coerceAtLeast(0f)

            canvas.drawArc(rectF, startAngle, angleToDraw, false, paint)

            startAngle += sweepAngle
        }
    }
}

