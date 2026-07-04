package com.rawsmusic.module.player.dsp

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.rawsmusic.module.data.prefs.AppPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 参量均衡器控制器
 * 管理PEQ状态并桥接NativeDSPEngine
 */
class ParametricEQController(private var nativeEngine: NativeDSPEngine) {

    companion object {
        private const val TAG = "PEQController"
        private const val CURVE_POINTS = 200 // 频率响应曲线采样点数
        private const val DEFAULT_SAMPLE_RATE = 48000
        private const val PERSIST_DEBOUNCE_MS = 350L
        private val gson = Gson()
    }

    private val persistHandler = Handler(Looper.getMainLooper())
    private val persistRunnable = Runnable { persistState() }

    // 采样率（用于 Kotlin 端曲线计算）
    private var sampleRate: Int = if (nativeEngine.isInitialized()) nativeEngine.sampleRate else DEFAULT_SAMPLE_RATE

    // 滤波器列表
    private val _filters = MutableStateFlow<List<PEQFilter>>(emptyList())
    val filters: StateFlow<List<PEQFilter>> = _filters.asStateFlow()

    // 当前 PEQ 段数：10-40
    private val _bandCount = MutableStateFlow(PEQFilter.MIN_FILTERS)
    val bandCount: StateFlow<Int> = _bandCount.asStateFlow()

    // 启用状态
    private val _isEnabled = MutableStateFlow(false)
    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    // 前置放大器增益 (dB)
    private val _preamp = MutableStateFlow(0f)
    val preamp: StateFlow<Float> = _preamp.asStateFlow()

    // 频率响应曲线数据
    private val _frequencyResponse = MutableStateFlow(FloatArray(0))
    val frequencyResponse: StateFlow<FloatArray> = _frequencyResponse.asStateFlow()

    // 频率点数组 (用于曲线绘制)
    private var frequencyPoints = FloatArray(CURVE_POINTS)

    init {
        // 初始化频率点 (对数分布: 20Hz - 20kHz)
        for (i in 0 until CURVE_POINTS) {
            val t = i.toFloat() / (CURVE_POINTS - 1)
            frequencyPoints[i] = 20f * Math.pow((20000.0 / 20.0), t.toDouble()).toFloat()
        }

        // 从持久化存储恢复状态；如果构造时已经拿到真实 native 引擎，立即同步一次
        loadPersistedState()
        if (nativeEngine.isInitialized()) {
            sampleRate = nativeEngine.sampleRate
            syncAllToNative()
            nativeEngine.setPEQEnabled(_isEnabled.value)
            updateFrequencyResponse()
            Log.d(TAG, "Initial sync to DSP engine, sampleRate=$sampleRate, bandCount=${_bandCount.value}, enabled=${_isEnabled.value}, preamp=${_preamp.value}dB")
        }
    }

    /**
     * 重新连接到新的 DSP 引擎实例
     */
    fun connectEngine(engine: NativeDSPEngine) {
        this.nativeEngine = engine
        if (engine.isInitialized()) {
            this.sampleRate = engine.sampleRate
        }
        syncAllToNative()
        if (engine.isInitialized()) {
            engine.setPEQEnabled(_isEnabled.value)
        }
        updateFrequencyResponse()
        Log.d(TAG, "Reconnected to DSP engine, sampleRate=$sampleRate, bandCount=${_bandCount.value}, enabled=${_isEnabled.value}, preamp=${_preamp.value}dB")
    }

    /**
     * 启用/禁用PEQ
     */
    fun setEnabled(enabled: Boolean) {
        _isEnabled.value = enabled
        if (nativeEngine.isInitialized()) {
            nativeEngine.setPEQEnabled(enabled)
        }
        persistImmediately()
        Log.d(TAG, "PEQ ${if (enabled) "enabled" else "disabled"}")
    }

    /**
     * 设置前置放大器增益
     * @param gainDB 增益值 (dB)，范围 -12 到 12
     */
    fun setPreamp(gainDB: Float) {
        val clamped = gainDB.coerceIn(-12f, 12f)
        _preamp.value = clamped
        if (nativeEngine.isInitialized()) {
            nativeEngine.setPreamp(clamped)
        }
        updateFrequencyResponse()
        persistDebounced()
        Log.d(TAG, "Preamp set to ${clamped}dB")
    }

