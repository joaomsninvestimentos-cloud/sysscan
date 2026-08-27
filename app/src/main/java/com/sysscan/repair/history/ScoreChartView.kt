package com.sysscan.repair.history

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min

class ScoreChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.parseColor("#0B6E4F")
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#F4A261")
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = Color.parseColor("#330B6E4F")
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(10f)
        color = Color.parseColor("#5F6368")
    }

    private val baselinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = Color.parseColor("#225F6368")
    }

    var entries: List<ScanHistoryEntry> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (entries.size < 1) {
            canvas.drawText("Sem dados suficientes ainda", width / 2f - dp(60f), height / 2f, labelPaint)
            return
        }

        val padLeft = dp(30f)
        val padRight = dp(12f)
        val padTop = dp(16f)
        val padBottom = dp(24f)

        val chartW = width - padLeft - padRight
        val chartH = height - padTop - padBottom
        if (chartW <= 0 || chartH <= 0) return

        // grid horizontal (0, 50, 100)
        for (v in listOf(0, 50, 100)) {
            val y = padTop + chartH * (1f - v / 100f)
            canvas.drawLine(padLeft, y, width - padRight, y, gridPaint)
        }

        // baseline
        canvas.drawLine(padLeft, padTop, padLeft, padTop + chartH, baselinePaint)
        canvas.drawText("100", 0f, padTop + dp(4f), labelPaint)
        canvas.drawText("0", 0f, padTop + chartH + dp(4f), labelPaint)

        val n = entries.size
        if (n == 1) {
            val x = padLeft + chartW / 2f
            val y = pointY(entries[0].score, padTop, chartH)
            canvas.drawCircle(x, y, dp(6f), dotPaint)
            drawScoreLabel(canvas, x, y, entries[0].score)
            return
        }

        val path = Path()
        entries.forEachIndexed { i, e ->
            val x = padLeft + chartW * (i.toFloat() / (n - 1))
            val y = pointY(e.score, padTop, chartH)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, linePaint)

        entries.forEachIndexed { i, e ->
            val x = padLeft + chartW * (i.toFloat() / (n - 1))
            val y = pointY(e.score, padTop, chartH)
            canvas.drawCircle(x, y, dp(5f), dotPaint)
            if (i == 0) drawScoreLabel(canvas, x, y, e.score)
        }
    }

    private fun drawScoreLabel(canvas: Canvas, x: Float, y: Float, score: Int) {
        val text = score.toString()
        val textW = labelPaint.measureText(text)
        val cx = min(max(x - textW / 2f, 0f), width - textW)
        val cy = max(y - dp(12f), dp(8f))
        canvas.drawText(text, cx, cy, labelPaint)
    }

    private fun pointY(score: Int, padTop: Float, chartH: Float): Float =
        padTop + chartH * (1f - score.coerceIn(0, 100) / 100f)

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
    private fun sp(v: Float): Float = v * resources.displayMetrics.scaledDensity
}
