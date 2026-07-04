package com.rawsmusic.core.ui.widget.powerlist

/**
 * 文本颜色动画状态。
 *
 * 动画管线：
 * 1. 场景切换触发 → setupTransition() 捕获旧色 fromColor、新色 toColor、重置 ratio=0
 * 2. 动画帧驱动 → updateRatio() 存储 ratio，触发重绘
 * 3. onDraw 渲染 → interpolateColor() 逐通道 ARGB 线性插值
 * 4. 完成提交 → finalize() 将新色永久应用
 */
class FastTextAnimState {

    /** 旧场景文本色 */
    var fromColor: Int = 0
        private set

    /** 新场景文本色 */
    var toColor: Int = 0
        private set

    /** 插值因子 0.0~1.0 */
    var ratio: Float = 0f
        private set

    /** 颜色过渡是否激活 */
    var isColorTransitionActive: Boolean = false
        private set

    /**
     * 场景切换时调用。
     *
     * @param currentColor 当前 Paint 颜色（旧色）
     * @param targetColor 目标场景的 ColorStateList 解析色（新色）
     */
    fun setupTransition(currentColor: Int, targetColor: Int) {
        fromColor = currentColor
        toColor = targetColor
        ratio = 0f
        isColorTransitionActive = (currentColor != targetColor)
    }

    /**
     * 每帧回调，存储插值因子。
     *
     * @param newRatio 0.0 = 完全显示旧色，1.0 = 完全显示新色
     */
    fun updateRatio(newRatio: Float) {
        ratio = newRatio.coerceIn(0f, 1f)
    }

    /**
     * 在 onDraw 中调用，返回插值后的 ARGB 颜色。
     *
     * 算法：fraction = (int)(ratio * 255 + 0.5)
     *       result_channel = from + (to - from) * fraction / 255
     */
    fun interpolateColor(): Int {
        if (!isColorTransitionActive) return toColor
        val fraction = (ratio * 255f + 0.5f).toInt()
        return interpolateArgb(fromColor, toColor, fraction)
    }

    /**
     * 完成动画，永久应用新色。
     */
    fun finalize() {
        fromColor = toColor
        ratio = 1f
        isColorTransitionActive = false
    }

    /**
     * 重置为初始状态。
     */
    fun reset() {
        fromColor = 0
        toColor = 0
        ratio = 0f
        isColorTransitionActive = false
    }
}

/**
 * 逐通道 ARGB 线性插值。
 *
 * 算法（每通道）：
 *   result = from + (to - from) * fraction / 255
 *
 * @param from 旧色 ARGB int
 * @param to 新色 ARGB int
 * @param fraction 0~255 的整数插值因子（0=完全 from，255=完全 to）
 * @return 插值后的 ARGB int
 */
fun interpolateArgb(from: Int, to: Int, fraction: Int): Int {
    // 提取 from 的四个通道
    val fromB = from and 0xFF
    val fromG = (from shr 8) and 0xFF
    val fromR = (from shr 16) and 0xFF
    val fromA = (from shr 24) and 0xFF

    // 提取 to 的四个通道
    val toB = to and 0xFF
    val toG = (to shr 8) and 0xFF
    val toR = (to shr 16) and 0xFF
    val toA = (to shr 24) and 0xFF

    // 逐通道插值：result = from + (to - from) * fraction / 255
    val r = fromR + (toR - fromR) * fraction / 255
    val g = fromG + (toG - fromG) * fraction / 255
    val b = fromB + (toB - fromB) * fraction / 255
    val a = fromA + (toA - fromA) * fraction / 255

    // 重组为 ARGB int
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}
