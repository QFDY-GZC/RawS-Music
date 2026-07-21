package com.rawsmusic.module.player.dsp

import android.util.Log

class NativeDSPEngine {
    private var nativeHandle: Long = 0
    var sampleRate: Int = 44100
        private set

    companion object {
        private const val TAG = "NativeDSPEngine"
        private const val MAX_PEQ_FILTERS = 40
        init {
            try {
                System.loadLibrary("rawsmusic_dsp")
                Log.d(TAG, "rawsmusic_dsp library loaded")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load rawsmusic_dsp", e)
            }
        }
    }

    fun init(sampleRate: Int, channels: Int) {
        if (nativeHandle != 0L) release()
        this.sampleRate = sampleRate
        nativeHandle = nativeCreate(sampleRate, channels)
        Log.d(TAG, "init: sampleRate=$sampleRate, channels=$channels, handle=$nativeHandle")
    }

    fun setStereoWiden(factor: Float) {
        if (nativeHandle == 0L) return

        val safeFactor = if (factor.isFinite()) {
            factor.coerceIn(0f, 1f)
        } else {
            0f
        }

        nativeSetStereoWiden(nativeHandle, safeFactor)
    }

    /** RawSMusic-owned binaural renderer for regular Android stereo output. */
    fun setAndroidBinauralSpatialEnabled(enabled: Boolean) {
        if (nativeHandle == 0L) return
        nativeSetAndroidBinauralSpatialEnabled(nativeHandle, enabled)
    }

    fun setAndroidBinauralSpatialParameters(intensityPercent: Float, roomPercent: Float) {
        if (nativeHandle == 0L) return
        nativeSetAndroidBinauralSpatialParameters(
            nativeHandle,
            intensityPercent.coerceIn(0f, 100f),
            roomPercent.coerceIn(0f, 100f)
        )
    }


    fun setAndroidBinauralSpatialAdvancedParameters(
        brirEnabled: Boolean,
        separationPercent: Float,
        headSizeCentimeters: Float,
        pinnaDetailPercent: Float
    ) {
        if (nativeHandle == 0L) return
        nativeSetAndroidBinauralSpatialAdvancedParameters(
            nativeHandle,
            brirEnabled,
            separationPercent.coerceIn(0f, 100f),
            headSizeCentimeters.coerceIn(48f, 68f),
            pinnaDetailPercent.coerceIn(0f, 100f)
        )
    }

    fun setAndroidBinauralHeadPose(
        enabled: Boolean,
        quaternionX: Float,
        quaternionY: Float,
        quaternionZ: Float,
        quaternionW: Float
    ) {
        if (nativeHandle == 0L) return
        nativeSetAndroidBinauralHeadPose(
            nativeHandle,
            enabled,
            quaternionX,
            quaternionY,
            quaternionZ,
            quaternionW
        )
    }

    fun process(buffer: ShortArray, length: Int, channels: Int): Int {
        if (nativeHandle == 0L) return -1
        return nativeProcess(nativeHandle, buffer, length, channels)
    }

    fun processFloat(buffer: FloatArray, length: Int, channels: Int): Int {
        if (nativeHandle == 0L) return -1
        return nativeProcessFloat(nativeHandle, buffer, length, channels)
    }

    fun hasActiveEffects(): Boolean {
        if (nativeHandle == 0L) return false
        return nativeHasActiveEffects(nativeHandle)
    }

    // ==========================================
    // 参量均衡器 API
    // ==========================================

    /** 启用/禁用参量均衡器 */
    fun setPEQEnabled(enabled: Boolean) {
        if (nativeHandle == 0L) return
        nativeSetPEQEnabled(nativeHandle, enabled)
    }

    /** Enable the independent graphic-EQ bank. Native enforces PEQ/GEQ exclusivity. */
    fun setGraphicEQEnabled(enabled: Boolean) {
        if (nativeHandle == 0L) return
        nativeSetGraphicEQEnabled(nativeHandle, enabled)
    }

