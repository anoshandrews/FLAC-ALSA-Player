package dev.anosh.musicplayer.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

class FrequencyResponseView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#242424")
        strokeWidth = dp(1.0f)
        style = Paint.Style.STROKE
    }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B3B3B3")
        strokeWidth = dp(1.25f)
        textSize = dp(10f)
    }
    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF003C")
        strokeWidth = dp(2.5f)
        style = Paint.Style.STROKE
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#22FF003C")
        style = Paint.Style.FILL
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val responsePath = Path()
    private val fillPath = Path()
    private val frequencies = floatArrayOf(20f, 50f, 100f, 200f, 500f, 1_000f, 2_000f, 5_000f, 10_000f, 20_000f)
    private val baselineDb = floatArrayOf(-7f, -5f, -2f, -1f, 1.5f, 0.5f, 2.5f, 1f, -1.5f, -4f)
    private val labels = arrayOf("20", "50", "100", "200", "500", "1k", "2k", "5k", "10k", "20k")
    private var playbackActive = false
    private var modulation = 0f
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2200L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            modulation = it.animatedFraction
            invalidate()
        }
    }

    fun setPlaybackActive(active: Boolean) {
        if (playbackActive == active) {
            return
        }
        playbackActive = active
        if (active) {
            if (!animator.isStarted) {
                animator.start()
            }
        } else {
            animator.cancel()
            modulation = 0f
        }
        invalidate()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = paddingLeft + dp(10f)
        val top = paddingTop + dp(12f)
        val right = width - paddingRight - dp(10f)
        val bottom = height - paddingBottom - dp(24f)
        val graphWidth = max(right - left, 1f)
        val graphHeight = max(bottom - top, 1f)

        drawGrid(canvas, left, top, right, bottom, graphHeight, graphWidth)
        canvas.drawLine(left, top + graphHeight / 2f, right, top + graphHeight / 2f, axisPaint)
        drawResponse(canvas, left, top, right, bottom, graphHeight)
    }

    private fun drawGrid(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        graphHeight: Float,
        graphWidth: Float,
    ) {
        val horizontalSteps = 5
        repeat(horizontalSteps + 1) { index ->
            val y = top + (graphHeight / horizontalSteps) * index
            canvas.drawLine(left, y, right, y, gridPaint)
        }
        frequencies.forEachIndexed { index, frequency ->
            val x = xForFrequency(frequency, left, graphWidth)
            canvas.drawLine(x, top, x, bottom, gridPaint)
            canvas.drawText(labels[index], x - dp(10f), bottom + dp(16f), axisPaint)
        }
        canvas.drawText("+6dB", left, top - dp(2f), axisPaint)
        canvas.drawText("-6dB", left, bottom - dp(4f), axisPaint)
    }

    private fun drawResponse(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        graphHeight: Float,
    ) {
        responsePath.reset()
        fillPath.reset()

        frequencies.forEachIndexed { index, frequency ->
            val x = xForFrequency(frequency, left, right - left)
            val animatedDb = baselineDb[index] + dynamicOffset(index)
            val y = yForDb(animatedDb, top, graphHeight)
            if (index == 0) {
                responsePath.moveTo(x, y)
                fillPath.moveTo(x, bottom)
                fillPath.lineTo(x, y)
            } else {
                responsePath.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }
        fillPath.lineTo(right, bottom)
        fillPath.close()

        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(responsePath, curvePaint)

        val markerX = xForFrequency(2_000f, left, right - left)
        val markerY = yForDb(baselineDb[6] + dynamicOffset(6), top, graphHeight)
        canvas.drawCircle(markerX, markerY, dp(3.5f), markerPaint)
    }

    private fun xForFrequency(
        frequency: Float,
        left: Float,
        width: Float,
    ): Float {
        val minLog = kotlin.math.log10(20f)
        val maxLog = kotlin.math.log10(20_000f)
        val ratio = (kotlin.math.log10(frequency) - minLog) / (maxLog - minLog)
        return left + ratio * width
    }

    private fun yForDb(
        db: Float,
        top: Float,
        height: Float,
    ): Float {
        val clamped = min(6f, max(-6f, db))
        val normalized = (6f - clamped) / 12f
        return top + normalized * height
    }

    private fun dynamicOffset(index: Int): Float {
        if (!playbackActive) {
            return 0f
        }
        val phase = modulation * Math.PI * 2.0
        return (cos(phase + index * 0.45) * 0.55).toFloat()
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
