package dev.anosh.musicplayer.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.AudioFormat
import android.util.AttributeSet
import android.view.View
import dev.anosh.musicplayer.audio.DecodedAudioFile
import kotlin.math.abs
import kotlin.math.max

class AudioVisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val barCount = 28
    private val levels = FloatArray(barCount) { 0.05f }
    private val peakLevels = FloatArray(barCount) { 0.08f }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF003C")
        style = Paint.Style.FILL
    }
    private val peakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FAFAFA")
        style = Paint.Style.FILL
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1F1F1F")
        strokeWidth = dp(1f)
        style = Paint.Style.STROKE
    }
    private val baselinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#303030")
        strokeWidth = dp(1f)
    }
    private var playbackActive = false

    fun setPlaybackActive(active: Boolean) {
        playbackActive = active
        if (!active) {
            repeat(levels.size) { index ->
                levels[index] = 0.05f
                peakLevels[index] = 0.08f
            }
        }
        postInvalidateOnAnimation()
    }

    fun submitAudioChunk(audioFile: DecodedAudioFile, chunk: ByteArray) {
        if (!playbackActive || chunk.isEmpty()) {
            return
        }
        val samples = decodeMonoSamples(audioFile, chunk)
        if (samples.isEmpty()) {
            return
        }
        val nextLevels = FloatArray(barCount)
        val window = max(samples.size / barCount, 1)
        repeat(barCount) { band ->
            val start = band * window
            if (start >= samples.size) {
                nextLevels[band] = 0.05f
                return@repeat
            }
            val end = minOf(samples.size, start + window)
            var energy = 0f
            for (index in start until end) {
                energy += abs(samples[index])
            }
            val average = (energy / max(end - start, 1))
            nextLevels[band] = average.coerceIn(0.04f, 1f)
        }

        post {
            repeat(barCount) { index ->
                levels[index] = levels[index] * 0.35f + nextLevels[index] * 0.65f
                peakLevels[index] = max(levels[index], peakLevels[index] - 0.035f).coerceAtLeast(0.08f)
            }
            postInvalidateOnAnimation()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = paddingLeft.toFloat()
        val top = paddingTop.toFloat()
        val right = width - paddingRight.toFloat()
        val bottom = height - paddingBottom.toFloat()
        val graphHeight = bottom - top

        repeat(3) { index ->
            val y = top + (graphHeight / 3f) * index
            canvas.drawLine(left, y, right, y, gridPaint)
        }
        canvas.drawLine(left, bottom, right, bottom, baselinePaint)

        val spacing = dp(4f)
        val barWidth = ((right - left) - spacing * (barCount - 1)) / barCount
        repeat(barCount) { index ->
            val x = left + index * (barWidth + spacing)
            val barHeight = graphHeight * levels[index]
            val peakY = bottom - graphHeight * peakLevels[index]
            val rect = RectF(x, bottom - barHeight, x + barWidth, bottom)
            canvas.drawRoundRect(rect, barWidth / 2f, barWidth / 2f, barPaint)
            canvas.drawRoundRect(
                RectF(x, peakY - dp(2f), x + barWidth, peakY),
                dp(1f),
                dp(1f),
                peakPaint,
            )
        }
    }

    private fun decodeMonoSamples(audioFile: DecodedAudioFile, chunk: ByteArray): FloatArray {
        val channels = max(audioFile.channelCount, 1)
        return when (audioFile.audioEncoding) {
            AudioFormat.ENCODING_PCM_8BIT -> {
                val sampleCount = chunk.size / channels
                FloatArray(sampleCount) { index ->
                    val byteIndex = index * channels
                    ((chunk[byteIndex].toInt() and 0xFF) - 128) / 128f
                }
            }

            AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                val frameSize = channels * 3
                val sampleCount = chunk.size / frameSize
                FloatArray(sampleCount) { index ->
                    val byteIndex = index * frameSize
                    val value =
                        (chunk[byteIndex].toInt() and 0xFF) or
                            ((chunk[byteIndex + 1].toInt() and 0xFF) shl 8) or
                            (chunk[byteIndex + 2].toInt() shl 16)
                    (value / 8_388_608f).coerceIn(-1f, 1f)
                }
            }

            AudioFormat.ENCODING_PCM_32BIT, AudioFormat.ENCODING_PCM_FLOAT -> {
                val frameSize = channels * 4
                val sampleCount = chunk.size / frameSize
                FloatArray(sampleCount) { index ->
                    val byteIndex = index * frameSize
                    if (audioFile.audioEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                        java.lang.Float.intBitsToFloat(
                            (chunk[byteIndex].toInt() and 0xFF) or
                                ((chunk[byteIndex + 1].toInt() and 0xFF) shl 8) or
                                ((chunk[byteIndex + 2].toInt() and 0xFF) shl 16) or
                                (chunk[byteIndex + 3].toInt() shl 24),
                        ).coerceIn(-1f, 1f)
                    } else {
                        val value =
                            (chunk[byteIndex].toInt() and 0xFF) or
                                ((chunk[byteIndex + 1].toInt() and 0xFF) shl 8) or
                                ((chunk[byteIndex + 2].toInt() and 0xFF) shl 16) or
                                (chunk[byteIndex + 3].toInt() shl 24)
                        (value / 2_147_483_648f).coerceIn(-1f, 1f)
                    }
                }
            }

            else -> {
                val frameSize = channels * 2
                val sampleCount = chunk.size / frameSize
                FloatArray(sampleCount) { index ->
                    val byteIndex = index * frameSize
                    val low = chunk[byteIndex].toInt() and 0xFF
                    val high = chunk[byteIndex + 1].toInt()
                    val value = (high shl 8) or low
                    (value / 32768f).coerceIn(-1f, 1f)
                }
            }
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