    fun setGraphicEQFilter(
        index: Int,
        frequency: Float,
        gainDB: Float,
        Q: Float,
        enabled: Boolean
    ) {
        if (nativeHandle == 0L || index !in 0 until MAX_PEQ_FILTERS) return
        nativeSetGraphicEQFilter(
            nativeHandle,
            index,
            frequency.coerceIn(20f, 20000f),
            gainDB.coerceIn(-12f, 12f),
            Q.coerceIn(0.05f, 24f),
            enabled
        )
    }

    fun clearGraphicEQFilters() {
        if (nativeHandle == 0L) return
        nativeClearGraphicEQFilters(nativeHandle)
    }

    fun setGraphicEQPreamp(gainDB: Float) {
        if (nativeHandle == 0L) return
        nativeSetGraphicEQPreamp(nativeHandle, gainDB.coerceIn(-12f, 12f))
    }

    fun setExperimentalGainEnabled(enabled: Boolean) {
        if (nativeHandle == 0L) return
        nativeSetExperimentalGainEnabled(nativeHandle, enabled)
    }

    fun setExperimentalGainDb(gainDb: Float) {
        if (nativeHandle == 0L) return
        nativeSetExperimentalGainDb(nativeHandle, gainDb.coerceIn(0f, 30f))
    }

    fun setLoudnessBalanceEnabled(enabled: Boolean) {
        if (nativeHandle != 0L) nativeSetLoudnessBalanceEnabled(nativeHandle, enabled)
    }

    fun setLoudnessBalanceParameters(loudnessPercent: Float, balance: Float) {
        if (nativeHandle != 0L) {
            nativeSetLoudnessBalanceParameters(
                nativeHandle,
                loudnessPercent.coerceIn(0f, 100f),
                balance.coerceIn(-1f, 1f)
            )
        }
    }

    fun setMonoBassEnabled(enabled: Boolean) {
        if (nativeHandle != 0L) nativeSetMonoBassEnabled(nativeHandle, enabled)
    }

    fun setMonoBassParameters(crossoverHz: Float, amountPercent: Float) {
        if (nativeHandle != 0L) {
            nativeSetMonoBassParameters(
                nativeHandle,
                crossoverHz.coerceIn(60f, 300f),
                amountPercent.coerceIn(0f, 100f)
            )
        }
    }

    fun setDynamicEqEnabled(enabled: Boolean) {
        if (nativeHandle != 0L) nativeSetDynamicEqEnabled(nativeHandle, enabled)
    }

    fun setDynamicEqParameters(
        intensityPercent: Float,
        deEsserPercent: Float,
        deEsserFrequencyHz: Float
    ) {
        if (nativeHandle != 0L) {
            nativeSetDynamicEqParameters(
                nativeHandle,
                intensityPercent.coerceIn(0f, 100f),
                deEsserPercent.coerceIn(0f, 100f),
                deEsserFrequencyHz.coerceIn(4000f, 10000f)
            )
        }
    }

    fun setMoogLadderEnabled(enabled: Boolean) {
        if (nativeHandle != 0L) nativeSetMoogLadderEnabled(nativeHandle, enabled)
    }

    fun setMoogLadderParameters(
        mode: Int,
        cutoffHz: Float,
        resonancePercent: Float,
        driveDb: Float,
        mixPercent: Float
    ) {
        if (nativeHandle != 0L) {
            nativeSetMoogLadderParameters(
                nativeHandle,
                mode.coerceIn(0, 4),
                cutoffHz.coerceIn(20f, 20000f),
                resonancePercent.coerceIn(0f, 100f),
                driveDb.coerceIn(0f, 18f),
                mixPercent.coerceIn(0f, 100f)
            )
        }
    }