    /**
     * 切换 PEQ 段数（10-40）
     * 智能重采样当前滤波器到新段数
     */
    fun setBandCount(newCount: Int) {
        val target = newCount.coerceIn(PEQFilter.MIN_FILTERS, PEQFilter.MAX_FILTERS)
        if (target == _bandCount.value) return

        val converted = convertParametricFilters(
            source = _filters.value,
            targetCount = target,
            preferOriginalOrder = false
        )

        _bandCount.value = target
        _filters.value = converted

        syncAllToNative()
        updateFrequencyResponse()
        persistImmediately()

        Log.d(TAG, "PEQ band count changed to $target")
    }

    /**
     * 更新指定索引的滤波器
     * 自动启用：调 gain 或特殊滤波器类型时自动 enabled = true
     */
    fun updateFilter(index: Int, filter: PEQFilter) {
        if (index < 0 || index >= _filters.value.size) return

        val safeFilter = filter.sanitized()
        val autoEnabled = if (
            safeFilter.type == FilterType.LOW_PASS ||
            safeFilter.type == FilterType.HIGH_PASS ||
            safeFilter.type == FilterType.BAND_PASS ||
            safeFilter.type == FilterType.NOTCH ||
            abs(safeFilter.gainDB) > 0.0001f
        ) {
            safeFilter.copy(enabled = true)
        } else {
            safeFilter
        }

        val currentFilters = _filters.value.toMutableList()
        currentFilters[index] = autoEnabled
        _filters.value = currentFilters

        syncToNative(index, autoEnabled)
        updateFrequencyResponse()
        persistDebounced()

        Log.d(TAG, "Updated filter[$index]: ${autoEnabled.displayType} @ ${autoEnabled.frequencyText}Hz")
    }

    /**
     * 切换滤波器启用状态
     */
    fun toggleFilter(index: Int) {
        if (index < 0 || index >= _filters.value.size) return

        val currentFilters = _filters.value.toMutableList()
        val filter = currentFilters[index]
        val updated = filter.copy(enabled = !filter.enabled)
        currentFilters[index] = updated
        _filters.value = currentFilters
        syncToNative(index, updated)
        updateFrequencyResponse()
        persistImmediately()
    }

    /**
     * 重置为默认配置（使用当前 bandCount）
     */
    fun resetToDefault() {
        val count = _bandCount.value.coerceIn(PEQFilter.MIN_FILTERS, PEQFilter.MAX_FILTERS)

        _filters.value = PEQFilter.createDefaultBands(count)
        _preamp.value = 0f

        if (nativeEngine.isInitialized()) {
            nativeEngine.clearPEQFilters()
            nativeEngine.setPreamp(0f)
        }

        syncAllToNative()
        updateFrequencyResponse()
        persistImmediately()

        Log.d(TAG, "PEQ reset to default, bandCount=$count")
    }

    /**
     * 获取频率响应曲线数据
     * @return Pair<频率数组, 增益数组>
     */
    fun getFrequencyResponseData(): Pair<FloatArray, FloatArray> {
        return Pair(frequencyPoints, _frequencyResponse.value)
    }

    // ==========================================
    // Native 同步
    // ==========================================

    /**
     * 同步单个滤波器到native（带参数安全化）
     */
    private fun syncToNative(index: Int, filter: PEQFilter) {
        if (!nativeEngine.isInitialized()) return
        if (index < 0 || index >= PEQFilter.MAX_FILTERS) return

        val safe = filter.sanitized()

        nativeEngine.setPEQFilter(
            index = index,
            type = safe.type.value,
            frequency = safe.frequency,
            gainDB = safe.gainDB,
            Q = safe.Q,
            enabled = safe.enabled
        )
    }

    /**
     * 同步所有滤波器到native
     * 先清空 40 个 slot，写入当前 bandCount 个 filter，剩余写 disabled
     */
    private fun syncAllToNative() {
        if (!nativeEngine.isInitialized()) return

        nativeEngine.clearPEQFilters()
        nativeEngine.setPreamp(_preamp.value.coerceIn(-12f, 12f))

        val target = _bandCount.value.coerceIn(PEQFilter.MIN_FILTERS, PEQFilter.MAX_FILTERS)
        val current = normalizeToBandCount(_filters.value, target)

        for (i in 0 until PEQFilter.MAX_FILTERS) {
            val filter = current.getOrNull(i)

            if (filter != null && i < target) {
                syncToNative(i, filter)
            } else {
                nativeEngine.setPEQFilter(
                    index = i,
                    type = FilterType.PEAK.value,
                    frequency = 1000f,
                    gainDB = 0f,
                    Q = 1.414f,
                    enabled = false
                )
            }
        }
    }

