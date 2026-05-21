package com.rawsmusic.module.player.dsp

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

/**
 * AutoEq 单个滤波器参数
 */
data class AutoEqFilter(
    val type: String,      // "PK", "LSC", "HSC"
    val fc: Float,         // 中心频率 (Hz)
    val gain: Float,       // 增益 (dB)
    val q: Float           // Q值
) {
    /**
     * 转换为 PEQFilter
     */
    fun toPEQFilter(): PEQFilter {
        val filterType = when (type.uppercase()) {
            "PK" -> FilterType.PEAK
            "LSC" -> FilterType.LOW_SHELF
            "HSC" -> FilterType.HIGH_SHELF
            else -> FilterType.PEAK
        }
        return PEQFilter(
            type = filterType,
            frequency = fc,
            gainDB = gain,
            Q = q,
            enabled = true
        )
    }
}

/**
 * AutoEq 预设数据类
 */
data class AutoEqPreset(
    val name: String,                      // 耳机名称
    val source: String = "",               // 测量来源 (crinacle, oratory1990 等)
    val preamp: Float = 0f,                // 前置放大 (dB)
    val filters: List<AutoEqFilter>,       // 滤波器列表
    val rawText: String = ""               // 原始文本（用于缓存）
) {
    companion object {
        private val gson = Gson()

        /**
         * 从 ParametricEQ 文本解析预设
         * 格式示例：
         * Preamp: -6.0 dB
         * Filter 1: ON PK Fc 1000 Hz Gain -2.0 dB Q 1.41
         * Filter 2: ON LSC Fc 105 Hz Gain 5.5 dB Q 0.70
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
                    preamp = preamp,
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
                
                // 检查是否启用
                if (!content.startsWith("ON", ignoreCase = true)) return null
                
                val parts = content.substring(2).trim().split("\\s+".toRegex())
                if (parts.size < 8) return null

                // 解析类型
                val type = parts[0].uppercase()
                if (type !in listOf("PK", "LSC", "HSC", "LP", "HP", "BP", "NO")) return null

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

                if (fc <= 0f) return null

                return AutoEqFilter(type = type, fc = fc, gain = gain, q = q)
            } catch (e: Exception) {
                return null
            }
        }

        /**
         * 从 JSON 字符串反序列化
         */
        fun fromJson(json: String): AutoEqPreset? {
            return try {
                gson.fromJson(json, AutoEqPreset::class.java)
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
     * 转换为 PEQFilter 列表
     */
    fun toPEQFilters(): List<PEQFilter> {
        return filters.map { it.toPEQFilter() }
    }
}