    /**
     * 设置PEQ滤波器
     * @param index 滤波器索引 (0-39)
     * @param type 滤波器类型 (0=Peak, 1=LowShelf, 2=HighShelf, 3=LowPass, 4=HighPass, 5=BandPass, 6=Notch, 7=PeakAnalog)
     * @param frequency 中心/截止频率 (Hz)
     * @param gainDB 增益 (dB)，建议 -24..24
     * @param Q 品质因数，建议 0.05..24
     * @param enabled 是否启用
     */
    fun setPEQFilter(
        index: Int,
        type: Int,
        frequency: Float,
        gainDB: Float,
        Q: Float,
        enabled: Boolean
    ) {
        if (nativeHandle == 0L) return
        if (index !in 0 until MAX_PEQ_FILTERS) return

        val safeType = type.coerceIn(0, 7)

        val safeFrequency = if (frequency.isFinite()) {
            frequency.coerceIn(20f, 20000f)
        } else {
            1000f
        }

        val safeGainDB = if (gainDB.isFinite()) {
            gainDB.coerceIn(-24f, 24f)
        } else {
            0f
        }

        val safeQ = if (Q.isFinite()) {
            Q.coerceIn(0.05f, 24f)
        } else {
            1.414f
        }

        nativeSetPEQFilter(
            nativeHandle,
            index,
            safeType,
            safeFrequency,
            safeGainDB,
            safeQ,
            enabled
        )
    }

    /** 移除指定索引的滤波器 */
    fun removePEQFilter(index: Int) {
        if (nativeHandle == 0L) return
        if (index !in 0 until MAX_PEQ_FILTERS) return

        nativeRemovePEQFilter(nativeHandle, index)
    }

    /** 清除所有滤波器 */
    fun clearPEQFilters() {
        if (nativeHandle == 0L) return
        nativeClearPEQFilters(nativeHandle)
    }

    /** 设置实际 DSP 前级增益。负向扩展范围用于 PEQ 自动余量补偿。 */
    fun setPreamp(gainDB: Float) {
        if (nativeHandle == 0L) return

        val safeGain = if (gainDB.isFinite()) {
            gainDB.coerceIn(-96f, 12f)
        } else {
            0f
        }

        nativeSetPreamp(nativeHandle, safeGain)
    }

    // ==========================================
    // 互馈 (Crossfeed) API
    // ==========================================

    /** 启用/禁用互馈 */
    fun setCrossfeedEnabled(enabled: Boolean) {
        if (nativeHandle == 0L) return
        nativeSetCrossfeedEnabled(nativeHandle, enabled)
    }

    /**
     * 设置互馈参数
     * @param lowCutFreq 高通截止频率 (Hz)，50-1000
     * @param highCutFreq 低通截止频率 (Hz)，500-8000
     * @param attenuationDB 衰减量 (dB)，0-15
     */
    fun setCrossfeedParams(lowCutFreq: Float, highCutFreq: Float, attenuationDB: Float) {
        if (nativeHandle == 0L) return
        nativeSetCrossfeedParams(nativeHandle, lowCutFreq, highCutFreq, attenuationDB)
    }

    /**
     * 计算频率响应曲线
     * @param frequencies 输入频率数组 (Hz)
     * @param magnitudes 输出增益数组 (dB)
     * @param numPoints 采样点数
     */
    fun calcPEQResponse(frequencies: FloatArray, magnitudes: FloatArray, numPoints: Int) {
        if (nativeHandle == 0L) return

        val safePoints = numPoints
            .coerceAtMost(frequencies.size)
            .coerceAtMost(magnitudes.size)
            .coerceAtLeast(0)

        if (safePoints <= 0) return

        nativeCalcPEQResponse(nativeHandle, frequencies, magnitudes, safePoints)
    }

    fun release() {
        if (nativeHandle != 0L) {
            nativeRelease(nativeHandle)
            nativeHandle = 0
        }
    }

    fun isInitialized(): Boolean = nativeHandle != 0L

    internal fun nativeHandleForBridge(): Long = nativeHandle

    private external fun nativeCreate(sampleRate: Int, channels: Int): Long
    private external fun nativeSetStereoWiden(handle: Long, factor: Float)
    private external fun nativeSetAndroidBinauralSpatialEnabled(handle: Long, enabled: Boolean)
    private external fun nativeSetAndroidBinauralSpatialParameters(
        handle: Long,
        intensityPercent: Float,
        roomPercent: Float
    )

