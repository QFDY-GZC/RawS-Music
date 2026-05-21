package com.rawsmusic.module.player.dsp

/**
 * 滤波器类型枚举
 */
enum class FilterType(val value: Int) {
    PEAK(0),           // 峰值EQ (RBJ标准)
    LOW_SHELF(1),      // 低架滤波
    HIGH_SHELF(2),     // 高架滤波
    LOW_PASS(3),       // 低通 (高切)
    HIGH_PASS(4),      // 高通 (低切)
    BAND_PASS(5),      // 带通
    NOTCH(6),          // 陷波
    PEAK_ANALOG(7);    // 峰值EQ (模拟建模)

    companion object {
        fun fromValue(value: Int): FilterType {
            return entries.firstOrNull { it.value == value } ?: PEAK
        }

        fun displayName(type: FilterType): String {
            return when (type) {
                PEAK -> "Peak"
                LOW_SHELF -> "Low Shelf"
                HIGH_SHELF -> "High Shelf"
                LOW_PASS -> "Low Pass"
                HIGH_PASS -> "High Pass"
                BAND_PASS -> "Band Pass"
                NOTCH -> "Notch"
                PEAK_ANALOG -> "Peak (Analog)"
            }
        }
    }
}

/**
 * PEQ滤波器参数
 */
data class PEQFilter(
    val type: FilterType = FilterType.PEAK,
    val frequency: Float = 1000f,   // 中心频率 (Hz)
    val gainDB: Float = 0f,         // 增益 (dB)
    val Q: Float = 1.414f,          // 品质因数 (√2, Butterworth 最平响应)
    val enabled: Boolean = true     // 是否启用
) {
    /**
     * 滤波器的显示名称
     */
    val displayType: String
        get() = FilterType.displayName(type)

    /**
     * 频率显示文本（保留一位小数）
     */
    val frequencyText: String
        get() = when {
            frequency >= 10000 -> "${String.format("%.1f", frequency / 1000)}k"
            frequency >= 1000 -> "${String.format("%.1f", frequency / 1000)}k"
            else -> String.format("%.1f", frequency)
        }

    /**
     * 增益显示文本（保留一位小数）
     */
    val gainText: String
        get() = String.format("%+.1f", gainDB)

    companion object {
        /** 最大滤波器数量 */
        const val MAX_FILTERS = 10

        /** 频率范围 */
        val FREQUENCY_RANGE = 20f..20000f

        /** 增益范围 */
        val GAIN_RANGE = -12f..12f

        /** Q值范围 */
        val Q_RANGE = 0.1f..10f

        /** 默认滤波器 */
        val DEFAULT = PEQFilter()

        /** 标准 10 段倍频程频率 (ISO 266) */
        private val STANDARD_OCTAVE_FREQS = floatArrayOf(
            31.5f, 63f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f
        )

        /** 生成默认的 10 段倍频程配置，全部启用，增益为 0 */
        fun createDefaultBands(): List<PEQFilter> {
            return STANDARD_OCTAVE_FREQS.map { freq ->
                PEQFilter(frequency = freq, gainDB = 0f, Q = 1.414f, enabled = true)
            }
        }
    }
}

/**
 * PEQ配置
 */
data class PEQConfig(
    val filters: List<PEQFilter> = emptyList(),
    val enabled: Boolean = false
) {
    /**
     * 获取启用的滤波器数量
     */
    val activeFilterCount: Int
        get() = filters.count { it.enabled }

    /**
     * 是否有滤波器
     */
    val hasFilters: Boolean
        get() = filters.isNotEmpty()

    companion object {
        /** 空配置 */
        val EMPTY = PEQConfig()
    }
}
