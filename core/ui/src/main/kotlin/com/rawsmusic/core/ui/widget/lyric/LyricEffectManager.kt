package com.rawsmusic.core.ui.widget.lyric

import android.content.Context
import android.util.Log

/**
 * 歌词动画效果管理器
 * 完全由 JSON 数据驱动，无硬编码 fallback
 */
class LyricEffectManager(private val context: Context) {
    
    companion object {
        private const val TAG = "LyricEffectManager"
        private const val ASSET_PATH = "lyric_effect/letter/input.json"
    }
    
    private var config: LyricAnimationConfig? = null
    private var loadAttempted = false
    
    /**
     * 获取动画配置（懒加载）
     */
    fun getConfig(): LyricAnimationConfig {
        if (!loadAttempted) {
            loadAttempted = true
            Log.i(TAG, "尝试从 assets 加载 JSON: $ASSET_PATH")
            config = LottieEffectParser.loadFromInputAssets(context, ASSET_PATH)
            if (config != null) {
                Log.i(TAG, "JSON 加载成功! layers=${config!!.layers.size}, fps=${config!!.fps}")
            } else {
                Log.w(TAG, "JSON 加载失败，使用空默认配置")
            }
        }
        return config ?: LyricAnimationConfig()
    }
    
    /**
     * 获取 Line0 配置（可能为 null）
     */
    fun getLine0Config(): LineConfig? = getConfig().line0
    
    /**
     * 获取 Line1 配置（可能为 null）
     */
    fun getLine1Config(): LineConfig? = getConfig().line1
    
    /**
     * 获取设计稿尺寸
     */
    fun getDesignSize(): Pair<Float, Float> {
        val cfg = getConfig()
        return Pair(cfg.designWidth, cfg.designHeight)
    }
    
    /**
     * 获取 FPS
     */
    fun getFps(): Float = getConfig().fps
}

/**
 * 歌词动画总配置
 */
data class LyricAnimationConfig(
    val fps: Float = 24f,
    val designWidth: Float = 1080f,
    val designHeight: Float = 2700f,
    val layers: List<LineConfig> = emptyList()
) {
    /** 第一行配置（索引0） */
    val line0: LineConfig? get() = layers.getOrNull(0)
    
    /** 第二行配置（索引1） */
    val line1: LineConfig? get() = layers.getOrNull(1)
}

/**
 * 单行歌词动画配置 (基于 Lottie 标准关键帧)
 */
data class LineConfig(
    // ===== 图层基础变换 (ks) =====
    val anchor: FloatArray,                     // ks.a [x, y, z]
    val positionKfs: List<LottieKeyframe>,      // ks.p.k (位移关键帧)
    val scaleKfs: List<LottieKeyframe>,         // ks.s.k (缩放关键帧)
    val opacityKfs: List<LottieKeyframe>,       // ks.o.k (透明度关键帧)

    // ===== 逐字动画器 - Range Selector (t.a[0].s) =====
    val rangeOffsetKfs: List<LottieKeyframe>,   // t.a[0].s.o.k (揭示偏移关键帧)
    val rangeRandomize: Boolean,                // t.a[0].s.rn (是否随机揭示: Line1为true)

    // ===== 逐字动画器 - Animator Properties (t.a[0].a) =====
    val charPositionKfs: List<LottieKeyframe>,  // t.a[0].a.p.k (逐字位移关键帧)
    val charScaleKfs: List<LottieKeyframe>,     // t.a[0].a.s.k (逐字缩放关键帧)
    val charOpacityKfs: List<LottieKeyframe>,   // t.a[0].a.o.k (逐字透明度关键帧)
    val charBlurKfs: List<LottieKeyframe>,      // t.a[0].a.bl.k (逐字模糊关键帧)
    
    val designWidth: Float = 1080f,
    val designHeight: Float = 2700f
) {
    // 默认插值器：线性组合多维数组
    private val defaultInterpolator: (FloatArray, FloatArray, Float) -> FloatArray = { start, end, progress ->
        FloatArray(start.size.coerceAtMost(end.size)) { i -> start[i] + (end[i] - start[i]) * progress }
    }

    fun getAnchor(frame: Float): FloatArray = anchor
    
    fun getPosition(frame: Float): FloatArray = LottieMathEngine.sampleKeyframes(frame, positionKfs, defaultInterpolator)
    
    fun getScale(frame: Float): FloatArray = LottieMathEngine.sampleKeyframes(frame, scaleKfs, defaultInterpolator)
    
    fun getOpacity(frame: Float): Float {
        val result = LottieMathEngine.sampleKeyframes(frame, opacityKfs, defaultInterpolator)
        return if (result.isNotEmpty()) result[0] / 100f else 1f
    }
    
    fun getRangeOffset(frame: Float): Float {
        val result = LottieMathEngine.sampleKeyframes(frame, rangeOffsetKfs, defaultInterpolator)
        return if (result.isNotEmpty()) result[0] / 100f else 0f
    }
    
    fun getCharPosition(frame: Float): FloatArray = LottieMathEngine.sampleKeyframes(frame, charPositionKfs, defaultInterpolator)
    
    fun getCharScale(frame: Float): FloatArray = LottieMathEngine.sampleKeyframes(frame, charScaleKfs, defaultInterpolator)
    
    fun getCharOpacity(frame: Float): Float {
        val result = LottieMathEngine.sampleKeyframes(frame, charOpacityKfs, defaultInterpolator)
        return if (result.isNotEmpty()) result[0] / 100f else 1f
    }
    
    fun getCharBlur(frame: Float): Float {
        val result = LottieMathEngine.sampleKeyframes(frame, charBlurKfs, defaultInterpolator)
        return if (result.isNotEmpty()) result[0] else 0f
    }

    // 兼容旧接口
    val anchorX: Float get() = if (anchor.size >= 2) anchor[0] else 0f
    val anchorY: Float get() = if (anchor.size >= 2) anchor[1] else 0f
    val endMs: Float get() {
        // 取所有关键帧类型的最大结束时间，避免静态position导致endMs=0
        val candidates = listOf(
            positionKfs.lastOrNull()?.t ?: 0f,
            scaleKfs.lastOrNull()?.t ?: 0f,
            opacityKfs.lastOrNull()?.t ?: 0f,
            rangeOffsetKfs.lastOrNull()?.t ?: 0f,
            charPositionKfs.lastOrNull()?.t ?: 0f,
            charScaleKfs.lastOrNull()?.t ?: 0f,
            charOpacityKfs.lastOrNull()?.t ?: 0f,
            charBlurKfs.lastOrNull()?.t ?: 0f
        )
        return candidates.maxOrNull() ?: 0f
    }
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LineConfig) return false
        return anchor.contentEquals(other.anchor) && rangeRandomize == other.rangeRandomize
    }

    override fun hashCode(): Int {
        var result = anchor.contentHashCode()
        result = 31 * result + rangeRandomize.hashCode()
        return result
    }
}