    private external fun nativeSetAndroidBinauralSpatialAdvancedParameters(
        handle: Long,
        brirEnabled: Boolean,
        separationPercent: Float,
        headSizeCentimeters: Float,
        pinnaDetailPercent: Float
    )
    private external fun nativeSetAndroidBinauralHeadPose(
        handle: Long,
        enabled: Boolean,
        quaternionX: Float,
        quaternionY: Float,
        quaternionZ: Float,
        quaternionW: Float
    )
    private external fun nativeProcess(handle: Long, buffer: ShortArray, length: Int, channels: Int): Int
    private external fun nativeProcessFloat(handle: Long, buffer: FloatArray, length: Int, channels: Int): Int
    private external fun nativeHasActiveEffects(handle: Long): Boolean
    private external fun nativeRelease(handle: Long)

    // PEQ JNI 方法
    private external fun nativeSetPEQEnabled(handle: Long, enabled: Boolean)
    private external fun nativeSetGraphicEQEnabled(handle: Long, enabled: Boolean)
    private external fun nativeSetGraphicEQFilter(
        handle: Long,
        index: Int,
        frequency: Float,
        gainDB: Float,
        Q: Float,
        enabled: Boolean
    )
    private external fun nativeClearGraphicEQFilters(handle: Long)
    private external fun nativeSetGraphicEQPreamp(handle: Long, gainDB: Float)
    private external fun nativeSetExperimentalGainEnabled(handle: Long, enabled: Boolean)
    private external fun nativeSetExperimentalGainDb(handle: Long, gainDb: Float)
    private external fun nativeSetLoudnessBalanceEnabled(handle: Long, enabled: Boolean)
    private external fun nativeSetLoudnessBalanceParameters(handle: Long, loudnessPercent: Float, balance: Float)
    private external fun nativeSetMonoBassEnabled(handle: Long, enabled: Boolean)
    private external fun nativeSetMonoBassParameters(handle: Long, crossoverHz: Float, amountPercent: Float)
    private external fun nativeSetDynamicEqEnabled(handle: Long, enabled: Boolean)
    private external fun nativeSetDynamicEqParameters(
        handle: Long,
        intensityPercent: Float,
        deEsserPercent: Float,
        deEsserFrequencyHz: Float
    )
    private external fun nativeSetMoogLadderEnabled(handle: Long, enabled: Boolean)
    private external fun nativeSetMoogLadderParameters(
        handle: Long,
        mode: Int,
        cutoffHz: Float,
        resonancePercent: Float,
        driveDb: Float,
        mixPercent: Float
    )
    private external fun nativeSetPEQFilter(handle: Long, index: Int, type: Int, frequency: Float, gainDB: Float, Q: Float, enabled: Boolean)
    private external fun nativeRemovePEQFilter(handle: Long, index: Int)
    private external fun nativeClearPEQFilters(handle: Long)
    private external fun nativeCalcPEQResponse(handle: Long, frequencies: FloatArray, magnitudes: FloatArray, numPoints: Int)
    private external fun nativeSetPreamp(handle: Long, gainDB: Float)

    // Crossfeed JNI 方法
    private external fun nativeSetCrossfeedEnabled(handle: Long, enabled: Boolean)
    private external fun nativeSetCrossfeedParams(handle: Long, lowCutFreq: Float, highCutFreq: Float, attenuationDB: Float)

    // ==========================================
    // 压限器 (Compressor) API
    // ==========================================

    /** 启用/禁用压限器 */
    fun setCompressorEnabled(enabled: Boolean) {
        if (nativeHandle == 0L) return
        nativeSetCompressorEnabled(nativeHandle, enabled)
    }

    /**
     * 获取当前增益衰减量 (GR Meter)
     * @return 增益衰减量 (dB)，正值表示压缩量
     */
    fun getCompressorGR(): Float {
        if (nativeHandle == 0L) return 0.0f
        return nativeGetCompressorGR(nativeHandle)
    }