    // ==========================================
    // 频率响应计算
    // ==========================================

    /**
     * 更新频率响应曲线（使用 Kotlin 端 RBJ biquad 计算，不依赖 native 引擎）
     */
    private fun updateFrequencyResponse() {
        val magnitudes = calcFrequencyResponseKotlin()
        _frequencyResponse.value = magnitudes
    }

    /**
     * 纯 Kotlin 计算所有滤波器的总频率响应
     */
    private fun calcFrequencyResponseKotlin(): FloatArray {
        val magnitudes = FloatArray(CURVE_POINTS)
        val sr = sampleRate.toFloat()
        val preampGain = _preamp.value.coerceIn(-12f, 12f)
        val activeFilters = _filters.value.take(
            _bandCount.value.coerceIn(PEQFilter.MIN_FILTERS, PEQFilter.MAX_FILTERS)
        )

        for (i in 0 until CURVE_POINTS) {
            var totalMag = preampGain

            for (filter in activeFilters) {
                if (filter.enabled) {
                    totalMag += calcFilterMagnitude(filter.sanitized(), frequencyPoints[i], sr)
                }
            }

            magnitudes[i] = totalMag
        }

        return magnitudes
    }

    /**
     * 计算单个滤波器在指定频率的增益 (dB)
     */
    private fun calcFilterMagnitude(filter: PEQFilter, freq: Float, sampleRate: Float): Float {
        val coeffs = calcCoeffs(filter, sampleRate)
        val w = 2.0 * PI * freq / sampleRate

        val cosW = cos(w)
        val sinW = sin(w)
        val cos2W = cos(2.0 * w)
        val sin2W = sin(2.0 * w)

        val numRe = coeffs[0] + coeffs[1] * cosW + coeffs[2] * cos2W
        val numIm = -(coeffs[1] * sinW + coeffs[2] * sin2W)

        val denRe = 1.0 + coeffs[3] * cosW + coeffs[4] * cos2W
        val denIm = -(coeffs[3] * sinW + coeffs[4] * sin2W)

        val numMag = sqrt(numRe * numRe + numIm * numIm)
        val denMag = sqrt(denRe * denRe + denIm * denIm)

        if (denMag < 1e-30) return 0.0f
        return (20.0 * log10(numMag / denMag)).toFloat()
    }

