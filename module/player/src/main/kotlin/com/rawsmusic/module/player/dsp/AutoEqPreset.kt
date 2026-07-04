package com.rawsmusic.module.player.dsp

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * AutoEq 单个滤波器参数
 */
data class AutoEqFilter(
    val type: String,      // "PK", "LSC", "HSC", "LP", "HP", "BP", "NO"
    val fc: Float,         // 中心频率 (Hz)
    val gain: Float,       // 增益 (dB)
    val q: Float           // Q值
) {
    /**
     * 转换为 PEQFilter（带安全化）
     */
    fun toPEQFilter(): PEQFilter {
        val filterType = when (type.uppercase()) {
            "PK" -> FilterType.PEAK
            "LSC", "LS" -> FilterType.LOW_SHELF
            "HSC", "HS" -> FilterType.HIGH_SHELF
            "LP", "LPQ" -> FilterType.LOW_PASS
            "HP", "HPQ" -> FilterType.HIGH_PASS
            "BP" -> FilterType.BAND_PASS
            "NO", "N", "NOTCH" -> FilterType.NOTCH
            else -> FilterType.PEAK
        }

        return PEQFilter(
            type = filterType,
            frequency = safeFrequency(fc),
            gainDB = safeGain(gain),
            Q = safeQ(q),
            enabled = true
        ).sanitized()
    }

    private fun safeFrequency(value: Float): Float {
        return if (value.isFinite()) {
            value.coerceIn(20f, 20000f)
        } else {
            1000f
        }
    }

    private fun safeGain(value: Float): Float {
        return if (value.isFinite()) {
            value.coerceIn(-24f, 24f)
        } else {
            0f
        }
    }

    private fun safeQ(value: Float): Float {
        return if (value.isFinite()) {
            value.coerceIn(0.05f, 24f)
        } else {
            1.414f
        }
    }
}

/**
 * AutoEq 预设数据类
 */
data class AutoEqPreset(
    val name: String,                      // 耳机名称
    val source: String = "",               // 测量来源 (crinacle, oratory1990 等)
    val preamp: Float = 0f,                // 前置放大 (dB)
    val filters: List<AutoEqFilter> = emptyList(),
    val rawText: String = ""               // 原始文本（用于缓存）
) {
    /**
     * 安全化的 preamp（防 NaN/Infinity）
     */
    val safePreamp: Float
        get() = if (preamp.isFinite()) preamp.coerceIn(-12f, 12f) else 0f

    companion object {
        private val gson = Gson()

        private val SUPPORTED_FILTER_TYPES = setOf(
            "PK",
            "LSC", "LS",
            "HSC", "HS",
            "LP", "LPQ",
            "HP", "HPQ",
            "BP",
            "NO", "N", "NOTCH"
        )

        /**
         * 从 ParametricEQ 文本解析预设
         * 格式示例：
         * Preamp: -6.0 dB
         * Filter 1: ON PK Fc 1000 Hz Gain -2.0 dB Q 1.41
         * Filter 2: ON LSC Fc 105 Hz Gain 5.5 dB Q 0.70
         * Filter 3: OFF PK Fc 1000 Hz Gain 0.0 dB Q 1.00  ← 跳过
         */
        fun parse(name: String, source: String = "", text: String): AutoEqPreset? {
            try {
                val lines = text.lines().filter { it.isNotBlank() }
                if (lines.isEmpty()) return null

                var preamp = 0f
                val filters = mutableListOf<AutoEqFilter>()

                for (line in lines) {
                    val trimmed = line.trim()

                    // 解析 Preamp
                    if (trimmed.startsWith("Preamp:", ignoreCase = true)) {
                        val preampStr = trimmed.substringAfter(":").trim()
                            .removeSuffix("dB").trim()
                        preamp = preampStr.toFloatOrNull() ?: 0f
                        continue
                    }

                    // 解析 Filter
                    if (trimmed.startsWith("Filter", ignoreCase = true)) {
                        val filter = parseFilterLine(trimmed)
                        if (filter != null) {
                            filters.add(filter)
                        }
                    }
                }

                if (filters.isEmpty()) return null

                return AutoEqPreset(
                    name = name,
                    source = source,
                    preamp = if (preamp.isFinite()) preamp.coerceIn(-12f, 12f) else 0f,
                    filters = filters,
                    rawText = text
                )
            } catch (e: Exception) {
                return null
            }
        }

        /**
         * 解析单行滤波器
         * 格式: Filter 1: ON PK Fc 1000 Hz Gain -2.0 dB Q 1.41
         */
        private fun parseFilterLine(line: String): AutoEqFilter? {
            try {
                // 提取冒号后的内容
                val content = line.substringAfter(":").trim()

                // 只导入 ON 状态的滤波器，跳过 OFF
                if (!content.startsWith("ON", ignoreCase = true)) return null

                val parts = content.substring(2).trim().split("\\s+".toRegex())
                if (parts.size < 8) return null

                // 解析类型
                val type = parts[0].uppercase()
                if (type !in SUPPORTED_FILTER_TYPES) return null

                // 找到 Fc, Gain, Q 的位置
                var fc = 0f
                var gain = 0f
                var q = 1.414f

                var i = 1
                while (i < parts.size) {
                    when (parts[i].uppercase()) {
                        "FC" -> {
                            fc = parts.getOrNull(i + 1)?.toFloatOrNull() ?: 0f
                            i += 2
                        }
                        "GAIN" -> {
                            gain = parts.getOrNull(i + 1)?.toFloatOrNull() ?: 0f
                            i += 2
                        }
                        "Q" -> {
                            q = parts.getOrNull(i + 1)?.toFloatOrNull() ?: 1.414f
                            i += 2
                        }
                        else -> i++
                    }
                }

                if (fc <= 0f || !fc.isFinite()) return null

                return AutoEqFilter(type = type, fc = fc, gain = gain, q = q)
            } catch (e: Exception) {
                return null
            }
        }

        /**
         * 从 JSON 字符串反序列化
         * 使用 TypeToken 保留 filters 字段的 List<AutoEqFilter> 泛型信息
         * 防止 R8 minify 擦除签名后 Gson 将其反序列化为 List<LinkedTreeMap>
         */
        fun fromJson(json: String): AutoEqPreset? {
            return try {
                val type = object : TypeToken<AutoEqPreset>() {}.type
                val raw = gson.fromJson<AutoEqPreset>(json, type) ?: return null

                raw.copy(
                    preamp = if (raw.preamp.isFinite()) raw.preamp.coerceIn(-12f, 12f) else 0f,
                    filters = raw.filters.filter {
                        it.fc.isFinite() && it.q.isFinite() && it.gain.isFinite()
                    }
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * 序列化为 JSON 字符串
     */
    fun toJson(): String {
        return gson.toJson(this)
    }

    /**
     * 转换为 PEQFilter 列表（统一 sanitize）
     */
    fun toPEQFilters(): List<PEQFilter> {
        return filters
            .map { it.toPEQFilter().sanitized() }
            .filter { it.frequency in 20f..20000f }
    }
}