    // ==========================================
    // 低音增强 (BassBoost) API
    // ==========================================

    /** 启用/禁用低音增强 */
    fun setBassBoostEnabled(enabled: Boolean) {
        if (nativeHandle == 0L) return
        nativeSetBassBoostEnabled(nativeHandle, enabled)
    }

    /**
     * 设置低音增强参数
     * @param gainDB 增益 (dB)，范围 -12 ~ +12
     * @param frequency 转折频率 (Hz)，范围 50 ~ 500
     */
    fun setBassBoostParams(gainDB: Float, frequency: Float) {
        if (nativeHandle == 0L) return
        nativeSetBassBoostParams(nativeHandle, gainDB, frequency)
    }

    // ==========================================
    // 高音增强 (TrebleBoost) API
    // ==========================================

    /** 启用/禁用高音增强 */
    fun setTrebleBoostEnabled(enabled: Boolean) {
        if (nativeHandle == 0L) return
        nativeSetTrebleBoostEnabled(nativeHandle, enabled)
    }

    /**
     * 设置高音增强参数
     * @param gainDB 增益 (dB)，范围 -12 ~ +12
     * @param frequency 转折频率 (Hz)，范围 2000 ~ 16000
     */
    fun setTrebleBoostParams(gainDB: Float, frequency: Float) {
        if (nativeHandle == 0L) return
        nativeSetTrebleBoostParams(nativeHandle, gainDB, frequency)
    }

    // Compressor JNI 方法
    private external fun nativeSetCompressorEnabled(handle: Long, enabled: Boolean)
    private external fun nativeGetCompressorGR(handle: Long): Float

    // BassBoost JNI 方法
    private external fun nativeSetBassBoostEnabled(handle: Long, enabled: Boolean)
    private external fun nativeSetBassBoostParams(handle: Long, gainDB: Float, frequency: Float)

    // TrebleBoost JNI 方法
    private external fun nativeSetTrebleBoostEnabled(handle: Long, enabled: Boolean)
    private external fun nativeSetTrebleBoostParams(handle: Long, gainDB: Float, frequency: Float)



    // ==========================================
    // 扬声器外放 API（统一入口）
    // ==========================================

    /**
     * 启用或禁用扬声器外放处理。
     *
     * 这是当前 C++ 实现对应的唯一真实 Native 开关入口。旧控制器使用的
     * setSpeakerOutputElasticityEnabled() 会在 Kotlin 层委托到这里，不再声明第二套 JNI。
     */
    fun setSpeakerOutputEnabled(enabled: Boolean) {
        if (nativeHandle == 0L) return
        nativeSetSpeakerOutputEnabled(nativeHandle, enabled)
    }

    /** 选择扬声器外放模式：0=弹性，1=澎湃，2=宽广。 */
    fun setSpeakerOutputMode(mode: SpeakerOutputEffectController.Mode) {
        if (nativeHandle == 0L) return
        nativeSetSpeakerOutputMode(nativeHandle, mode.nativeCode)
    }

    /**
     * 提交“弹性”模式的完整参数快照。
     *
     * 参数模型与 SpeakerOutputEffectController 保持一致；校验会在 Kotlin 和 C++
     * 两层分别执行。音频线程只在处理块边界应用新参数。
     */
    fun setSpeakerElasticityParameters(
        parameters: SpeakerOutputEffectController.ElasticityParameters
    ) {
        if (nativeHandle == 0L) return
        val safe = parameters.sanitized()
        nativeSetSpeakerElasticityParameters(
            nativeHandle,
            safe.strengthPercent,
            safe.detectorLowHz,
            safe.detectorHighHz,
            safe.fastAttackMs,
            safe.fastReleaseMs,
            safe.slowAttackMs,
            safe.slowReleaseMs,
            safe.gainAttackMs,
            safe.gainReleaseMs,
            safe.maxBoostDb,
            safe.noiseGateDb,
            safe.headroomCeiling,
            safe.peakReleaseMs,
            safe.sensitivity
        )
    }

