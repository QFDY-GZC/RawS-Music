package com.rawsmusic.module.player.dsp

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 图形均衡器控制器
 * 包装 ParametricEQController，将固定频率/Q 的 PEQ 滤波器暴露为"只调增益"的图形 EQ
 */
class GraphicEQController(private val peqController: ParametricEQController) {

    companion object {
        private const val TAG = "GraphicEQController"
    }

    // 段数（与 PEQ 共享）
    val bandCount: StateFlow<Int> = peqController.bandCount

    // 启用状态（与 PEQ 共享）
    val isEnabled: StateFlow<Boolean> = peqController.isEnabled

    // 前级增益（与 PEQ 共享）
    val preamp: StateFlow<Float> = peqController.preamp

    // 当前每段增益
    private val _gains = MutableStateFlow<List<Float>>(emptyList())
    val gains: StateFlow<List<Float>> = _gains.asStateFlow()

    // 当前预设名
    private val _presetName = MutableStateFlow("Normal")
    val presetName: StateFlow<String> = _presetName.asStateFlow()

    // 内置预设列表
    private val _presets = MutableStateFlow<List<GraphicEQPreset>>(emptyList())
    val presets: StateFlow<List<GraphicEQPreset>> = _presets.asStateFlow()

    init {
        // 初始化：从当前 PEQ 状态同步增益
        syncGainsFromPEQ()
        refreshPresets()
    }

    /**
     * 切换启用/禁用
     */
    fun setEnabled(enabled: Boolean) {
        peqController.setEnabled(enabled)
    }

    /**
     * 设置前级增益
     */
    fun setPreamp(gainDB: Float) {
        peqController.setPreamp(gainDB)
    }

    /**
     * 切换段数
     */
    fun setBandCount(count: Int) {
        val target = count.coerceIn(PEQFilter.MIN_FILTERS, PEQFilter.MAX_FILTERS)
        if (target == bandCount.value) return

        peqController.setBandCount(target)
        syncGainsFromPEQ()
        refreshPresets()

        Log.d(TAG, "Band count changed to $target")
    }

    /**
     * 设置单段增益
     */
    fun setGain(index: Int, gainDB: Float) {
        val current = _gains.value.toMutableList()
        if (index < 0 || index >= current.size) return

        current[index] = gainDB.coerceIn(-12f, 12f)
        _gains.value = current

        // 转换为 PEQ 滤波器写入
        val freqs = PEQFilter.defaultFreqsForCount(bandCount.value)
        val q = PEQFilter.qForGraphicBand(bandCount.value)
        val filter = PEQFilter(
            type = FilterType.PEAK,
            frequency = freqs[index],
            gainDB = gainDB,
            Q = q,
            enabled = true
        )
        peqController.updateFilter(index, filter)
    }

    /**
     * 重置单段到 0dB
     */
    fun resetBand(index: Int) {
        setGain(index, 0f)
    }

    /**
     * 重置全部到平坦
     */
    fun resetToFlat() {
        val count = bandCount.value
        val freqs = PEQFilter.defaultFreqsForCount(count)
        val q = PEQFilter.qForGraphicBand(count)

        val filters = freqs.map { freq ->
            PEQFilter(
                type = FilterType.PEAK,
                frequency = freq,
                gainDB = 0f,
                Q = q,
                enabled = true
            )
        }
        peqController.importFilters(filters, "Normal")
        _gains.value = List(count) { 0f }
        _presetName.value = "Normal"

        Log.d(TAG, "Reset to flat, bandCount=$count")
    }

    /**
     * 应用预设
     */
    fun applyPreset(preset: GraphicEQPreset) {
        val targetCount = bandCount.value

        // 如果预设段数不同，先重采样
        val resampled = if (preset.bandCount != targetCount) {
            GraphicEQPreset.resamplePreset(preset, targetCount)
        } else {
            preset
        }

        val freqs = PEQFilter.defaultFreqsForCount(targetCount)
        val q = PEQFilter.qForGraphicBand(targetCount)

        val filters = resampled.gains.mapIndexed { i, gain ->
            PEQFilter(
                type = FilterType.PEAK,
                frequency = freqs[i],
                gainDB = gain,
                Q = q,
                enabled = true
            )
        }

        peqController.importFilters(filters, resampled.name)
        _gains.value = resampled.gains.toList()
        _presetName.value = resampled.name

        Log.d(TAG, "Applied preset: ${resampled.name}, bands=$targetCount")
    }

    /**
     * 从 PEQ 状态同步增益
     */
    private fun syncGainsFromPEQ() {
        val filters = peqController.filters.value
        val count = bandCount.value
        val newGains = MutableList(count) { 0f }

        for (i in 0 until minOf(count, filters.size)) {
            if (filters[i].enabled) {
                newGains[i] = filters[i].gainDB
            }
        }

        _gains.value = newGains
    }

    /**
     * 刷新预设列表
     */
    private fun refreshPresets() {
        _presets.value = GraphicEQPreset.builtInPresetsForCount(bandCount.value)
    }
}
