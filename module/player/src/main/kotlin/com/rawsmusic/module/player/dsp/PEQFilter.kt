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
    val frequency: Float = 1000f,
    val gainDB: Float = 0f,
    val Q: Float = 1.414f,
    val enabled: Boolean = false
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
        get() = if (gainDB >= 0) "+${String.format("%.1f", gainDB)} dB"
        else "${String.format("%.1f", gainDB)} dB"

    /**
     * Q值显示文本
     */
    val qText: String
        get() = String.format("%.2f", Q)

    /**
     * 参数安全化：防止异常预设/导入数据导致滤波器不稳定或爆音
     * 先处理 NaN/Infinity，再 coerceIn 到安全范围
     * 导入预设时使用更宽范围，UI 显示可继续用更窄的范围
     */
    fun sanitized(): PEQFilter {
        val safeFreq = frequency.safeOr(1000f)
        val safeGain = gainDB.safeOr(0f)
        val safeQ = Q.safeOr(1.414f)

        return copy(
            frequency = safeFreq.coerceIn(20f, 20000f),
            gainDB = safeGain.coerceIn(-24f, 24f),
            Q = safeQ.coerceIn(0.05f, 24f)
        )
    }

    private fun Float.safeOr(defaultValue: Float): Float {
        return if (isFinite()) this else defaultValue
    }

    companion object {
        /** 最小滤波器数量 */
        const val MIN_FILTERS = 10

        /** 最大滤波器数量 */
        const val MAX_FILTERS = 40

        /** 频率范围 */
        val FREQUENCY_RANGE = 20f..20000f

        /** 增益范围（UI 显示） */
        val GAIN_RANGE = -12f..12f

        /** Q值范围（UI 显示） */
        val Q_RANGE = 0.1f..10f

        /** 默认滤波器 */
        val DEFAULT = PEQFilter()

        /** 标准 10 段倍频程频率 (ISO 266) */
        private val STANDARD_10_BAND_FREQS = floatArrayOf(
            31.5f, 63f, 125f, 250f, 500f,
            1000f, 2000f, 4000f, 8000f, 16000f
        )

        /**
         * 根据段数生成对数均匀分布的默认频率
         * 10 段使用 ISO 266 标准倍频程
         * 11-40 段在 25Hz-18kHz 间对数均匀插值
         */
        fun defaultFreqsForCount(count: Int): FloatArray {
            val c = count.coerceIn(MIN_FILTERS, MAX_FILTERS)

            if (c == 10) {
                return STANDARD_10_BAND_FREQS.copyOf()
            }

            val minHz = 25.0
            val maxHz = 18000.0

            return FloatArray(c) { i ->
                val t = i.toDouble() / (c - 1).toDouble()
                (minHz * Math.pow(maxHz / minHz, t)).toFloat()
            }
        }

        /**
         * 生成默认配置，全部 disabled，增益为 0
         * disabled 避免 40 段 0dB 也参与 DSP 白跑 biquad
         */
        fun createDefaultBands(count: Int = MIN_FILTERS): List<PEQFilter> {
            return defaultFreqsForCount(count).map { freq ->
                PEQFilter(
                    type = FilterType.PEAK,
                    frequency = freq,
                    gainDB = 0f,
                    Q = 1.414f,
                    enabled = false
                )
            }
        }

        /**
         * 图形 EQ 转 PEQ 时的推荐 Q 值
         * 段数越多，Q 值越大，避免相邻频段互相干扰
         */
        fun qForGraphicBand(count: Int): Float {
            val c = count.coerceIn(MIN_FILTERS, MAX_FILTERS)

            return when {
                c <= 10 -> 1.414f
                c <= 20 -> 2.0f
                c <= 31 -> 2.8f
                else -> 3.2f
            }
        }
    }
}

/**
 * PEQ配置
 */
data class PEQConfig(
    val filters: List<PEQFilter> = emptyList(),
    val enabled: Boolean = false,
    val bandCount: Int = PEQFilter.MIN_FILTERS
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

    /**
     * 安全化的段数（防止越界）
     */
    val resolvedBandCount: Int
        get() = bandCount.coerceIn(PEQFilter.MIN_FILTERS, PEQFilter.MAX_FILTERS)

    companion object {
        /** 空配置 */
        val EMPTY = PEQConfig()
    }
}