    /** 提交“澎湃”模式的完整参数快照。 */
    fun setSpeakerPowerfulParameters(
        parameters: SpeakerOutputEffectController.PowerfulParameters
    ) {
        if (nativeHandle == 0L) return
        val safe = parameters.sanitized()
        nativeSetSpeakerPowerfulParameters(
            nativeHandle,
            safe.strengthPercent,
            safe.bodyLowHz,
            safe.bodyHighHz,
            safe.bassBoostDb,
            safe.harmonicPercent,
            safe.compressorThresholdDb,
            safe.compressorRatio,
            safe.compressorAttackMs,
            safe.compressorReleaseMs,
            safe.parallelMixPercent,
            safe.makeupGainDb,
            safe.presenceBoostDb,
            safe.headroomCeiling
        )
    }

    /** 提交“宽广”模式的完整参数快照。 */
    fun setSpeakerWideParameters(
        parameters: SpeakerOutputEffectController.WideParameters
    ) {
        if (nativeHandle == 0L) return
        val safe = parameters.sanitized()
        nativeSetSpeakerWideParameters(
            nativeHandle,
            safe.strengthPercent,
            safe.crossoverHz,
            safe.widthDb,
            safe.decorrelationPercent,
            safe.bassCenterPercent,
            safe.centerProtectionPercent,
            safe.headroomCeiling
        )
    }

    // ------------------------------------------------------------------
    // 旧版控制器兼容层
    // ------------------------------------------------------------------

    /**
     * 兼容 SpeakerOutputElasticityController 的旧方法名。
     *
     * 仅做 Kotlin 委托，不对应独立 JNI，避免出现 UnsatisfiedLinkError。
     */
    fun setSpeakerOutputElasticityEnabled(enabled: Boolean) {
        setSpeakerOutputEnabled(enabled)
    }

    /**
     * 将旧控制器的参数格式转换为当前统一参数模型。
     *
     * sensitivityPercent 使用指数映射：0%=0.25、约82%=1.92、100%=3.0；
     * peakCeilingDb 从 dBFS 转换为线性峰值；旧接口没有 peakReleaseMs，使用70ms。
     */
    fun setSpeakerOutputElasticityParams(
        strengthPercent: Float,
        detectorLowHz: Float,
        detectorHighHz: Float,
        sensitivityPercent: Float,
        gateThresholdDb: Float,
        fastAttackMs: Float,
        fastReleaseMs: Float,
        slowAttackMs: Float,
        slowReleaseMs: Float,
        gainAttackMs: Float,
        gainReleaseMs: Float,
        maxBoostDb: Float,
        peakCeilingDb: Float
    ) {
        val safeSensitivityPercent = finiteOr(sensitivityPercent, 82f).coerceIn(0f, 100f)
        val normalizedSensitivity = safeSensitivityPercent / 100f
        val mappedSensitivity = (
            0.25 * Math.pow(12.0, normalizedSensitivity.toDouble())
        ).toFloat().coerceIn(0.25f, 3f)

        val safePeakCeilingDb = finiteOr(peakCeilingDb, -0.2f).coerceIn(-6f, -0.1f)
        val linearPeakCeiling = Math.pow(
            10.0,
            safePeakCeilingDb.toDouble() / 20.0
        ).toFloat().coerceIn(0.70f, 0.995f)

        setSpeakerElasticityParameters(
            SpeakerOutputEffectController.ElasticityParameters(
                strengthPercent = strengthPercent,
                detectorLowHz = detectorLowHz,
                detectorHighHz = detectorHighHz,
                fastAttackMs = fastAttackMs,
                fastReleaseMs = fastReleaseMs,
                slowAttackMs = slowAttackMs,
                slowReleaseMs = slowReleaseMs,
                gainAttackMs = gainAttackMs,
                gainReleaseMs = gainReleaseMs,
                maxBoostDb = maxBoostDb,
                noiseGateDb = gateThresholdDb,
                headroomCeiling = linearPeakCeiling,
                peakReleaseMs = 70f,
                sensitivity = mappedSensitivity
            )
        )
    }