    /**
     * 计算 RBJ 标准 BiQuad 滤波器系数
     */
    private fun calcCoeffs(filter: PEQFilter, sampleRate: Float): DoubleArray {
        val A = 10.0.pow(filter.gainDB / 40.0)
        var w0 = 2.0 * PI * filter.frequency / sampleRate
        w0 = w0.coerceIn(0.00000001, 3.0013)
        val sinW0 = sin(w0)
        val cosW0 = cos(w0)
        val alpha = sinW0 / (2.0 * filter.Q.coerceAtLeast(0.00000001f))

        return when (filter.type) {
            FilterType.PEAK, FilterType.PEAK_ANALOG -> {
                val b0 = 1.0 + alpha * A
                val b1 = -2.0 * cosW0
                val b2 = 1.0 - alpha * A
                val a0 = 1.0 + alpha / A
                val a1 = -2.0 * cosW0
                val a2 = 1.0 - alpha / A
                doubleArrayOf(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
            }
            FilterType.LOW_SHELF -> {
                val sqrtA = sqrt(A)
                val b0 = A * ((A + 1.0) - (A - 1.0) * cosW0 + 2.0 * sqrtA * alpha)
                val b1 = 2.0 * A * ((A - 1.0) - (A + 1.0) * cosW0)
                val b2 = A * ((A + 1.0) - (A - 1.0) * cosW0 - 2.0 * sqrtA * alpha)
                val a0 = (A + 1.0) + (A - 1.0) * cosW0 + 2.0 * sqrtA * alpha
                val a1 = -2.0 * ((A - 1.0) + (A + 1.0) * cosW0)
                val a2 = (A + 1.0) + (A - 1.0) * cosW0 - 2.0 * sqrtA * alpha
                doubleArrayOf(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
            }
            FilterType.HIGH_SHELF -> {
                val sqrtA = sqrt(A)
                val b0 = A * ((A + 1.0) + (A - 1.0) * cosW0 + 2.0 * sqrtA * alpha)
                val b1 = -2.0 * A * ((A - 1.0) + (A + 1.0) * cosW0)
                val b2 = A * ((A + 1.0) + (A - 1.0) * cosW0 - 2.0 * sqrtA * alpha)
                val a0 = (A + 1.0) - (A - 1.0) * cosW0 + 2.0 * sqrtA * alpha
                val a1 = 2.0 * ((A - 1.0) - (A + 1.0) * cosW0)
                val a2 = (A + 1.0) - (A - 1.0) * cosW0 - 2.0 * sqrtA * alpha
                doubleArrayOf(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
            }
            FilterType.LOW_PASS -> {
                val b0 = (1.0 - cosW0) / 2.0
                val b1 = 1.0 - cosW0
                val b2 = (1.0 - cosW0) / 2.0
                val a0 = 1.0 + alpha
                val a1 = -2.0 * cosW0
                val a2 = 1.0 - alpha
                doubleArrayOf(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
            }
            FilterType.HIGH_PASS -> {
                val b0 = (1.0 + cosW0) / 2.0
                val b1 = -(1.0 + cosW0)
                val b2 = (1.0 + cosW0) / 2.0
                val a0 = 1.0 + alpha
                val a1 = -2.0 * cosW0
                val a2 = 1.0 - alpha
                doubleArrayOf(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
            }
            FilterType.BAND_PASS -> {
                val A_bp = 10.0.pow(filter.gainDB / 40.0)
                val b0 = alpha * A_bp
                val b1 = 0.0
                val b2 = -alpha * A_bp
                val a0 = 1.0 + alpha
                val a1 = -2.0 * cosW0
                val a2 = 1.0 - alpha
                doubleArrayOf(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
            }
            FilterType.NOTCH -> {
                val A_notch = 10.0.pow(filter.gainDB / 40.0)
                val b0 = A_notch
                val b1 = -2.0 * cosW0 * A_notch
                val b2 = A_notch
                val a0 = 1.0 + alpha
                val a1 = -2.0 * cosW0
                val a2 = 1.0 - alpha
                doubleArrayOf(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
            }
        }
    }

    // ==========================================
    // 智能重采样 / 转换工具
    // ==========================================

    /**
     * 将滤波器列表归一化到目标段数
     * 不足时用默认 disabled 段填充
     */
    private fun normalizeToBandCount(
        filters: List<PEQFilter>,
        targetCount: Int
    ): List<PEQFilter> {
        val target = targetCount.coerceIn(PEQFilter.MIN_FILTERS, PEQFilter.MAX_FILTERS)

        val sanitized = filters
            .map { it.sanitized() }
            .take(target)

        if (sanitized.size == target) return sanitized

        val defaults = PEQFilter.createDefaultBands(target).toMutableList()

        sanitized.forEachIndexed { index, filter ->
            defaults[index] = filter
        }

        return defaults.sortedBy { it.frequency }
    }

    /**
     * 智能转换滤波器列表到目标段数
     * 源 < 目标：保留所有源滤波器，用默认 disabled 填充
     * 源 > 目标：按重要度排序，保留最重要的 N 个
     */
    private fun convertParametricFilters(
        source: List<PEQFilter>,
        targetCount: Int,
        preferOriginalOrder: Boolean
    ): List<PEQFilter> {
        val target = targetCount.coerceIn(PEQFilter.MIN_FILTERS, PEQFilter.MAX_FILTERS)

        val cleaned = source
            .map { it.sanitized() }
            .filter { it.frequency in 20f..20000f }

        val selected = if (cleaned.size <= target) {
            cleaned
        } else {
            if (preferOriginalOrder) {
                cleaned.take(target)
            } else {
                cleaned
                    .sortedByDescending { filterImportance(it) }
                    .take(target)
                    .sortedBy { it.frequency }
            }
        }

        if (selected.size == target) return selected

        // 不足时用默认 disabled 段填充
        val result = PEQFilter.createDefaultBands(target).toMutableList()

        selected.forEachIndexed { index, filter ->
            result[index] = filter
        }

        return result.sortedBy { it.frequency }
    }

    /**
     * 滤波器重要度评分
     * 用于从多段预设中选出最重要的 N 个
     */
    private fun filterImportance(filter: PEQFilter): Float {
        if (!filter.enabled) return 0f

        val gainScore = abs(filter.gainDB)
        val qScore = log10(filter.Q.coerceAtLeast(0.1f)) * 1.5f

        val typeBonus = when (filter.type) {
            FilterType.NOTCH -> 4f
            FilterType.LOW_SHELF,
            FilterType.HIGH_SHELF -> 2f
            else -> 0f
        }

        return gainScore + qScore + typeBonus
    }

    // ==========================================
    // 持久化
    // ==========================================

    /**
     * 从持久化存储加载PEQ状态
     */
    private fun loadPersistedState() {
        try {
            val savedBandCount = AppPreferences.PEQ.bandCount
                .coerceIn(PEQFilter.MIN_FILTERS, PEQFilter.MAX_FILTERS)

            _bandCount.value = savedBandCount

            val json = AppPreferences.PEQ.filtersJson

            val loadedFilters = if (json.isNotBlank()) {
                val type = object : TypeToken<List<PEQFilter>>() {}.type
                gson.fromJson<List<PEQFilter>>(json, type).orEmpty()
            } else {
                emptyList()
            }

            _filters.value = if (loadedFilters.isNotEmpty()) {
                normalizeToBandCount(loadedFilters, savedBandCount)
            } else {
                PEQFilter.createDefaultBands(savedBandCount)
            }

            _isEnabled.value = AppPreferences.PEQ.isEnabled
            _preamp.value = AppPreferences.PEQ.preamp.coerceIn(-12f, 12f)

            syncAllToNative()

            if (nativeEngine.isInitialized()) {
                nativeEngine.setPEQEnabled(_isEnabled.value)
                nativeEngine.setPreamp(_preamp.value)
            }

            updateFrequencyResponse()

            Log.d(TAG, "Loaded PEQ state: bandCount=$savedBandCount, filters=${_filters.value.size}, enabled=${_isEnabled.value}, preamp=${_preamp.value}dB")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load PEQ state, using defaults", e)

            _bandCount.value = PEQFilter.MIN_FILTERS
            _filters.value = PEQFilter.createDefaultBands(PEQFilter.MIN_FILTERS)
            _isEnabled.value = false
            _preamp.value = 0f

            syncAllToNative()
            updateFrequencyResponse()
        }
    }

    /**
     * 将当前PEQ状态持久化到存储
     */
    private fun persistState() {
        try {
            AppPreferences.PEQ.isEnabled = _isEnabled.value
            AppPreferences.PEQ.preamp = _preamp.value
            AppPreferences.PEQ.bandCount = _bandCount.value
            AppPreferences.PEQ.filtersJson = gson.toJson(_filters.value)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist PEQ state", e)
        }
    }

    private fun persistDebounced() {
        persistHandler.removeCallbacks(persistRunnable)
        persistHandler.postDelayed(persistRunnable, PERSIST_DEBOUNCE_MS)
    }

    private fun persistImmediately() {
        persistHandler.removeCallbacks(persistRunnable)
        persistState()
    }

    /**
     * 从 AutoEq 预设导入滤波器配置
     * 智能转换到当前 bandCount
     */
    fun importFromAutoEq(preset: AutoEqPreset) {
        val peqFilters = preset.toPEQFilters()
        if (peqFilters.isEmpty()) {
            Log.w(TAG, "AutoEq preset has no filters: ${preset.name}")
            return
        }

        val target = _bandCount.value.coerceIn(PEQFilter.MIN_FILTERS, PEQFilter.MAX_FILTERS)

        val converted = convertParametricFilters(
            source = peqFilters,
            targetCount = target,
            preferOriginalOrder = true
        )

        _filters.value = converted
        _preamp.value = preset.safePreamp

        syncAllToNative()
        updateFrequencyResponse()

        AppPreferences.PEQ.presetName = preset.name
        persistImmediately()

        Log.d(TAG, "Imported AutoEq preset: ${preset.name}, source=${peqFilters.size}, target=$target")
    }

    /**
     * 导入滤波器列表
     * 智能转换到当前 bandCount
     */
    fun importFilters(filters: List<PEQFilter>, presetName: String? = null) {
        if (filters.isEmpty()) {
            Log.w(TAG, "importFilters: empty filter list")
            return
        }

        val target = _bandCount.value.coerceIn(PEQFilter.MIN_FILTERS, PEQFilter.MAX_FILTERS)

        val converted = convertParametricFilters(
            source = filters,
            targetCount = target,
            preferOriginalOrder = false
        )

        _filters.value = converted

        if (!presetName.isNullOrBlank()) {
            AppPreferences.PEQ.presetName = presetName
        }

        syncAllToNative()
        updateFrequencyResponse()
        persistImmediately()

        Log.d(TAG, "Imported PEQ filters: source=${filters.size}, target=$target")
    }
}
