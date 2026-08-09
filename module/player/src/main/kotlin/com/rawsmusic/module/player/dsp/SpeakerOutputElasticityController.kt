package com.rawsmusic.module.player.dsp

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.rawsmusic.module.data.prefs.AppPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.pow

/**
 * 扬声器外放设置控制器。
 *
 * 兼容既有类名，统一管理弹性、澎湃和宽广三种互斥模式。每种模式拥有独立参数，
 * 切换模式不会覆盖其他模式的用户调整。所有参数均以完整快照写入 Native。
 */
class SpeakerOutputElasticityController(
    nativeEngine: NativeDSPEngine
) {
    companion object {
        private const val TAG = "SpeakerOutput"
        private const val PERSIST_DEBOUNCE_MS = 350L
        private const val CURRENT_ELASTICITY_PROFILE_VERSION = 2
        private const val CURRENT_POWERFUL_PROFILE_VERSION = 2

        private const val LEGACY_ELASTICITY_STRENGTH_PERCENT = 65f
        private const val LEGACY_ELASTICITY_DETECTOR_LOW_HZ = 80f
        private const val LEGACY_ELASTICITY_DETECTOR_HIGH_HZ = 950f
        private const val LEGACY_ELASTICITY_SENSITIVITY_PERCENT = 55f
        private const val LEGACY_ELASTICITY_GATE_THRESHOLD_DB = -44f
        private const val LEGACY_ELASTICITY_FAST_ATTACK_MS = 0.7f
        private const val LEGACY_ELASTICITY_FAST_RELEASE_MS = 34f
        private const val LEGACY_ELASTICITY_SLOW_ATTACK_MS = 18f
        private const val LEGACY_ELASTICITY_SLOW_RELEASE_MS = 145f
        private const val LEGACY_ELASTICITY_GAIN_ATTACK_MS = 0.6f
        private const val LEGACY_ELASTICITY_GAIN_RELEASE_MS = 45f
        private const val LEGACY_ELASTICITY_MAX_BOOST_DB = 2.8f
        private const val LEGACY_ELASTICITY_PEAK_CEILING_DB = -0.5f

        private const val LEGACY_POWERFUL_STRENGTH_PERCENT = 78f
        private const val LEGACY_POWERFUL_BODY_LOW_HZ = 65f
        private const val LEGACY_POWERFUL_BODY_HIGH_HZ = 360f
        private const val LEGACY_POWERFUL_BASS_BOOST_DB = 3.4f
        private const val LEGACY_POWERFUL_HARMONIC_PERCENT = 28f
        private const val LEGACY_POWERFUL_COMPRESSOR_THRESHOLD_DB = -18f
        private const val LEGACY_POWERFUL_COMPRESSOR_RATIO = 3.2f
        private const val LEGACY_POWERFUL_COMPRESSOR_ATTACK_MS = 12f
        private const val LEGACY_POWERFUL_COMPRESSOR_RELEASE_MS = 180f
        private const val LEGACY_POWERFUL_PARALLEL_MIX_PERCENT = 42f
        private const val LEGACY_POWERFUL_MAKEUP_GAIN_DB = 3f
        private const val LEGACY_POWERFUL_PRESENCE_BOOST_DB = 1.1f
        private const val LEGACY_POWERFUL_PEAK_CEILING_DB = -0.3f

        const val DEFAULT_STRENGTH_PERCENT = 82f
        const val DEFAULT_DETECTOR_LOW_HZ = 85f
        const val DEFAULT_DETECTOR_HIGH_HZ = 1350f
        const val DEFAULT_SENSITIVITY_PERCENT = 82f
        const val DEFAULT_GATE_THRESHOLD_DB = -50f
        const val DEFAULT_FAST_ATTACK_MS = 0.35f
        const val DEFAULT_FAST_RELEASE_MS = 20f
        const val DEFAULT_SLOW_ATTACK_MS = 34f
        const val DEFAULT_SLOW_RELEASE_MS = 165f
        const val DEFAULT_GAIN_ATTACK_MS = 0.3f
        const val DEFAULT_GAIN_RELEASE_MS = 62f
        const val DEFAULT_MAX_BOOST_DB = 4.2f
        const val DEFAULT_PEAK_CEILING_DB = -0.2f

        const val DEFAULT_POWERFUL_STRENGTH_PERCENT = 84f
        const val DEFAULT_POWERFUL_BODY_LOW_HZ = 65f
        const val DEFAULT_POWERFUL_BODY_HIGH_HZ = 390f
        const val DEFAULT_POWERFUL_BASS_BOOST_DB = 4f
        const val DEFAULT_POWERFUL_HARMONIC_PERCENT = 34f
        const val DEFAULT_POWERFUL_COMPRESSOR_THRESHOLD_DB = -20f
        const val DEFAULT_POWERFUL_COMPRESSOR_RATIO = 3.5f
        const val DEFAULT_POWERFUL_COMPRESSOR_ATTACK_MS = 10f
        const val DEFAULT_POWERFUL_COMPRESSOR_RELEASE_MS = 200f
        const val DEFAULT_POWERFUL_PARALLEL_MIX_PERCENT = 48f
        const val DEFAULT_POWERFUL_MAKEUP_GAIN_DB = 3.4f
        const val DEFAULT_POWERFUL_PRESENCE_BOOST_DB = 1.3f
        const val DEFAULT_POWERFUL_PEAK_CEILING_DB = -0.25f

        const val DEFAULT_WIDE_STRENGTH_PERCENT = 76f
        const val DEFAULT_WIDE_CROSSOVER_HZ = 760f
        const val DEFAULT_WIDE_WIDTH_DB = 3.2f
        const val DEFAULT_WIDE_DECORRELATION_PERCENT = 18f
        const val DEFAULT_WIDE_BASS_CENTER_PERCENT = 58f
        const val DEFAULT_WIDE_CENTER_PROTECTION_PERCENT = 70f
        const val DEFAULT_WIDE_PEAK_CEILING_DB = -0.25f
    }

    private var nativeEngine: NativeDSPEngine = nativeEngine
    private val persistHandler = Handler(Looper.getMainLooper())
    private val persistRunnable = Runnable { persistState() }

    private val _isEnabled = MutableStateFlow(false)
    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    private val _mode = MutableStateFlow(SpeakerOutputEffectController.Mode.ELASTICITY)
    val mode: StateFlow<SpeakerOutputEffectController.Mode> = _mode.asStateFlow()

    private val _strengthPercent = MutableStateFlow(DEFAULT_STRENGTH_PERCENT)
    val strengthPercent: StateFlow<Float> = _strengthPercent.asStateFlow()
    private val _detectorLowHz = MutableStateFlow(DEFAULT_DETECTOR_LOW_HZ)
    val detectorLowHz: StateFlow<Float> = _detectorLowHz.asStateFlow()
    private val _detectorHighHz = MutableStateFlow(DEFAULT_DETECTOR_HIGH_HZ)
    val detectorHighHz: StateFlow<Float> = _detectorHighHz.asStateFlow()
    private val _sensitivityPercent = MutableStateFlow(DEFAULT_SENSITIVITY_PERCENT)
    val sensitivityPercent: StateFlow<Float> = _sensitivityPercent.asStateFlow()
    private val _gateThresholdDb = MutableStateFlow(DEFAULT_GATE_THRESHOLD_DB)
    val gateThresholdDb: StateFlow<Float> = _gateThresholdDb.asStateFlow()
    private val _fastAttackMs = MutableStateFlow(DEFAULT_FAST_ATTACK_MS)
    val fastAttackMs: StateFlow<Float> = _fastAttackMs.asStateFlow()
    private val _fastReleaseMs = MutableStateFlow(DEFAULT_FAST_RELEASE_MS)
    val fastReleaseMs: StateFlow<Float> = _fastReleaseMs.asStateFlow()
    private val _slowAttackMs = MutableStateFlow(DEFAULT_SLOW_ATTACK_MS)
    val slowAttackMs: StateFlow<Float> = _slowAttackMs.asStateFlow()
    private val _slowReleaseMs = MutableStateFlow(DEFAULT_SLOW_RELEASE_MS)
    val slowReleaseMs: StateFlow<Float> = _slowReleaseMs.asStateFlow()
    private val _gainAttackMs = MutableStateFlow(DEFAULT_GAIN_ATTACK_MS)
    val gainAttackMs: StateFlow<Float> = _gainAttackMs.asStateFlow()
    private val _gainReleaseMs = MutableStateFlow(DEFAULT_GAIN_RELEASE_MS)
    val gainReleaseMs: StateFlow<Float> = _gainReleaseMs.asStateFlow()
    private val _maxBoostDb = MutableStateFlow(DEFAULT_MAX_BOOST_DB)
    val maxBoostDb: StateFlow<Float> = _maxBoostDb.asStateFlow()
    private val _peakCeilingDb = MutableStateFlow(DEFAULT_PEAK_CEILING_DB)
    val peakCeilingDb: StateFlow<Float> = _peakCeilingDb.asStateFlow()

    private val _powerfulStrengthPercent = MutableStateFlow(DEFAULT_POWERFUL_STRENGTH_PERCENT)
    val powerfulStrengthPercent: StateFlow<Float> = _powerfulStrengthPercent.asStateFlow()
    private val _powerfulBodyLowHz = MutableStateFlow(DEFAULT_POWERFUL_BODY_LOW_HZ)
    val powerfulBodyLowHz: StateFlow<Float> = _powerfulBodyLowHz.asStateFlow()
    private val _powerfulBodyHighHz = MutableStateFlow(DEFAULT_POWERFUL_BODY_HIGH_HZ)
    val powerfulBodyHighHz: StateFlow<Float> = _powerfulBodyHighHz.asStateFlow()
    private val _powerfulBassBoostDb = MutableStateFlow(DEFAULT_POWERFUL_BASS_BOOST_DB)
    val powerfulBassBoostDb: StateFlow<Float> = _powerfulBassBoostDb.asStateFlow()
    private val _powerfulHarmonicPercent = MutableStateFlow(DEFAULT_POWERFUL_HARMONIC_PERCENT)
    val powerfulHarmonicPercent: StateFlow<Float> = _powerfulHarmonicPercent.asStateFlow()
    private val _powerfulCompressorThresholdDb = MutableStateFlow(DEFAULT_POWERFUL_COMPRESSOR_THRESHOLD_DB)
    val powerfulCompressorThresholdDb: StateFlow<Float> = _powerfulCompressorThresholdDb.asStateFlow()
    private val _powerfulCompressorRatio = MutableStateFlow(DEFAULT_POWERFUL_COMPRESSOR_RATIO)
    val powerfulCompressorRatio: StateFlow<Float> = _powerfulCompressorRatio.asStateFlow()
    private val _powerfulCompressorAttackMs = MutableStateFlow(DEFAULT_POWERFUL_COMPRESSOR_ATTACK_MS)
    val powerfulCompressorAttackMs: StateFlow<Float> = _powerfulCompressorAttackMs.asStateFlow()
    private val _powerfulCompressorReleaseMs = MutableStateFlow(DEFAULT_POWERFUL_COMPRESSOR_RELEASE_MS)
    val powerfulCompressorReleaseMs: StateFlow<Float> = _powerfulCompressorReleaseMs.asStateFlow()
    private val _powerfulParallelMixPercent = MutableStateFlow(DEFAULT_POWERFUL_PARALLEL_MIX_PERCENT)
    val powerfulParallelMixPercent: StateFlow<Float> = _powerfulParallelMixPercent.asStateFlow()
    private val _powerfulMakeupGainDb = MutableStateFlow(DEFAULT_POWERFUL_MAKEUP_GAIN_DB)
    val powerfulMakeupGainDb: StateFlow<Float> = _powerfulMakeupGainDb.asStateFlow()
    private val _powerfulPresenceBoostDb = MutableStateFlow(DEFAULT_POWERFUL_PRESENCE_BOOST_DB)
    val powerfulPresenceBoostDb: StateFlow<Float> = _powerfulPresenceBoostDb.asStateFlow()
    private val _powerfulPeakCeilingDb = MutableStateFlow(DEFAULT_POWERFUL_PEAK_CEILING_DB)
    val powerfulPeakCeilingDb: StateFlow<Float> = _powerfulPeakCeilingDb.asStateFlow()

    private val _wideStrengthPercent = MutableStateFlow(DEFAULT_WIDE_STRENGTH_PERCENT)
    val wideStrengthPercent: StateFlow<Float> = _wideStrengthPercent.asStateFlow()
    private val _wideCrossoverHz = MutableStateFlow(DEFAULT_WIDE_CROSSOVER_HZ)
    val wideCrossoverHz: StateFlow<Float> = _wideCrossoverHz.asStateFlow()
    private val _wideWidthDb = MutableStateFlow(DEFAULT_WIDE_WIDTH_DB)
    val wideWidthDb: StateFlow<Float> = _wideWidthDb.asStateFlow()
    private val _wideDecorrelationPercent = MutableStateFlow(DEFAULT_WIDE_DECORRELATION_PERCENT)
    val wideDecorrelationPercent: StateFlow<Float> = _wideDecorrelationPercent.asStateFlow()
    private val _wideBassCenterPercent = MutableStateFlow(DEFAULT_WIDE_BASS_CENTER_PERCENT)
    val wideBassCenterPercent: StateFlow<Float> = _wideBassCenterPercent.asStateFlow()
    private val _wideCenterProtectionPercent = MutableStateFlow(DEFAULT_WIDE_CENTER_PROTECTION_PERCENT)
    val wideCenterProtectionPercent: StateFlow<Float> = _wideCenterProtectionPercent.asStateFlow()
    private val _widePeakCeilingDb = MutableStateFlow(DEFAULT_WIDE_PEAK_CEILING_DB)
    val widePeakCeilingDb: StateFlow<Float> = _widePeakCeilingDb.asStateFlow()

    init {
        migrateDefaultProfilesIfNeeded()
        loadPersistedState()
        syncAllToNative()
    }

    fun connectEngine(engine: NativeDSPEngine) {
        nativeEngine = engine
        syncAllToNative()
        Log.d(TAG, "Reconnected mode=${_mode.value}, enabled=${_isEnabled.value}")
    }

    fun setEnabled(enabled: Boolean) {
        _isEnabled.value = enabled
        nativeEngine.setSpeakerOutputEnabled(enabled)
        persistImmediately()
    }

    fun setMode(mode: SpeakerOutputEffectController.Mode) {
        if (_mode.value == mode) return
        _mode.value = mode
        syncCurrentModeToNative()
        nativeEngine.setSpeakerOutputMode(mode)
        persistImmediately()
    }

    fun setStrengthPercent(value: Float) = updateElasticity(_strengthPercent, value, DEFAULT_STRENGTH_PERCENT, 0f, 100f)
    fun setSensitivityPercent(value: Float) = updateElasticity(_sensitivityPercent, value, DEFAULT_SENSITIVITY_PERCENT, 0f, 100f)
    fun setGateThresholdDb(value: Float) = updateElasticity(_gateThresholdDb, value, DEFAULT_GATE_THRESHOLD_DB, -72f, -24f)
    fun setGainAttackMs(value: Float) = updateElasticity(_gainAttackMs, value, DEFAULT_GAIN_ATTACK_MS, 0.2f, 10f)
    fun setGainReleaseMs(value: Float) = updateElasticity(_gainReleaseMs, value, DEFAULT_GAIN_RELEASE_MS, 10f, 250f)
    fun setMaxBoostDb(value: Float) = updateElasticity(_maxBoostDb, value, DEFAULT_MAX_BOOST_DB, 0f, 6f)
    fun setPeakCeilingDb(value: Float) = updateElasticity(_peakCeilingDb, value, DEFAULT_PEAK_CEILING_DB, -6f, -0.1f)

    fun setDetectorLowHz(value: Float) {
        val maximum = (_detectorHighHz.value - 100f).coerceAtMost(250f).coerceAtLeast(50f)
        _detectorLowHz.value = value.safe(DEFAULT_DETECTOR_LOW_HZ).coerceIn(50f, maximum)
        onElasticityChanged()
    }

    fun setDetectorHighHz(value: Float) {
        val minimum = (_detectorLowHz.value + 100f).coerceAtLeast(400f).coerceAtMost(2500f)
        _detectorHighHz.value = value.safe(DEFAULT_DETECTOR_HIGH_HZ).coerceIn(minimum, 2500f)
        onElasticityChanged()
    }

    fun setFastAttackMs(value: Float) {
        val safe = value.safe(DEFAULT_FAST_ATTACK_MS).coerceIn(0.2f, 5f)
        _fastAttackMs.value = safe
        if (_slowAttackMs.value < safe + 1f) _slowAttackMs.value = (safe + 1f).coerceAtMost(80f)
        onElasticityChanged()
    }

    fun setFastReleaseMs(value: Float) {
        val safe = value.safe(DEFAULT_FAST_RELEASE_MS).coerceIn(8f, 100f)
        _fastReleaseMs.value = safe
        if (_slowReleaseMs.value < safe + 10f) _slowReleaseMs.value = (safe + 10f).coerceAtMost(500f)
        onElasticityChanged()
    }

    fun setSlowAttackMs(value: Float) {
        _slowAttackMs.value = value.safe(DEFAULT_SLOW_ATTACK_MS)
            .coerceIn((_fastAttackMs.value + 1f).coerceAtMost(80f), 80f)
        onElasticityChanged()
    }

    fun setSlowReleaseMs(value: Float) {
        _slowReleaseMs.value = value.safe(DEFAULT_SLOW_RELEASE_MS)
            .coerceIn((_fastReleaseMs.value + 10f).coerceAtMost(500f), 500f)
        onElasticityChanged()
    }

    fun setPowerfulStrengthPercent(value: Float) = updatePowerful(_powerfulStrengthPercent, value, DEFAULT_POWERFUL_STRENGTH_PERCENT, 0f, 100f)
    fun setPowerfulBassBoostDb(value: Float) = updatePowerful(_powerfulBassBoostDb, value, DEFAULT_POWERFUL_BASS_BOOST_DB, 0f, 6f)
    fun setPowerfulHarmonicPercent(value: Float) = updatePowerful(_powerfulHarmonicPercent, value, DEFAULT_POWERFUL_HARMONIC_PERCENT, 0f, 100f)
    fun setPowerfulCompressorThresholdDb(value: Float) = updatePowerful(_powerfulCompressorThresholdDb, value, DEFAULT_POWERFUL_COMPRESSOR_THRESHOLD_DB, -36f, -6f)
    fun setPowerfulCompressorRatio(value: Float) = updatePowerful(_powerfulCompressorRatio, value, DEFAULT_POWERFUL_COMPRESSOR_RATIO, 1f, 8f)
    fun setPowerfulCompressorAttackMs(value: Float) = updatePowerful(_powerfulCompressorAttackMs, value, DEFAULT_POWERFUL_COMPRESSOR_ATTACK_MS, 2f, 80f)
    fun setPowerfulCompressorReleaseMs(value: Float) = updatePowerful(_powerfulCompressorReleaseMs, value, DEFAULT_POWERFUL_COMPRESSOR_RELEASE_MS, 40f, 500f)
    fun setPowerfulParallelMixPercent(value: Float) = updatePowerful(_powerfulParallelMixPercent, value, DEFAULT_POWERFUL_PARALLEL_MIX_PERCENT, 0f, 100f)
    fun setPowerfulMakeupGainDb(value: Float) = updatePowerful(_powerfulMakeupGainDb, value, DEFAULT_POWERFUL_MAKEUP_GAIN_DB, 0f, 6f)
    fun setPowerfulPresenceBoostDb(value: Float) = updatePowerful(_powerfulPresenceBoostDb, value, DEFAULT_POWERFUL_PRESENCE_BOOST_DB, 0f, 4f)
    fun setPowerfulPeakCeilingDb(value: Float) = updatePowerful(_powerfulPeakCeilingDb, value, DEFAULT_POWERFUL_PEAK_CEILING_DB, -6f, -0.1f)

    fun setPowerfulBodyLowHz(value: Float) {
        val maximum = (_powerfulBodyHighHz.value - 100f).coerceAtMost(140f).coerceAtLeast(40f)
        _powerfulBodyLowHz.value = value.safe(DEFAULT_POWERFUL_BODY_LOW_HZ).coerceIn(40f, maximum)
        onPowerfulChanged()
    }

    fun setPowerfulBodyHighHz(value: Float) {
        val minimum = (_powerfulBodyLowHz.value + 100f).coerceAtLeast(180f).coerceAtMost(700f)
        _powerfulBodyHighHz.value = value.safe(DEFAULT_POWERFUL_BODY_HIGH_HZ).coerceIn(minimum, 700f)
        onPowerfulChanged()
    }

    fun setWideStrengthPercent(value: Float) = updateWide(_wideStrengthPercent, value, DEFAULT_WIDE_STRENGTH_PERCENT, 0f, 100f)
    fun setWideCrossoverHz(value: Float) = updateWide(_wideCrossoverHz, value, DEFAULT_WIDE_CROSSOVER_HZ, 300f, 2200f)
    fun setWideWidthDb(value: Float) = updateWide(_wideWidthDb, value, DEFAULT_WIDE_WIDTH_DB, 0f, 6f)
    fun setWideDecorrelationPercent(value: Float) = updateWide(_wideDecorrelationPercent, value, DEFAULT_WIDE_DECORRELATION_PERCENT, 0f, 60f)
    fun setWideBassCenterPercent(value: Float) = updateWide(_wideBassCenterPercent, value, DEFAULT_WIDE_BASS_CENTER_PERCENT, 0f, 100f)
    fun setWideCenterProtectionPercent(value: Float) = updateWide(_wideCenterProtectionPercent, value, DEFAULT_WIDE_CENTER_PROTECTION_PERCENT, 0f, 100f)
    fun setWidePeakCeilingDb(value: Float) = updateWide(_widePeakCeilingDb, value, DEFAULT_WIDE_PEAK_CEILING_DB, -6f, -0.1f)

    fun resetCurrentModeToDefaults() {
        when (_mode.value) {
            SpeakerOutputEffectController.Mode.ELASTICITY -> resetElasticityToDefaults()
            SpeakerOutputEffectController.Mode.POWERFUL -> resetPowerfulToDefaults()
            SpeakerOutputEffectController.Mode.WIDE -> resetWideToDefaults()
        }
        persistImmediately()
    }

    fun resetParametersToDefaults() = resetCurrentModeToDefaults()

    private fun resetElasticityToDefaults() {
        _strengthPercent.value = DEFAULT_STRENGTH_PERCENT
        _detectorLowHz.value = DEFAULT_DETECTOR_LOW_HZ
        _detectorHighHz.value = DEFAULT_DETECTOR_HIGH_HZ
        _sensitivityPercent.value = DEFAULT_SENSITIVITY_PERCENT
        _gateThresholdDb.value = DEFAULT_GATE_THRESHOLD_DB
        _fastAttackMs.value = DEFAULT_FAST_ATTACK_MS
        _fastReleaseMs.value = DEFAULT_FAST_RELEASE_MS
        _slowAttackMs.value = DEFAULT_SLOW_ATTACK_MS
        _slowReleaseMs.value = DEFAULT_SLOW_RELEASE_MS
        _gainAttackMs.value = DEFAULT_GAIN_ATTACK_MS
        _gainReleaseMs.value = DEFAULT_GAIN_RELEASE_MS
        _maxBoostDb.value = DEFAULT_MAX_BOOST_DB
        _peakCeilingDb.value = DEFAULT_PEAK_CEILING_DB
        syncElasticityToNative()
    }

    private fun resetPowerfulToDefaults() {
        _powerfulStrengthPercent.value = DEFAULT_POWERFUL_STRENGTH_PERCENT
        _powerfulBodyLowHz.value = DEFAULT_POWERFUL_BODY_LOW_HZ
        _powerfulBodyHighHz.value = DEFAULT_POWERFUL_BODY_HIGH_HZ
        _powerfulBassBoostDb.value = DEFAULT_POWERFUL_BASS_BOOST_DB
        _powerfulHarmonicPercent.value = DEFAULT_POWERFUL_HARMONIC_PERCENT
        _powerfulCompressorThresholdDb.value = DEFAULT_POWERFUL_COMPRESSOR_THRESHOLD_DB
        _powerfulCompressorRatio.value = DEFAULT_POWERFUL_COMPRESSOR_RATIO
        _powerfulCompressorAttackMs.value = DEFAULT_POWERFUL_COMPRESSOR_ATTACK_MS
        _powerfulCompressorReleaseMs.value = DEFAULT_POWERFUL_COMPRESSOR_RELEASE_MS
        _powerfulParallelMixPercent.value = DEFAULT_POWERFUL_PARALLEL_MIX_PERCENT
        _powerfulMakeupGainDb.value = DEFAULT_POWERFUL_MAKEUP_GAIN_DB
        _powerfulPresenceBoostDb.value = DEFAULT_POWERFUL_PRESENCE_BOOST_DB
        _powerfulPeakCeilingDb.value = DEFAULT_POWERFUL_PEAK_CEILING_DB
        syncPowerfulToNative()
    }

    private fun resetWideToDefaults() {
        _wideStrengthPercent.value = DEFAULT_WIDE_STRENGTH_PERCENT
        _wideCrossoverHz.value = DEFAULT_WIDE_CROSSOVER_HZ
        _wideWidthDb.value = DEFAULT_WIDE_WIDTH_DB
        _wideDecorrelationPercent.value = DEFAULT_WIDE_DECORRELATION_PERCENT
        _wideBassCenterPercent.value = DEFAULT_WIDE_BASS_CENTER_PERCENT
        _wideCenterProtectionPercent.value = DEFAULT_WIDE_CENTER_PROTECTION_PERCENT
        _widePeakCeilingDb.value = DEFAULT_WIDE_PEAK_CEILING_DB
        syncWideToNative()
    }

    private fun updateElasticity(flow: MutableStateFlow<Float>, value: Float, fallback: Float, min: Float, max: Float) {
        flow.value = value.safe(fallback).coerceIn(min, max)
        onElasticityChanged()
    }

    private fun updatePowerful(flow: MutableStateFlow<Float>, value: Float, fallback: Float, min: Float, max: Float) {
        flow.value = value.safe(fallback).coerceIn(min, max)
        onPowerfulChanged()
    }

    private fun updateWide(flow: MutableStateFlow<Float>, value: Float, fallback: Float, min: Float, max: Float) {
        flow.value = value.safe(fallback).coerceIn(min, max)
        onWideChanged()
    }

    private fun onElasticityChanged() {
        if (_mode.value == SpeakerOutputEffectController.Mode.ELASTICITY) syncElasticityToNative()
        persistDebounced()
    }

    private fun onPowerfulChanged() {
        if (_mode.value == SpeakerOutputEffectController.Mode.POWERFUL) syncPowerfulToNative()
        persistDebounced()
    }

    private fun onWideChanged() {
        if (_mode.value == SpeakerOutputEffectController.Mode.WIDE) syncWideToNative()
        persistDebounced()
    }

    private fun syncAllToNative() {
        syncElasticityToNative()
        syncPowerfulToNative()
        syncWideToNative()
        nativeEngine.setSpeakerOutputMode(_mode.value)
        nativeEngine.setSpeakerOutputEnabled(_isEnabled.value)
    }

    private fun syncCurrentModeToNative() {
        when (_mode.value) {
            SpeakerOutputEffectController.Mode.ELASTICITY -> syncElasticityToNative()
            SpeakerOutputEffectController.Mode.POWERFUL -> syncPowerfulToNative()
            SpeakerOutputEffectController.Mode.WIDE -> syncWideToNative()
        }
    }

    private fun syncElasticityToNative() {
        nativeEngine.setSpeakerOutputElasticityParams(
            strengthPercent = _strengthPercent.value,
            detectorLowHz = _detectorLowHz.value,
            detectorHighHz = _detectorHighHz.value,
            sensitivityPercent = _sensitivityPercent.value,
            gateThresholdDb = _gateThresholdDb.value,
            fastAttackMs = _fastAttackMs.value,
            fastReleaseMs = _fastReleaseMs.value,
            slowAttackMs = _slowAttackMs.value,
            slowReleaseMs = _slowReleaseMs.value,
            gainAttackMs = _gainAttackMs.value,
            gainReleaseMs = _gainReleaseMs.value,
            maxBoostDb = _maxBoostDb.value,
            peakCeilingDb = _peakCeilingDb.value
        )
    }

    private fun syncPowerfulToNative() {
        nativeEngine.setSpeakerPowerfulParameters(
            SpeakerOutputEffectController.PowerfulParameters(
                strengthPercent = _powerfulStrengthPercent.value,
                bodyLowHz = _powerfulBodyLowHz.value,
                bodyHighHz = _powerfulBodyHighHz.value,
                bassBoostDb = _powerfulBassBoostDb.value,
                harmonicPercent = _powerfulHarmonicPercent.value,
                compressorThresholdDb = _powerfulCompressorThresholdDb.value,
                compressorRatio = _powerfulCompressorRatio.value,
                compressorAttackMs = _powerfulCompressorAttackMs.value,
                compressorReleaseMs = _powerfulCompressorReleaseMs.value,
                parallelMixPercent = _powerfulParallelMixPercent.value,
                makeupGainDb = _powerfulMakeupGainDb.value,
                presenceBoostDb = _powerfulPresenceBoostDb.value,
                headroomCeiling = dbToLinear(_powerfulPeakCeilingDb.value)
            )
        )
    }

    private fun syncWideToNative() {
        nativeEngine.setSpeakerWideParameters(
            SpeakerOutputEffectController.WideParameters(
                strengthPercent = _wideStrengthPercent.value,
                crossoverHz = _wideCrossoverHz.value,
                widthDb = _wideWidthDb.value,
                decorrelationPercent = _wideDecorrelationPercent.value,
                bassCenterPercent = _wideBassCenterPercent.value,
                centerProtectionPercent = _wideCenterProtectionPercent.value,
                headroomCeiling = dbToLinear(_widePeakCeilingDb.value)
            )
        )
    }

    private fun migrateDefaultProfilesIfNeeded() {
        migrateElasticityDefaultsIfNeeded()
        migratePowerfulDefaultsIfNeeded()
    }

    private fun migrateElasticityDefaultsIfNeeded() {
        val preferences = AppPreferences.SpeakerOutputElasticity
        if (preferences.profileVersion >= CURRENT_ELASTICITY_PROFILE_VERSION) return
        val unchanged =
            close(preferences.strengthPercent, LEGACY_ELASTICITY_STRENGTH_PERCENT) &&
                close(preferences.detectorLowHz, LEGACY_ELASTICITY_DETECTOR_LOW_HZ) &&
                close(preferences.detectorHighHz, LEGACY_ELASTICITY_DETECTOR_HIGH_HZ) &&
                close(preferences.sensitivityPercent, LEGACY_ELASTICITY_SENSITIVITY_PERCENT) &&
                close(preferences.gateThresholdDb, LEGACY_ELASTICITY_GATE_THRESHOLD_DB) &&
                close(preferences.fastAttackMs, LEGACY_ELASTICITY_FAST_ATTACK_MS) &&
                close(preferences.fastReleaseMs, LEGACY_ELASTICITY_FAST_RELEASE_MS) &&
                close(preferences.slowAttackMs, LEGACY_ELASTICITY_SLOW_ATTACK_MS) &&
                close(preferences.slowReleaseMs, LEGACY_ELASTICITY_SLOW_RELEASE_MS) &&
                close(preferences.gainAttackMs, LEGACY_ELASTICITY_GAIN_ATTACK_MS) &&
                close(preferences.gainReleaseMs, LEGACY_ELASTICITY_GAIN_RELEASE_MS) &&
                close(preferences.maxBoostDb, LEGACY_ELASTICITY_MAX_BOOST_DB) &&
                close(preferences.peakCeilingDb, LEGACY_ELASTICITY_PEAK_CEILING_DB)
        if (unchanged) {
            preferences.strengthPercent = DEFAULT_STRENGTH_PERCENT
            preferences.detectorLowHz = DEFAULT_DETECTOR_LOW_HZ
            preferences.detectorHighHz = DEFAULT_DETECTOR_HIGH_HZ
            preferences.sensitivityPercent = DEFAULT_SENSITIVITY_PERCENT
            preferences.gateThresholdDb = DEFAULT_GATE_THRESHOLD_DB
            preferences.fastAttackMs = DEFAULT_FAST_ATTACK_MS
            preferences.fastReleaseMs = DEFAULT_FAST_RELEASE_MS
            preferences.slowAttackMs = DEFAULT_SLOW_ATTACK_MS
            preferences.slowReleaseMs = DEFAULT_SLOW_RELEASE_MS
            preferences.gainAttackMs = DEFAULT_GAIN_ATTACK_MS
            preferences.gainReleaseMs = DEFAULT_GAIN_RELEASE_MS
            preferences.maxBoostDb = DEFAULT_MAX_BOOST_DB
            preferences.peakCeilingDb = DEFAULT_PEAK_CEILING_DB
            Log.i(TAG, "Updated elasticity default profile")
        }
        preferences.profileVersion = CURRENT_ELASTICITY_PROFILE_VERSION
    }

    private fun migratePowerfulDefaultsIfNeeded() {
        val preferences = AppPreferences.SpeakerOutputPowerful
        if (preferences.profileVersion >= CURRENT_POWERFUL_PROFILE_VERSION) return
        val unchanged =
            close(preferences.strengthPercent, LEGACY_POWERFUL_STRENGTH_PERCENT) &&
                close(preferences.bodyLowHz, LEGACY_POWERFUL_BODY_LOW_HZ) &&
                close(preferences.bodyHighHz, LEGACY_POWERFUL_BODY_HIGH_HZ) &&
                close(preferences.bassBoostDb, LEGACY_POWERFUL_BASS_BOOST_DB) &&
                close(preferences.harmonicPercent, LEGACY_POWERFUL_HARMONIC_PERCENT) &&
                close(preferences.compressorThresholdDb, LEGACY_POWERFUL_COMPRESSOR_THRESHOLD_DB) &&
                close(preferences.compressorRatio, LEGACY_POWERFUL_COMPRESSOR_RATIO) &&
                close(preferences.compressorAttackMs, LEGACY_POWERFUL_COMPRESSOR_ATTACK_MS) &&
                close(preferences.compressorReleaseMs, LEGACY_POWERFUL_COMPRESSOR_RELEASE_MS) &&
                close(preferences.parallelMixPercent, LEGACY_POWERFUL_PARALLEL_MIX_PERCENT) &&
                close(preferences.makeupGainDb, LEGACY_POWERFUL_MAKEUP_GAIN_DB) &&
                close(preferences.presenceBoostDb, LEGACY_POWERFUL_PRESENCE_BOOST_DB) &&
                close(preferences.peakCeilingDb, LEGACY_POWERFUL_PEAK_CEILING_DB)
        if (unchanged) {
            preferences.strengthPercent = DEFAULT_POWERFUL_STRENGTH_PERCENT
            preferences.bodyLowHz = DEFAULT_POWERFUL_BODY_LOW_HZ
            preferences.bodyHighHz = DEFAULT_POWERFUL_BODY_HIGH_HZ
            preferences.bassBoostDb = DEFAULT_POWERFUL_BASS_BOOST_DB
            preferences.harmonicPercent = DEFAULT_POWERFUL_HARMONIC_PERCENT
            preferences.compressorThresholdDb = DEFAULT_POWERFUL_COMPRESSOR_THRESHOLD_DB
            preferences.compressorRatio = DEFAULT_POWERFUL_COMPRESSOR_RATIO
            preferences.compressorAttackMs = DEFAULT_POWERFUL_COMPRESSOR_ATTACK_MS
            preferences.compressorReleaseMs = DEFAULT_POWERFUL_COMPRESSOR_RELEASE_MS
            preferences.parallelMixPercent = DEFAULT_POWERFUL_PARALLEL_MIX_PERCENT
            preferences.makeupGainDb = DEFAULT_POWERFUL_MAKEUP_GAIN_DB
            preferences.presenceBoostDb = DEFAULT_POWERFUL_PRESENCE_BOOST_DB
            preferences.peakCeilingDb = DEFAULT_POWERFUL_PEAK_CEILING_DB
            Log.i(TAG, "Updated powerful default profile")
        }
        preferences.profileVersion = CURRENT_POWERFUL_PROFILE_VERSION
    }

    private fun loadPersistedState() {
        val elasticity = AppPreferences.SpeakerOutputElasticity
        _isEnabled.value = elasticity.isEnabled
        _mode.value = SpeakerOutputEffectController.Mode.fromNativeCode(elasticity.modeCode)
        _strengthPercent.value = elasticity.strengthPercent
        _detectorLowHz.value = elasticity.detectorLowHz
        _detectorHighHz.value = elasticity.detectorHighHz.coerceAtLeast(_detectorLowHz.value + 100f)
        _sensitivityPercent.value = elasticity.sensitivityPercent
        _gateThresholdDb.value = elasticity.gateThresholdDb
        _fastAttackMs.value = elasticity.fastAttackMs
        _fastReleaseMs.value = elasticity.fastReleaseMs
        _slowAttackMs.value = elasticity.slowAttackMs.coerceAtLeast(_fastAttackMs.value + 1f)
        _slowReleaseMs.value = elasticity.slowReleaseMs.coerceAtLeast(_fastReleaseMs.value + 10f)
        _gainAttackMs.value = elasticity.gainAttackMs
        _gainReleaseMs.value = elasticity.gainReleaseMs
        _maxBoostDb.value = elasticity.maxBoostDb
        _peakCeilingDb.value = elasticity.peakCeilingDb

        val powerful = AppPreferences.SpeakerOutputPowerful
        _powerfulStrengthPercent.value = powerful.strengthPercent
        _powerfulBodyLowHz.value = powerful.bodyLowHz
        _powerfulBodyHighHz.value = powerful.bodyHighHz.coerceAtLeast(_powerfulBodyLowHz.value + 100f)
        _powerfulBassBoostDb.value = powerful.bassBoostDb
        _powerfulHarmonicPercent.value = powerful.harmonicPercent
        _powerfulCompressorThresholdDb.value = powerful.compressorThresholdDb
        _powerfulCompressorRatio.value = powerful.compressorRatio
        _powerfulCompressorAttackMs.value = powerful.compressorAttackMs
        _powerfulCompressorReleaseMs.value = powerful.compressorReleaseMs
        _powerfulParallelMixPercent.value = powerful.parallelMixPercent
        _powerfulMakeupGainDb.value = powerful.makeupGainDb
        _powerfulPresenceBoostDb.value = powerful.presenceBoostDb
        _powerfulPeakCeilingDb.value = powerful.peakCeilingDb

        val wide = AppPreferences.SpeakerOutputWide
        _wideStrengthPercent.value = wide.strengthPercent
        _wideCrossoverHz.value = wide.crossoverHz
        _wideWidthDb.value = wide.widthDb
        _wideDecorrelationPercent.value = wide.decorrelationPercent
        _wideBassCenterPercent.value = wide.bassCenterPercent
        _wideCenterProtectionPercent.value = wide.centerProtectionPercent
        _widePeakCeilingDb.value = wide.peakCeilingDb
    }

    private fun persistState() {
        val elasticity = AppPreferences.SpeakerOutputElasticity
        elasticity.isEnabled = _isEnabled.value
        elasticity.modeCode = _mode.value.nativeCode
        elasticity.strengthPercent = _strengthPercent.value
        elasticity.detectorLowHz = _detectorLowHz.value
        elasticity.detectorHighHz = _detectorHighHz.value
        elasticity.sensitivityPercent = _sensitivityPercent.value
        elasticity.gateThresholdDb = _gateThresholdDb.value
        elasticity.fastAttackMs = _fastAttackMs.value
        elasticity.fastReleaseMs = _fastReleaseMs.value
        elasticity.slowAttackMs = _slowAttackMs.value
        elasticity.slowReleaseMs = _slowReleaseMs.value
        elasticity.gainAttackMs = _gainAttackMs.value
        elasticity.gainReleaseMs = _gainReleaseMs.value
        elasticity.maxBoostDb = _maxBoostDb.value
        elasticity.peakCeilingDb = _peakCeilingDb.value

        val powerful = AppPreferences.SpeakerOutputPowerful
        powerful.strengthPercent = _powerfulStrengthPercent.value
        powerful.bodyLowHz = _powerfulBodyLowHz.value
        powerful.bodyHighHz = _powerfulBodyHighHz.value
        powerful.bassBoostDb = _powerfulBassBoostDb.value
        powerful.harmonicPercent = _powerfulHarmonicPercent.value
        powerful.compressorThresholdDb = _powerfulCompressorThresholdDb.value
        powerful.compressorRatio = _powerfulCompressorRatio.value
        powerful.compressorAttackMs = _powerfulCompressorAttackMs.value
        powerful.compressorReleaseMs = _powerfulCompressorReleaseMs.value
        powerful.parallelMixPercent = _powerfulParallelMixPercent.value
        powerful.makeupGainDb = _powerfulMakeupGainDb.value
        powerful.presenceBoostDb = _powerfulPresenceBoostDb.value
        powerful.peakCeilingDb = _powerfulPeakCeilingDb.value

        val wide = AppPreferences.SpeakerOutputWide
        wide.strengthPercent = _wideStrengthPercent.value
        wide.crossoverHz = _wideCrossoverHz.value
        wide.widthDb = _wideWidthDb.value
        wide.decorrelationPercent = _wideDecorrelationPercent.value
        wide.bassCenterPercent = _wideBassCenterPercent.value
        wide.centerProtectionPercent = _wideCenterProtectionPercent.value
        wide.peakCeilingDb = _widePeakCeilingDb.value
    }

    private fun persistDebounced() {
        persistHandler.removeCallbacks(persistRunnable)
        persistHandler.postDelayed(persistRunnable, PERSIST_DEBOUNCE_MS)
    }

    private fun persistImmediately() {
        persistHandler.removeCallbacks(persistRunnable)
        persistState()
    }

    private fun close(actual: Float, expected: Float): Boolean = abs(actual - expected) < 0.001f
    private fun Float.safe(fallback: Float): Float = if (isFinite()) this else fallback
    private fun dbToLinear(db: Float): Float = 10.0.pow(db.toDouble() / 20.0).toFloat().coerceIn(0.70f, 0.995f)
}