    private fun finiteOr(value: Float, fallback: Float): Float =
        if (value.isFinite()) value else fallback

    // 与 speaker_output_effect.cpp 保持一一对应的 JNI 声明。
    private external fun nativeSetSpeakerOutputEnabled(handle: Long, enabled: Boolean)
    private external fun nativeSetSpeakerOutputMode(handle: Long, mode: Int)
    private external fun nativeSetSpeakerElasticityParameters(
        handle: Long,
        strengthPercent: Float,
        detectorLowHz: Float,
        detectorHighHz: Float,
        fastAttackMs: Float,
        fastReleaseMs: Float,
        slowAttackMs: Float,
        slowReleaseMs: Float,
        gainAttackMs: Float,
        gainReleaseMs: Float,
        maxBoostDb: Float,
        noiseGateDb: Float,
        headroomCeiling: Float,
        peakReleaseMs: Float,
        sensitivity: Float
    )
    private external fun nativeSetSpeakerPowerfulParameters(
        handle: Long,
        strengthPercent: Float,
        bodyLowHz: Float,
        bodyHighHz: Float,
        bassBoostDb: Float,
        harmonicPercent: Float,
        compressorThresholdDb: Float,
        compressorRatio: Float,
        compressorAttackMs: Float,
        compressorReleaseMs: Float,
        parallelMixPercent: Float,
        makeupGainDb: Float,
        presenceBoostDb: Float,
        headroomCeiling: Float
    )

    private external fun nativeSetSpeakerWideParameters(
        handle: Long,
        strengthPercent: Float,
        crossoverHz: Float,
        widthDb: Float,
        decorrelationPercent: Float,
        bassCenterPercent: Float,
        centerProtectionPercent: Float,
        headroomCeiling: Float
    )

    // ==========================================
    // 360° 环绕音 (Surround360) API
    // ==========================================

    /** 启用/禁用 360° 环绕音 */
    fun setSurround360Enabled(enabled: Boolean) {
        if (nativeHandle == 0L) return
        nativeSetSurround360Enabled(nativeHandle, enabled)
    }

    /**
     * 设置 360° 环绕音参数
     * @param intensity 效果强度 (0~100)
     * @param azimuthDeg 方位角 (0~360°), 0=前, 90=右, 180=后, 270=左
     */
    fun setSurround360Params(intensity: Float, azimuthDeg: Float) {
        if (nativeHandle == 0L) return
        nativeSetSurround360Params(nativeHandle, intensity, azimuthDeg)
    }

    // ==========================================
    // 360° 全景音 (Panoramic360) API
    // ==========================================

    /** 启用/禁用 360° 全景音 */
    fun setPanoramic360Enabled(enabled: Boolean) {
        if (nativeHandle == 0L) return
        nativeSetPanoramic360Enabled(nativeHandle, enabled)
    }

    /**
     * 设置 360° 全景音参数
     * @param intensity 效果强度 (0~100)
     * @param azimuthDeg 方位角 (0~360°), 0=前, 90=右, 180=后, 270=左
     * @param elevationDeg 仰角 (-90~+90°), 正=上方, 负=下方
     */
    fun setPanoramic360Params(intensity: Float, azimuthDeg: Float, elevationDeg: Float) {
        if (nativeHandle == 0L) return
        nativeSetPanoramic360Params(nativeHandle, intensity, azimuthDeg, elevationDeg)
    }

    // Surround360 JNI 方法
    private external fun nativeSetSurround360Enabled(handle: Long, enabled: Boolean)
    private external fun nativeSetSurround360Params(handle: Long, intensity: Float, azimuthDeg: Float)

    // Panoramic360 JNI 方法
    private external fun nativeSetPanoramic360Enabled(handle: Long, enabled: Boolean)
    private external fun nativeSetPanoramic360Params(handle: Long, intensity: Float, azimuthDeg: Float, elevationDeg: Float)
}
