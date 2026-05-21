package com.rawsmusic.module.player.dsp

import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.rawsmusic.module.data.prefs.AppPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.PI
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
        private val gson = Gson()
    }

    // 采样率（用于 Kotlin 端曲线计算）
    private var sampleRate: Int = if (nativeEngine.isInitialized()) nativeEngine.sampleRate else DEFAULT_SAMPLE_RATE

    // 滤波器列表
    private val _filters = MutableStateFlow<List<PEQFilter>>(emptyList())
    val filters: StateFlow<List<PEQFilter>> = _filters.asStateFlow()

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
            frequencyPoints[i] = 20f * Math.pow((20000.0 / 20.0).toDouble(), t.toDouble()).toFloat()
        }

        // 从持久化存储恢复状态
        loadPersistedState()
    }

    /**
     * 重新连接到新的 DSP 引擎实例
     * 当播放器重新初始化 DSP 引擎后调用
     * 注意：initDspEngine() 会对同一个 Kotlin 对象 release+init，
     *       导致底层 native handle 变化但 Kotlin 引用不变，
     *       因此不能用 === 判断是否需要重新同步，必须始终同步。
     */
    fun connectEngine(engine: NativeDSPEngine) {
        this.nativeEngine = engine
        if (engine.isInitialized()) {
            this.sampleRate = engine.sampleRate
        }
        // 将当前所有滤波器同步到新引擎（native handle 可能已变化）
        syncAllToNative()
        // 同步启用状态
        if (engine.isInitialized()) {
            engine.setPEQEnabled(_isEnabled.value)
        }
        // 重新计算频率响应
        updateFrequencyResponse()
        Log.d(TAG, "Reconnected to DSP engine, sampleRate=$sampleRate, enabled=${_isEnabled.value}, preamp=${_preamp.value}dB")
    }

    /**
     * 启用/禁用PEQ
     */
    fun setEnabled(enabled: Boolean) {
        _isEnabled.value = enabled
        if (nativeEngine.isInitialized()) {
            nativeEngine.setPEQEnabled(enabled)
        }
        persistState()
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
        persistState()
        Log.d(TAG, "Preamp set to ${clamped}dB")
    }

    /**
     * 更新指定索引的滤波器
     */
    fun updateFilter(index: Int, filter: PEQFilter) {
        if (index < 0 || index >= _filters.value.size) return

        val currentFilters = _filters.value.toMutableList()
        currentFilters[index] = filter
        _filters.value = currentFilters
        syncToNative(index, filter)
        updateFrequencyResponse()
        persistState()
        Log.d(TAG, "Updated filter[$index]: ${filter.displayType} @ ${filter.frequencyText}Hz")
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
        persistState()
    }

    /**
     * 重置为默认 10 段倍频程配置
     */
    fun resetToDefault() {
        _filters.value = PEQFilter.createDefaultBands()
        syncAllToNative()
        updateFrequencyResponse()
        persistState()
        Log.d(TAG, "Reset to default 10-band octave config")
    }

    /**
     * 获取频率响应曲线数据
     * @return Pair<频率数组, 增益数组>
     */
    fun getFrequencyResponseData(): Pair<FloatArray, FloatArray> {
        return Pair(frequencyPoints, _frequencyResponse.value)
    }

    /**
     * 同步单个滤波器到native
     */
    private fun syncToNative(index: Int, filter: PEQFilter) {
        if (!nativeEngine.isInitialized()) return
        nativeEngine.setPEQFilter(
            index = index,
            type = filter.type.value,
            frequency = filter.frequency,
            gainDB = filter.gainDB,
            Q = filter.Q,
            enabled = filter.enabled
        )
    }

    /**
     * 同步所有滤波器到native
     */
    private fun syncAllToNative() {
        if (!nativeEngine.isInitialized()) return
        nativeEngine.clearPEQFilters()
        _filters.value.forEachIndexed { index, filter ->
            syncToNative(index, filter)
        }
        // 同步前置放大器
        nativeEngine.setPreamp(_preamp.value)
    }

    /**
     * 更新频率响应曲线（使用 Kotlin 端 RBJ biquad 计算，不依赖 native 引擎）
     */
    private fun updateFrequencyResponse() {
        val magnitudes = calcFrequencyResponseKotlin()
        _frequencyResponse.value = magnitudes
    }

    // ==========================================
    // Kotlin 端 BiQuad 频率响应计算（RBJ Cookbook）
    // ==========================================

    /**
     * 纯 Kotlin 计算所有滤波器的总频率响应
     * 用于曲线可视化，不依赖 native 引擎
     */
    private fun calcFrequencyResponseKotlin(): FloatArray {
        val magnitudes = FloatArray(CURVE_POINTS)
        val sr = sampleRate.toFloat()
        val preampGain = _preamp.value

        for (i in 0 until CURVE_POINTS) {
            var totalMag = preampGain // 添加前置放大器增益
            for (filter in _filters.value) {
                if (filter.enabled) {
                    totalMag += calcFilterMagnitude(filter, frequencyPoints[i], sr)
                }
            }
            magnitudes[i] = totalMag
        }
        return magnitudes
    }

    /**
     * 计算单个滤波器在指定频率的增益 (dB)
     * 使用复数向量模长法: |H(e^jw)| = |num| / |den|
     */
    private fun calcFilterMagnitude(filter: PEQFilter, freq: Float, sampleRate: Float): Float {
        val coeffs = calcCoeffs(filter, sampleRate)
        val w = 2.0 * PI * freq / sampleRate

        val cosW = cos(w)
        val sinW = sin(w)
        val cos2W = cos(2.0 * w)
        val sin2W = sin(2.0 * w)

        // 分子: b0 + b1*e^-jw + b2*e^-2jw
        val numRe = coeffs[0] + coeffs[1] * cosW + coeffs[2] * cos2W
        val numIm = -(coeffs[1] * sinW + coeffs[2] * sin2W)

        // 分母: 1 + a1*e^-jw + a2*e^-2jw
        val denRe = 1.0 + coeffs[3] * cosW + coeffs[4] * cos2W
        val denIm = -(coeffs[3] * sinW + coeffs[4] * sin2W)

        val numMag = sqrt(numRe * numRe + numIm * numIm)
        val denMag = sqrt(denRe * denRe + denIm * denIm)

        if (denMag < 1e-30) return 0.0f
        return (20.0 * log10(numMag / denMag)).toFloat()
    }

    /**
     * 计算 RBJ 标准 BiQuad 滤波器系数
     * @return [b0, b1, b2, a1, a2] (a0 已归一化为 1)
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
                // 与 C++ 端 setBP 对齐：分子系数乘以增益 A = 10^(gainDB/40)
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
                // 与 C++ 端 setNotch 对齐：分子系数乘以增益 A = 10^(gainDB/40)
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

    /**
     * 从持久化存储加载PEQ状态
     * 如果没有保存的状态，初始化为默认 10 段倍频程配置
     */
    private fun loadPersistedState() {
        try {
            // 恢复滤波器列表
            val json = AppPreferences.PEQ.filtersJson
            if (json.isNotBlank()) {
                val type = object : TypeToken<List<PEQFilter>>() {}.type
                val savedFilters: List<PEQFilter> = gson.fromJson(json, type)
                if (savedFilters.isNotEmpty()) {
                    _filters.value = savedFilters
                    syncAllToNative()
                    Log.d(TAG, "Restored ${savedFilters.size} filters from preferences")
                } else {
                    // 保存的数据为空，加载默认配置
                    _filters.value = PEQFilter.createDefaultBands()
                    syncAllToNative()
                    Log.d(TAG, "Loaded default 10-band config (saved list was empty)")
                }
            } else {
                // 首次使用，加载默认 10 段配置
                _filters.value = PEQFilter.createDefaultBands()
                syncAllToNative()
                Log.d(TAG, "First run: loaded default 10-band octave config")
            }

            // 恢复启用状态（必须在滤波器之后设置）
            val savedEnabled = AppPreferences.PEQ.isEnabled
            _isEnabled.value = savedEnabled
            if (nativeEngine.isInitialized()) {
                nativeEngine.setPEQEnabled(savedEnabled)
            }
            Log.d(TAG, "Restored PEQ enabled=$savedEnabled from preferences")

            // 恢复前置放大器增益
            val savedPreamp = AppPreferences.PEQ.preamp
            _preamp.value = savedPreamp.coerceIn(-12f, 12f)
            if (nativeEngine.isInitialized()) {
                nativeEngine.setPreamp(_preamp.value)
            }
            Log.d(TAG, "Restored PEQ preamp=${_preamp.value}dB from preferences")

            // 计算频率响应（使用 Kotlin 计算，始终可用）
            updateFrequencyResponse()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load PEQ state, using defaults", e)
            // 异常时也加载默认配置
            _filters.value = PEQFilter.createDefaultBands()
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
            val json = gson.toJson(_filters.value)
            AppPreferences.PEQ.filtersJson = json
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist PEQ state", e)
        }
    }

    /**
     * 从 AutoEq 预设导入滤波器配置
     * @param preset AutoEq 预设
     */
    fun importFromAutoEq(preset: AutoEqPreset) {
        val peqFilters = preset.toPEQFilters()
        if (peqFilters.isEmpty()) {
            Log.w(TAG, "AutoEq preset has no filters: ${preset.name}")
            return
        }

        val maxFilters = PEQFilter.MAX_FILTERS
        val filtersToImport = if (peqFilters.size > maxFilters) {
            Log.w(TAG, "AutoEq preset has ${peqFilters.size} filters, truncating to $maxFilters")
            peqFilters.take(maxFilters)
        } else {
            peqFilters
        }

        _filters.value = filtersToImport
        syncAllToNative()
        updateFrequencyResponse()
        AppPreferences.PEQ.presetName = preset.name
        persistState()

        Log.d(TAG, "Imported AutoEq preset: ${preset.name} (${filtersToImport.size} filters)")
    }

    fun importFilters(filters: List<PEQFilter>, presetName: String? = null) {
        if (filters.isEmpty()) {
            Log.w(TAG, "importFilters: empty filter list")
            return
        }

        val maxFilters = PEQFilter.MAX_FILTERS
        val filtersToImport = if (filters.size > maxFilters) {
            Log.w(TAG, "importFilters: ${filters.size} filters, truncating to $maxFilters")
            filters.take(maxFilters)
        } else {
            filters
        }

        _filters.value = filtersToImport
        syncAllToNative()
        updateFrequencyResponse()
        if (presetName != null) {
            AppPreferences.PEQ.presetName = presetName
        }
        persistState()

        Log.d(TAG, "Imported ${filtersToImport.size} filters")
    }
}
