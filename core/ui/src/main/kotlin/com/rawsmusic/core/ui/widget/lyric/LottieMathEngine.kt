package com.rawsmusic.core.ui.widget.lyric

import kotlin.math.abs

/**
 * Lottie 核心数学引擎：精确还原 AE/Lottie 的贝塞尔缓动和关键帧插值
 */
object LottieMathEngine {

    /**
     * 精确的 Lottie 贝塞尔求值器 (X轴代表时间进度，Y轴代表动画进度)
     * P0=(0,0), P1=(cp1x, cp1y), P2=(cp2x, cp2y), P3=(1,1)
     */
    fun evaluateBezier(
        t: Float,       // 线性时间进度 0->1
        cp1x: Float, cp1y: Float, // 对应 Lottie JSON 的 'o' (Out tangent)
        cp2x: Float, cp2y: Float  // 对应 Lottie JSON 的 'i' (In tangent)
    ): Float {
        if (t <= 0f) return 0f
        if (t >= 1f) return 1f

        // 牛顿迭代法求参数 param，使得 X(param) ≈ t
        var param = t
        for (i in 0 until 8) {
            val currentX = getX(param, cp1x, cp2x) - t
            if (abs(currentX) < 0.0005f) break
            val dx = getDX(param, cp1x, cp2x)
            if (abs(dx) < 0.0001f) break
            param -= currentX / dx
        }
        return getY(param, cp1y, cp2y)
    }

    private fun getX(t: Float, cp1x: Float, cp2x: Float): Float {
        val u = 1f - t
        return 3f * u * u * t * cp1x + 3f * u * t * t * cp2x + t * t * t
    }

    private fun getY(t: Float, cp1y: Float, cp2y: Float): Float {
        val u = 1f - t
        return 3f * u * u * t * cp1y + 3f * u * t * t * cp2y + t * t * t
    }

    private fun getDX(t: Float, cp1x: Float, cp2x: Float): Float {
        val u = 1f - t
        return 3f * u * u * cp1x + 6f * u * t * (cp2x - cp1x) + 3f * t * t * (1f - cp2x)
    }

    /**
     * 采样 Lottie 关键帧数组 (支持多维数组如 [x, y, z])
     * 返回当前帧对应的属性值
     */
    fun sampleKeyframes(
        frame: Float,
        keyframes: List<LottieKeyframe>,
        interpolate: (startValue: FloatArray, endValue: FloatArray, progress: Float) -> FloatArray
    ): FloatArray {
        if (keyframes.isEmpty()) return FloatArray(0)
        if (frame <= keyframes.first().t) return keyframes.first().s
        if (frame >= keyframes.last().t) return keyframes.last().s

        for (i in 0 until keyframes.size - 1) {
            val kf = keyframes[i]
            val nextKf = keyframes[i + 1]
            if (frame in kf.t..nextKf.t) {
                val duration = nextKf.t - kf.t
                val linearProgress = if (duration == 0f) 1f else (frame - kf.t) / duration
                
                // 使用贝塞尔曲线计算缓动进度
                val easedProgress = evaluateBezier(
                    linearProgress,
                    kf.o[0], kf.o[1], // 出口切线
                    nextKf.i[0], nextKf.i[1] // 入口切线
                )
                return interpolate(kf.s, nextKf.s, easedProgress)
            }
        }
        return keyframes.last().s
    }

    /**
     * 采样标量关键帧 (1维)
     */
    fun sampleScalar(
        frame: Float,
        keyframes: List<LottieKeyframe>,
        defaultValue: Float = 0f
    ): Float {
        if (keyframes.isEmpty()) return defaultValue
        val result = sampleKeyframes(frame, keyframes) { start, end, progress ->
            floatArrayOf(start[0] + (end[0] - start[0]) * progress)
        }
        return if (result.isNotEmpty()) result[0] else defaultValue
    }

    /**
     * 采样2D关键帧 [x, y]
     */
    fun sample2D(
        frame: Float,
        keyframes: List<LottieKeyframe>,
        defaultX: Float = 0f,
        defaultY: Float = 0f
    ): FloatArray {
        if (keyframes.isEmpty()) return floatArrayOf(defaultX, defaultY)
        return sampleKeyframes(frame, keyframes) { start, end, progress ->
            floatArrayOf(
                start[0] + (end[0] - start[0]) * progress,
                start[1] + (end[1] - start[1]) * progress
            )
        }
    }

    /**
     * 采样3D关键帧 [x, y, z]
     */
    fun sample3D(
        frame: Float,
        keyframes: List<LottieKeyframe>,
        defaultX: Float = 0f,
        defaultY: Float = 0f,
        defaultZ: Float = 0f
    ): FloatArray {
        if (keyframes.isEmpty()) return floatArrayOf(defaultX, defaultY, defaultZ)
        return sampleKeyframes(frame, keyframes) { start, end, progress ->
            val size = minOf(start.size, end.size, 3)
            FloatArray(size) { i -> start[i] + (end[i] - start[i]) * progress }
        }
    }
}

/**
 * Lottie 关键帧数据结构
 */
data class LottieKeyframe(
    val t: Float,           // 起始帧号
    val s: FloatArray,      // 起始值 (可能是1维[opacity], 2维[x,y], 3维[x,y,z])
    val o: FloatArray,      // 出口切线 [x, y]
    val i: FloatArray       // 入口切线 [x, y]
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LottieKeyframe) return false
        return t == other.t && s.contentEquals(other.s) && o.contentEquals(other.o) && i.contentEquals(other.i)
    }

    override fun hashCode(): Int {
        var result = t.hashCode()
        result = 31 * result + s.contentHashCode()
        result = 31 * result + o.contentHashCode()
        result = 31 * result + i.contentHashCode()
        return result
    }
}