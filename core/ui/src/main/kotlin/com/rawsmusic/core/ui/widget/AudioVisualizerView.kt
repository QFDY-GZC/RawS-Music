package com.rawsmusic.core.ui.widget

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.media.audiofx.Visualizer
import android.os.Build
import android.util.AttributeSet
import android.util.Log
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 音频可视化 View
 *
 * 采用"中心流线 + 镜像能量柱 + 发光底座"的方式呈现，
 * 适合信笺模式或歌词页底部的动态氛围渲染。
 */
class AudioVisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val BAR_COUNT = 80
        private const val UPDATE_DELAY_MS = 16L
        private const val SIMULATION_DELAY_MS = 33L
        private const val SMOOTH_FACTOR = 0.15f
        private const val SIMULATION_SPEED = 0.08f
        private const val MIN_VISIBLE_LEVEL = 0.015f
    }

    private val density = resources.displayMetrics.density
    private val barRect = RectF()
    
    // 正向曲线路径
    private val wavePath = Path()
    // 反向曲线路径（镜像）
    private val reverseWavePath = Path()

    // 基础画笔
    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    // 发光画笔
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        maskFilter = BlurMaskFilter(18f * density, BlurMaskFilter.Blur.NORMAL)
    }
    // 正向线条画笔
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 2.2f * density
    }
    // 反向线条画笔（镜像，透明度较低）
    private val reverseLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 2.2f * density
        alpha = 80
    }
    // 模糊曲线画笔
    private val blurLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 2.2f * density
        alpha = 50
        maskFilter = BlurMaskFilter(18f * density, BlurMaskFilter.Blur.NORMAL)
    }
    // 反向模糊曲线画笔
    private val reverseBlurLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 2.2f * density
        alpha = 15
        maskFilter = BlurMaskFilter(18f * density, BlurMaskFilter.Blur.NORMAL)
    }
    // 底部线条画笔
    private val bottomLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 1.4f * density
    }
    // 模糊底部线条画笔
    private val bottomBlurPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 1.4f * density
        alpha = 50
        maskFilter = BlurMaskFilter(18f * density, BlurMaskFilter.Blur.NORMAL)
    }

    private val waveformLevels = FloatArray(BAR_COUNT)
    private val targetLevels = FloatArray(BAR_COUNT)
    private var active = false
    private var usingRealAudio = false
    private var simulationPhase = 0f
    private var lastMeasuredWidth = 0
    private var visualizer: Visualizer? = null
    private var boundAudioSessionId = 0

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    /**
     * 绑定播放器音频会话，使用系统 Visualizer 捕获真实播放波形。
     */
    fun bindAudioSession(audioSessionId: Int): Boolean {
        if (audioSessionId <= 0 || !hasRecordAudioPermission()) {
            releaseVisualizer()
            return false
        }
        if (boundAudioSessionId == audioSessionId && visualizer != null) {
            active = true
            return true
        }

        releaseVisualizer()
        return try {
            val captureSize = Visualizer.getCaptureSizeRange().lastOrNull() ?: 1024
            visualizer = Visualizer(audioSessionId).apply {
                enabled = false
                this.captureSize = captureSize
                setDataCaptureListener(
                    object : Visualizer.OnDataCaptureListener {
                        override fun onWaveFormDataCapture(
                            visualizer: Visualizer?,
                            waveform: ByteArray?,
                            samplingRate: Int
                        ) {
                            updateWaveform(waveform)
                        }

                        override fun onFftDataCapture(
                            visualizer: Visualizer?,
                            fft: ByteArray?,
                            samplingRate: Int
                        ) = Unit
                    },
                    Visualizer.getMaxCaptureRate() / 2,
                    true,
                    false
                )
                enabled = true
            }
            boundAudioSessionId = audioSessionId
            active = true
            true
        } catch (t: Throwable) {
            Log.w("AudioVisualizerView", "bindAudioSession failed: session=$audioSessionId", t)
            releaseVisualizer()
            false
        }
    }

    private fun hasRecordAudioPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun releaseVisualizer() {
        visualizer?.let {
            try { it.enabled = false } catch (_: Throwable) {}
            try { it.release() } catch (_: Throwable) {}
        }
        visualizer = null
        boundAudioSessionId = 0
    }

    /**
     * 更新 PCM 数据，用于直接根据播放器输出驱动可视化。
     */
    fun updatePcmWaveform(
        buffer: ByteArray?,
        read: Int,
        channels: Int,
        sampleRate: Int,
        bitsPerSample: Int
    ) {
        if (buffer == null || read <= 0) {
            return
        }

        usingRealAudio = true
        active = true
        removeCallbacks(simulationRunnable)
        releaseVisualizer()

        val bytesPerSample = when {
            bitsPerSample <= 8 -> 1
            bitsPerSample <= 16 -> 2
            bitsPerSample <= 24 -> 3
            else -> 4
        }
        val frameSize = max(1, channels) * bytesPerSample
        val usableBytes = read.coerceAtMost(buffer.size) - (read.coerceAtMost(buffer.size) % frameSize)
        if (usableBytes <= 0) {
            targetLevels.fill(0f)
            postInvalidateOnAnimation()
            return
        }

        val frames = usableBytes / frameSize
        val step = max(1f, frames.toFloat() / BAR_COUNT)
        val rawLevels = FloatArray(BAR_COUNT)
        for (i in 0 until BAR_COUNT) {
            val frameStart = (i * step).toInt().coerceIn(0, frames - 1)
            val frameEnd = min(frames - 1, ((i + 1) * step).toInt())
            var peak = 0f
            var sum = 0f
            var count = 0
            for (frame in frameStart..frameEnd) {
                val base = frame * frameSize
                for (ch in 0 until channels) {
                    val sampleOffset = base + ch * bytesPerSample
                    val sample = abs(decodePcmSample(buffer, sampleOffset, bytesPerSample, bitsPerSample))
                    peak = max(peak, sample)
                    sum += sample
                    count++
                }
            }
            val average = if (count > 0) sum / count else 0f
            rawLevels[i] = (peak * 0.72f + average * 1.65f).coerceIn(0f, 1f)
        }

        for (i in 0 until BAR_COUNT) {
            val left = rawLevels[max(0, i - 1)]
            val center = rawLevels[i]
            val right = rawLevels[min(BAR_COUNT - 1, i + 1)]
            val shaped = (left * 0.22f + center * 0.56f + right * 0.22f).coerceIn(0f, 1f)
            targetLevels[i] = if (shaped < MIN_VISIBLE_LEVEL) 0f else shaped
        }
        postInvalidateOnAnimation()
    }

    private fun decodePcmSample(buffer: ByteArray, offset: Int, bytesPerSample: Int, bitsPerSample: Int): Float {
        if (offset < 0 || offset >= buffer.size) return 0f
        return when (bytesPerSample) {
            1 -> (buffer[offset].toInt() / 128f).coerceIn(-1f, 1f)
            2 -> {
                if (offset + 1 >= buffer.size) return 0f
                val sample = ((buffer[offset + 1].toInt() shl 8) or (buffer[offset].toInt() and 0xFF))
                val signed = sample.toShort().toInt()
                (signed / 32768f).coerceIn(-1f, 1f)
            }
            3 -> {
                if (offset + 2 >= buffer.size) return 0f
                val sample = (buffer[offset].toInt() and 0xFF) or
                    ((buffer[offset + 1].toInt() and 0xFF) shl 8) or
                    ((buffer[offset + 2].toInt() and 0xFF) shl 16)
                val signed = if (sample and 0x800000 != 0) sample or -0x1000000 else sample
                (signed / 8388608f).coerceIn(-1f, 1f)
            }
            else -> {
                if (offset + 3 >= buffer.size) return 0f
                val sample = (buffer[offset].toInt() and 0xFF) or
                    ((buffer[offset + 1].toInt() and 0xFF) shl 8) or
                    ((buffer[offset + 2].toInt() and 0xFF) shl 16) or
                    ((buffer[offset + 3].toInt() and 0xFF) shl 24)
                (sample / 2147483648f).coerceIn(-1f, 1f)
            }
        }
    }

    /**
     * 更新波形数据。
     * @param waveform 来自 Visualizer API 的原始波形字节数组。
     */
    fun updateWaveform(waveform: ByteArray?) {
        active = waveform != null && waveform.isNotEmpty()
        if (waveform == null || waveform.isEmpty()) {
            targetLevels.fill(0f)
            invalidate()
            return
        }

        val waveformData = waveform
        val step = max(1f, waveformData.size.toFloat() / BAR_COUNT)
        for (i in 0 until BAR_COUNT) {
            val start = (i * step).toInt().coerceIn(0, waveformData.lastIndex)
            val end = min(waveformData.lastIndex, ((i + 1) * step).toInt())
            var peak = 0f
            for (j in start..end) {
                peak = max(peak, abs(waveformData[j].toInt()).toFloat() / 128f)
            }
            val shaped = peak.coerceIn(0f, 1f)
            targetLevels[i] = if (shaped < MIN_VISIBLE_LEVEL) 0f else shaped
        }
        postInvalidateOnAnimation()
    }

    /**
     * 无真实音频输入时，开启模拟动画。
     */
    fun startSimulation() {
        if (usingRealAudio || visualizer != null) {
            active = true
            return
        }
        active = true
        removeCallbacks(simulationRunnable)
        post(simulationRunnable)
    }

    /**
     * 停止动画并清空状态。
     */
    fun stop() {
        active = false
        usingRealAudio = false
        removeCallbacks(simulationRunnable)
        releaseVisualizer()
        targetLevels.fill(0f)
        waveformLevels.fill(0f)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        lastMeasuredWidth = w
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        animateLevels()
        drawVisualization(canvas)

        if (active) {
            postInvalidateDelayed(UPDATE_DELAY_MS)
        }
    }

    private fun animateLevels() {
        for (i in 0 until BAR_COUNT) {
            waveformLevels[i] += (targetLevels[i] - waveformLevels[i]) * SMOOTH_FACTOR
            if (!active && waveformLevels[i] < 0.01f) {
                waveformLevels[i] = 0f
            }
        }
    }

    private fun drawVisualization(canvas: Canvas) {
        val canvasWidth = width.toFloat()
        val canvasHeight = height.toFloat()
        val barWidth = canvasWidth / BAR_COUNT
        val offsetY = 2f * density + 2f
        val centerY = canvasHeight * 0.5f
        val maxBarHeight = canvasHeight * 0.4f

        // 计算每个频段的目标Y坐标
        val destY = FloatArray(BAR_COUNT)
        for (i in 0 until BAR_COUNT) {
            val level = waveformLevels[i]
            val barHeight = maxBarHeight * level * level
            destY[i] = canvasHeight - barHeight - offsetY
        }

        // 绘制正向线条
        val lineCoords = FloatArray(BAR_COUNT * 4)
        for (i in 0 until BAR_COUNT) {
            val x = i * barWidth + barWidth / 2f
            val y = destY[i]
            lineCoords[i * 4] = x
            lineCoords[i * 4 + 1] = canvasHeight - offsetY
            lineCoords[i * 4 + 2] = x
            lineCoords[i * 4 + 3] = y
        }
        canvas.drawLines(lineCoords, linePaint)

        // 绘制反向线条（镜像）
        val reverseLineCoords = FloatArray(BAR_COUNT * 4)
        for (i in 0 until BAR_COUNT) {
            val x = i * barWidth + barWidth / 2f
            val reverseY = canvasHeight - destY[BAR_COUNT - 1 - i] + offsetY
            reverseLineCoords[i * 4] = x
            reverseLineCoords[i * 4 + 1] = canvasHeight - offsetY
            reverseLineCoords[i * 4 + 2] = x
            reverseLineCoords[i * 4 + 3] = reverseY
        }
        canvas.drawLines(reverseLineCoords, reverseLinePaint)

        // 绘制正向曲线
        wavePath.reset()
        for (i in 0 until BAR_COUNT) {
            val x = i * barWidth + barWidth / 2f
            val y = destY[i]
            if (i == 0) {
                wavePath.moveTo(x, y)
            } else {
                wavePath.lineTo(x, y)
            }
        }
        canvas.drawPath(wavePath, linePaint)
        canvas.drawPath(wavePath, blurLinePaint)

        // 绘制反向曲线（镜像）
        reverseWavePath.reset()
        for (i in 0 until BAR_COUNT) {
            val x = i * barWidth + barWidth / 2f
            val reverseY = canvasHeight - destY[BAR_COUNT - 1 - i] + offsetY
            if (i == 0) {
                reverseWavePath.moveTo(x, reverseY)
            } else {
                reverseWavePath.lineTo(x, reverseY)
            }
        }
        canvas.drawPath(reverseWavePath, reverseLinePaint)
        canvas.drawPath(reverseWavePath, reverseBlurLinePaint)

        // 绘制底部线条
        val bottomLineCoords = floatArrayOf(
            barWidth / 2f, canvasHeight - offsetY,
            (BAR_COUNT - 1) * barWidth + barWidth / 2f, canvasHeight - offsetY
        )
        canvas.drawLines(bottomLineCoords, bottomLinePaint)
        canvas.drawLines(bottomLineCoords, bottomBlurPaint)
    }

    private val simulationRunnable = object : Runnable {
        override fun run() {
            if (!active) return

            simulationPhase += SIMULATION_SPEED
            val widthSeed = if (lastMeasuredWidth > 0) lastMeasuredWidth.toFloat() else width.toFloat().coerceAtLeast(1f)
            for (i in 0 until BAR_COUNT) {
                val base = (sin(simulationPhase + i * 0.35f) * 0.5f + 0.5f)
                val detail = (sin(simulationPhase * 1.9f + i * 0.12f + widthSeed * 0.0003f) * 0.5f + 0.5f)
                val accent = (cos(simulationPhase * 0.7f + i * 0.18f) * 0.5f + 0.5f)
                targetLevels[i] = ((base * 0.52f) + (detail * 0.28f) + (accent * 0.20f)).coerceIn(0f, 1f)
            }
            postInvalidateOnAnimation()
            postDelayed(this, SIMULATION_DELAY_MS)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stop()
    }
}