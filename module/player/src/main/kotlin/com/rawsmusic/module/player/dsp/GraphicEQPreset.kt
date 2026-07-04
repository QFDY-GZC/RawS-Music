package com.rawsmusic.module.player.dsp

/**
 * 图形均衡器预设
 * @param name 预设名称
 * @param bandCount 段数
 * @param gains 每段增益 (dB)，长度 = bandCount
 * @param isBuiltIn 是否内置预设
 */
data class GraphicEQPreset(
    val name: String,
    val bandCount: Int = 10,
    val gains: FloatArray,
    val isBuiltIn: Boolean = false
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GraphicEQPreset) return false
        return name == other.name && bandCount == other.bandCount && gains.contentEquals(other.gains)
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + bandCount
        result = 31 * result + gains.contentHashCode()
        return result
    }

    companion object {
        /** 10 段内置预设 */
        val BUILT_IN_10 = listOf(
            GraphicEQPreset("Normal", 10, floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f), isBuiltIn = true),
            GraphicEQPreset("Pop", 10, floatArrayOf(-1f, 1f, 3f, 2f, -1f, -1f, 0f, 1f, 2f, 1f), isBuiltIn = true),
            GraphicEQPreset("Rock", 10, floatArrayOf(3f, 1f, -1f, 1f, 3f, 2f, 0f, -1f, 1f, 3f), isBuiltIn = true),
            GraphicEQPreset("Jazz", 10, floatArrayOf(2f, 1f, -1f, 1f, 2f, 2f, -1f, 0f, 1f, 2f), isBuiltIn = true),
            GraphicEQPreset("Classical", 10, floatArrayOf(3f, 2f, 0f, 2f, 3f, 2f, 0f, 1f, 2f, 3f), isBuiltIn = true),
            GraphicEQPreset("Bass Boost", 10, floatArrayOf(5f, 4f, 3f, 1f, 0f, 0f, 0f, 0f, 0f, 0f), isBuiltIn = true),
            GraphicEQPreset("Treble Boost", 10, floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 1f, 2f, 3f, 4f), isBuiltIn = true),
            GraphicEQPreset("Vocal", 10, floatArrayOf(-1f, 0f, 1f, 3f, 4f, 4f, 3f, 1f, 0f, -1f), isBuiltIn = true),
            GraphicEQPreset("Electronic", 10, floatArrayOf(4f, 3f, 1f, 0f, -1f, 0f, 1f, 2f, 3f, 4f), isBuiltIn = true),
            GraphicEQPreset("Acoustic", 10, floatArrayOf(3f, 2f, 0f, 1f, 2f, 2f, 1f, 0f, 2f, 3f), isBuiltIn = true)
        )

        /**
         * 根据段数获取内置预设
         * 10 段有完整预设，其他段数只有 Normal
         */
        fun builtInPresetsForCount(bandCount: Int): List<GraphicEQPreset> {
            val count = bandCount.coerceIn(PEQFilter.MIN_FILTERS, PEQFilter.MAX_FILTERS)
            if (count == 10) return BUILT_IN_10

            // 其他段数只提供 Normal（全 0）和几个基础预设
            val normal = GraphicEQPreset("Normal", count, FloatArray(count), isBuiltIn = true)
            val bassBoost = GraphicEQPreset("Bass Boost", count, buildBassBoostGains(count), isBuiltIn = true)
            val trebleBoost = GraphicEQPreset("Treble Boost", count, buildTrebleBoostGains(count), isBuiltIn = true)
            return listOf(normal, bassBoost, trebleBoost)
        }

        /** 构建低音增强增益（前 1/3 段递增） */
        private fun buildBassBoostGains(count: Int): FloatArray {
            return FloatArray(count) { i ->
                val ratio = i.toFloat() / count
                when {
                    ratio < 0.1f -> 5f
                    ratio < 0.2f -> 4f
                    ratio < 0.3f -> 3f
                    ratio < 0.4f -> 1f
                    else -> 0f
                }
            }
        }

        /** 构建高音增强增益（后 1/3 段递增） */
        private fun buildTrebleBoostGains(count: Int): FloatArray {
            return FloatArray(count) { i ->
                val ratio = i.toFloat() / count
                when {
                    ratio > 0.9f -> 5f
                    ratio > 0.8f -> 4f
                    ratio > 0.7f -> 3f
                    ratio > 0.6f -> 1f
                    else -> 0f
                }
            }
        }

        /**
         * 将 10 段预设智能重采样到目标段数
         */
        fun resamplePreset(preset: GraphicEQPreset, targetCount: Int): GraphicEQPreset {
            if (preset.bandCount == targetCount) return preset

            val srcFreqs = PEQFilter.defaultFreqsForCount(preset.bandCount)
            val dstFreqs = PEQFilter.defaultFreqsForCount(targetCount)
            val newGains = FloatArray(targetCount)

            for (i in dstFreqs.indices) {
                val dstFreq = dstFreqs[i]
                // 找到源预设中最近的两个频率进行线性插值
                var leftIdx = 0
                var rightIdx = srcFreqs.size - 1
                for (j in srcFreqs.indices) {
                    if (srcFreqs[j] <= dstFreq) leftIdx = j
                    if (srcFreqs[j] >= dstFreq) {
                        rightIdx = j
                        break
                    }
                }
                if (leftIdx == rightIdx) {
                    newGains[i] = preset.gains[leftIdx]
                } else {
                    val leftFreq = srcFreqs[leftIdx]
                    val rightFreq = srcFreqs[rightIdx]
                    val t = ((dstFreq - leftFreq) / (rightFreq - leftFreq)).coerceIn(0f, 1f)
                    newGains[i] = preset.gains[leftIdx] * (1f - t) + preset.gains[rightIdx] * t
                }
            }

            return GraphicEQPreset(preset.name, targetCount, newGains, preset.isBuiltIn)
        }
    }
}
