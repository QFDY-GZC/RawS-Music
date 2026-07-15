package com.rawsmusic.module.player.dsp

/**
 * “扬声器外放”Native 参数模型。
 *
 * 该类不负责持久化；设置页使用 [SpeakerOutputElasticityController] 保存参数。
 * 它只提供经过校验的不可变快照，供 [NativeDSPEngine] 与 Java 门面复用。
 */
class SpeakerOutputEffectController(
    private var nativeEngine: NativeDSPEngine
) {
    enum class Mode(val nativeCode: Int) {
        ELASTICITY(0),
        POWERFUL(1),
        WIDE(2);

        companion object {
            fun fromNativeCode(value: Int): Mode = values().firstOrNull {
                it.nativeCode == value
            } ?: ELASTICITY
        }
    }

    /** 弹性模式完整参数。 */
    data class ElasticityParameters(
        val strengthPercent: Float = 82f,
        val detectorLowHz: Float = 85f,
        val detectorHighHz: Float = 1350f,
        val fastAttackMs: Float = 0.35f,
        val fastReleaseMs: Float = 20f,
        val slowAttackMs: Float = 34f,
        val slowReleaseMs: Float = 165f,
        val gainAttackMs: Float = 0.3f,
        val gainReleaseMs: Float = 62f,
        val maxBoostDb: Float = 4.2f,
        val noiseGateDb: Float = -50f,
        val headroomCeiling: Float = 0.9772f,
        val peakReleaseMs: Float = 70f,
        val sensitivity: Float = 1.92f
    ) {
        fun sanitized(): ElasticityParameters {
            val low = detectorLowHz.finiteOr(85f).coerceIn(40f, 300f)
            val high = detectorHighHz.finiteOr(1350f)
                .coerceIn(300f, 3000f)
                .coerceAtLeast(low + 80f)
            return copy(
                strengthPercent = strengthPercent.finiteOr(82f).coerceIn(0f, 100f),
                detectorLowHz = low,
                detectorHighHz = high,
                fastAttackMs = fastAttackMs.finiteOr(0.35f).coerceIn(0.1f, 10f),
                fastReleaseMs = fastReleaseMs.finiteOr(20f).coerceIn(5f, 150f),
                slowAttackMs = slowAttackMs.finiteOr(34f).coerceIn(2f, 100f),
                slowReleaseMs = slowReleaseMs.finiteOr(165f).coerceIn(30f, 500f),
                gainAttackMs = gainAttackMs.finiteOr(0.3f).coerceIn(0.1f, 10f),
                gainReleaseMs = gainReleaseMs.finiteOr(62f).coerceIn(10f, 250f),
                maxBoostDb = maxBoostDb.finiteOr(4.2f).coerceIn(0f, 6f),
                noiseGateDb = noiseGateDb.finiteOr(-50f).coerceIn(-80f, -24f),
                headroomCeiling = headroomCeiling.finiteOr(0.9772f).coerceIn(0.70f, 0.995f),
                peakReleaseMs = peakReleaseMs.finiteOr(70f).coerceIn(10f, 300f),
                sensitivity = sensitivity.finiteOr(1.92f).coerceIn(0.25f, 3f)
            )
        }
    }

    /** 澎湃模式完整参数。 */
    data class PowerfulParameters(
        val strengthPercent: Float = 84f,
        val bodyLowHz: Float = 65f,
        val bodyHighHz: Float = 390f,
        val bassBoostDb: Float = 4f,
        val harmonicPercent: Float = 34f,
        val compressorThresholdDb: Float = -20f,
        val compressorRatio: Float = 3.5f,
        val compressorAttackMs: Float = 10f,
        val compressorReleaseMs: Float = 200f,
        val parallelMixPercent: Float = 48f,
        val makeupGainDb: Float = 3.4f,
        val presenceBoostDb: Float = 1.3f,
        val headroomCeiling: Float = 0.9716f
    ) {
        fun sanitized(): PowerfulParameters {
            val low = bodyLowHz.finiteOr(65f).coerceIn(40f, 140f)
            val high = bodyHighHz.finiteOr(390f)
                .coerceIn(180f, 700f)
                .coerceAtLeast(low + 100f)
            return copy(
                strengthPercent = strengthPercent.finiteOr(84f).coerceIn(0f, 100f),
                bodyLowHz = low,
                bodyHighHz = high,
                bassBoostDb = bassBoostDb.finiteOr(4f).coerceIn(0f, 6f),
                harmonicPercent = harmonicPercent.finiteOr(34f).coerceIn(0f, 100f),
                compressorThresholdDb = compressorThresholdDb.finiteOr(-20f).coerceIn(-36f, -6f),
                compressorRatio = compressorRatio.finiteOr(3.5f).coerceIn(1f, 8f),
                compressorAttackMs = compressorAttackMs.finiteOr(10f).coerceIn(2f, 80f),
                compressorReleaseMs = compressorReleaseMs.finiteOr(200f).coerceIn(40f, 500f),
                parallelMixPercent = parallelMixPercent.finiteOr(48f).coerceIn(0f, 100f),
                makeupGainDb = makeupGainDb.finiteOr(3.4f).coerceIn(0f, 6f),
                presenceBoostDb = presenceBoostDb.finiteOr(1.3f).coerceIn(0f, 4f),
                headroomCeiling = headroomCeiling.finiteOr(0.9716f).coerceIn(0.70f, 0.995f)
            )
        }
    }

    /** 宽广模式完整参数。 */
    data class WideParameters(
        val strengthPercent: Float = 76f,
        val crossoverHz: Float = 760f,
        val widthDb: Float = 3.2f,
        val decorrelationPercent: Float = 18f,
        val bassCenterPercent: Float = 58f,
        val centerProtectionPercent: Float = 70f,
        val headroomCeiling: Float = 0.9716f
    ) {
        fun sanitized(): WideParameters = copy(
            strengthPercent = strengthPercent.finiteOr(76f).coerceIn(0f, 100f),
            crossoverHz = crossoverHz.finiteOr(760f).coerceIn(300f, 2200f),
            widthDb = widthDb.finiteOr(3.2f).coerceIn(0f, 6f),
            decorrelationPercent = decorrelationPercent.finiteOr(18f).coerceIn(0f, 60f),
            bassCenterPercent = bassCenterPercent.finiteOr(58f).coerceIn(0f, 100f),
            centerProtectionPercent = centerProtectionPercent.finiteOr(70f).coerceIn(0f, 100f),
            headroomCeiling = headroomCeiling.finiteOr(0.9716f).coerceIn(0.70f, 0.995f)
        )
    }

    var enabled: Boolean = false
        private set
    var mode: Mode = Mode.ELASTICITY
        private set
    var elasticityParameters: ElasticityParameters = ElasticityParameters()
        private set
    var powerfulParameters: PowerfulParameters = PowerfulParameters()
        private set
    var wideParameters: WideParameters = WideParameters()
        private set

    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
        nativeEngine.setSpeakerOutputEnabled(enabled)
    }

    fun setMode(mode: Mode) {
        this.mode = mode
        nativeEngine.setSpeakerOutputMode(mode)
    }

    fun setElasticityParameters(parameters: ElasticityParameters) {
        elasticityParameters = parameters.sanitized()
        nativeEngine.setSpeakerElasticityParameters(elasticityParameters)
    }

    fun setPowerfulParameters(parameters: PowerfulParameters) {
        powerfulParameters = parameters.sanitized()
        nativeEngine.setSpeakerPowerfulParameters(powerfulParameters)
    }

    fun setWideParameters(parameters: WideParameters) {
        wideParameters = parameters.sanitized()
        nativeEngine.setSpeakerWideParameters(wideParameters)
    }

    fun reconnect(engine: NativeDSPEngine) {
        nativeEngine = engine
        applyCurrentState()
    }

    fun applyCurrentState() {
        nativeEngine.setSpeakerElasticityParameters(elasticityParameters)
        nativeEngine.setSpeakerPowerfulParameters(powerfulParameters)
        nativeEngine.setSpeakerWideParameters(wideParameters)
        nativeEngine.setSpeakerOutputMode(mode)
        nativeEngine.setSpeakerOutputEnabled(enabled)
    }
}

private fun Float.finiteOr(fallback: Float): Float = if (isFinite()) this else fallback
