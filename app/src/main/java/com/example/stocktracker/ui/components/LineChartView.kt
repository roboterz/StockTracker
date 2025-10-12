package com.example.stocktracker.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

// 用于封装单条曲线数据的数据类
data class LineData(val points: List<Float>, val color: Int)

class LineChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }
    private val linePath = Path()
    private var lines = listOf<LineData>()

    // 更新后的setData方法，可以接收多条曲线
    fun setData(newLines: List<LineData>) {
        lines = newLines
        invalidate() // 请求重绘
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (lines.isEmpty()) return

        // 查找所有曲线中的最大和最小值，以统一图表刻度
        val allPoints = lines.flatMap { it.points }
        if (allPoints.isEmpty()) return

        val maxValue = allPoints.maxOrNull() ?: 0f
        val minValue = allPoints.minOrNull() ?: 0f
        val range = if (maxValue - minValue == 0f) 1f else maxValue - minValue

        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        val horizontalPadding = 10f
        val verticalPadding = 20f

        // 循环遍历并绘制每一条曲线
        lines.forEach { line ->
            if (line.points.size < 2) return@forEach

            linePath.reset()
            linePaint.color = line.color

            val dataPoints = line.points
            val numPoints = dataPoints.size

            dataPoints.forEachIndexed { index, value ->
                val x = horizontalPadding + (index.toFloat() / (numPoints - 1).coerceAtLeast(1)) * (viewWidth - 2 * horizontalPadding)
                val y = (viewHeight - verticalPadding) - ((value - minValue) / range) * (viewHeight - 2 * verticalPadding)

                if (index == 0) {
                    linePath.moveTo(x, y)
                } else {
                    linePath.lineTo(x, y)
                }
            }
            canvas.drawPath(linePath, linePaint)
        }
    }
}

