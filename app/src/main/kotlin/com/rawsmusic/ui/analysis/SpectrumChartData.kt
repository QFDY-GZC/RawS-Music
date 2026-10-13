package com.rawsmusic.ui.analysis

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.rawsmusic.core.common.spectrum.AudioSpectrumAnalysis
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

internal enum class SpectrumChannel { Stereo, Left, Right }

internal fun AudioSpectrumAnalysis.chartCutoff(edge: SpectrumBandEdge?): Float? =
    // Energy extent is not evidence of a steep cutoff or the source's sample rate.
    edge?.takeIf { it.evidence == SpectrumEvidence.Moderate }?.frequencyHz
        ?.takeIf { it.isFinite() && it > 0f && it < maxFrequencyHz }

internal fun spectrumFrequency(fraction: Float, maximum: Float, logarithmic: Boolean): Float {
    val max = maximum.coerceAtLeast(1f)
    val min = minOf(20f, max)
    val t = fraction.coerceIn(0f, 1f)
    return if (logarithmic) min * (max / min).pow(t) else max * t
}

internal fun spectrumFraction(frequency: Float, maximum: Float, logarithmic: Boolean): Float {
    val max = maximum.coerceAtLeast(1f)
    val min = minOf(20f, max)
    return if (logarithmic && max > min) {
        (log10(frequency.coerceAtLeast(min) / min) / log10(max / min)).coerceIn(0f, 1f)
    } else (frequency / max).coerceIn(0f, 1f)
}

internal fun AudioSpectrumAnalysis.waterfallDb(frame: Int, frequency: Float, channel: SpectrumChannel): Float {
    if (waterfallBins <= 0 || waterfallFrames <= 0) return -120f
    val bin = (frequency / maxFrequencyHz.coerceAtLeast(1f) * (waterfallBins - 1))
        .coerceIn(0f, (waterfallBins - 1).toFloat())
    val low = bin.toInt()
    val high = (low + 1).coerceAtMost(waterfallBins - 1)
    val offset = frame.coerceIn(0, waterfallFrames - 1) * waterfallBins
    fun power(source: ByteArray): Double {
        val a = waterfallPower[(source.getOrNull(offset + low)?.toInt() ?: 0) and 255]
        val b = waterfallPower[(source.getOrNull(offset + high)?.toInt() ?: 0) and 255]
        return a + (b - a) * (bin - low)
    }
    val left = power(leftWaterfall)
    val combined = when {
        channels == 1 || channel == SpectrumChannel.Left -> left
        channel == SpectrumChannel.Right -> power(rightWaterfall)
        else -> (left + power(rightWaterfall)) * 0.5
    }
    return (10.0 * log10(combined)).toFloat()
}

private val waterfallPower = DoubleArray(256) { 10.0.pow((it * (120.0 / 255.0) - 120.0) / 10.0) }

internal suspend fun renderSpectrumBitmap(analysis: AudioSpectrumAnalysis, channel: SpectrumChannel, logarithmic: Boolean): ImageBitmap {
    val width = analysis.waterfallFrames.coerceAtLeast(1)
    val height = analysis.waterfallBins.coerceAtLeast(2)
    val colors = spectrumColors()
    val pixels = IntArray(width * height)
    // Reproject the cached linear bins; this does not add FFT resolution.
    for (y in 0 until height) {
        currentCoroutineContext().ensureActive()
        val frequency = spectrumFrequency(1f - y.toFloat() / (height - 1), analysis.maxFrequencyHz, logarithmic)
        for (x in 0 until width) {
            val db = analysis.waterfallDb(x, frequency, channel)
            pixels[y * width + x] = colors[((db + 120f) / 120f * 255f).roundToInt().coerceIn(0, 255)]
        }
    }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()
}

internal fun spectrumColors(): IntArray {
    val stops = intArrayOf(0xff080e17.toInt(), 0xff122550.toInt(), 0xff225ba1.toInt(),
        0xff32adcf.toInt(), 0xfff1ca6b.toInt(), 0xffffad48.toInt(), 0xffffe9b1.toInt())
    return IntArray(256) { index ->
        val position = index / 255f * (stops.size - 1)
        val start = position.toInt().coerceAtMost(stops.size - 2)
        val t = position - start
        fun part(shift: Int): Int {
            val a = (stops[start] ushr shift) and 255
            val b = (stops[start + 1] ushr shift) and 255
            return (a + (b - a) * t).roundToInt()
        }
        (255 shl 24) or (part(16) shl 16) or (part(8) shl 8) or part(0)
    }
}
