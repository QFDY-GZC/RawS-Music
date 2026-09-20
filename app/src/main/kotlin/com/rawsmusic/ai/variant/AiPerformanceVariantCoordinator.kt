package com.rawsmusic.ai.variant

import android.content.Context
import com.rawsmusic.ai.instrument.AiInstalledInstrumentPack
import com.rawsmusic.ai.instrument.AiInstrumentLeadArtifact
import com.rawsmusic.ai.instrument.AiInstrumentRenderConfig
import com.rawsmusic.ai.instrument.AiInstrumentRenderCoordinator
import com.rawsmusic.ai.melody.AiPerformanceTrack
import com.rawsmusic.separation.AiStemDependency

/** Step-4 orchestration: cached performance -> cached lead -> cached encoded media variant. */
class AiPerformanceVariantCoordinator private constructor(context: Context) {
    private val instrumentCoordinator = AiInstrumentRenderCoordinator.get(context.applicationContext)
    private val variantGenerator = AiMediaVariantGenerator.get(context.applicationContext)

    data class PreparedVariant(
        val lead: AiInstrumentLeadArtifact,
        val variant: AiMediaVariantArtifact,
    )

    data class PreparedVariantSet(
        val lead: AiInstrumentLeadArtifact,
        val instrumentPerformance: AiMediaVariantArtifact,
        val vocalEnsemble: AiMediaVariantArtifact,
    )

    suspend fun prepareInstrumentVariants(
        dependency: AiStemDependency,
        performance: AiPerformanceTrack,
        pack: AiInstalledInstrumentPack,
        instrumentConfig: AiInstrumentRenderConfig = AiInstrumentRenderConfig(),
        instrumentPerformanceMix: AiMediaVariantMixConfig =
            AiMediaVariantMixConfig.defaultFor(AiMediaVariantMode.INSTRUMENT_PERFORMANCE),
        vocalEnsembleMix: AiMediaVariantMixConfig =
            AiMediaVariantMixConfig.defaultFor(AiMediaVariantMode.VOCAL_ENSEMBLE),
        onProgress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): PreparedVariantSet {
        require(performance.dependencyFingerprint == dependency.dependencyFingerprint) {
            "AI performance track is stale for this stem dependency"
        }
        val lead = instrumentCoordinator.renderInstrumentLead(
            performance = performance,
            pack = pack,
            config = instrumentConfig,
            onProgress = { fraction -> onProgress(fraction * 0.45f) },
            isCancelled = isCancelled,
        )
        check(!isCancelled()) { "AI media variant generation cancelled" }
        val performanceVariant = variantGenerator.generateOrLoad(
            dependency = dependency,
            lead = lead,
            mode = AiMediaVariantMode.INSTRUMENT_PERFORMANCE,
            config = instrumentPerformanceMix,
            onProgress = { fraction -> onProgress(0.45f + fraction * 0.275f) },
            isCancelled = isCancelled,
        )
        check(!isCancelled()) { "AI media variant generation cancelled" }
        val ensembleVariant = variantGenerator.generateOrLoad(
            dependency = dependency,
            lead = lead,
            mode = AiMediaVariantMode.VOCAL_ENSEMBLE,
            config = vocalEnsembleMix,
            onProgress = { fraction -> onProgress(0.725f + fraction * 0.275f) },
            isCancelled = isCancelled,
        )
        onProgress(1f)
        return PreparedVariantSet(
            lead = lead,
            instrumentPerformance = performanceVariant,
            vocalEnsemble = ensembleVariant,
        )
    }

    suspend fun prepareInstrumentVariant(
        dependency: AiStemDependency,
        performance: AiPerformanceTrack,
        pack: AiInstalledInstrumentPack,
        mode: AiMediaVariantMode,
        instrumentConfig: AiInstrumentRenderConfig = AiInstrumentRenderConfig(),
        mixConfig: AiMediaVariantMixConfig = AiMediaVariantMixConfig.defaultFor(mode),
        onProgress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): PreparedVariant {
        require(performance.dependencyFingerprint == dependency.dependencyFingerprint) {
            "AI performance track is stale for this stem dependency"
        }
        val lead = instrumentCoordinator.renderInstrumentLead(
            performance = performance,
            pack = pack,
            config = instrumentConfig,
            onProgress = { fraction -> onProgress(fraction * 0.55f) },
            isCancelled = isCancelled,
        )
        check(!isCancelled()) { "AI media variant generation cancelled" }
        val variant = variantGenerator.generateOrLoad(
            dependency = dependency,
            lead = lead,
            mode = mode,
            config = mixConfig,
            onProgress = { fraction -> onProgress(0.55f + fraction * 0.45f) },
            isCancelled = isCancelled,
        )
        onProgress(1f)
        return PreparedVariant(lead = lead, variant = variant)
    }

    @Deprecated("Use prepareInstrumentVariants")
    suspend fun preparePianoVariants(
        dependency: AiStemDependency,
        performance: AiPerformanceTrack,
        pack: AiInstalledInstrumentPack,
        instrumentConfig: AiInstrumentRenderConfig = AiInstrumentRenderConfig(),
        instrumentPerformanceMix: AiMediaVariantMixConfig =
            AiMediaVariantMixConfig.defaultFor(AiMediaVariantMode.INSTRUMENT_PERFORMANCE),
        vocalEnsembleMix: AiMediaVariantMixConfig =
            AiMediaVariantMixConfig.defaultFor(AiMediaVariantMode.VOCAL_ENSEMBLE),
        onProgress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): PreparedVariantSet = prepareInstrumentVariants(
        dependency, performance, pack, instrumentConfig, instrumentPerformanceMix, vocalEnsembleMix, onProgress, isCancelled
    )

    @Deprecated("Use prepareInstrumentVariant")
    suspend fun preparePianoVariant(
        dependency: AiStemDependency,
        performance: AiPerformanceTrack,
        pack: AiInstalledInstrumentPack,
        mode: AiMediaVariantMode,
        instrumentConfig: AiInstrumentRenderConfig = AiInstrumentRenderConfig(),
        mixConfig: AiMediaVariantMixConfig = AiMediaVariantMixConfig.defaultFor(mode),
        onProgress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): PreparedVariant = prepareInstrumentVariant(
        dependency, performance, pack, mode, instrumentConfig, mixConfig, onProgress, isCancelled
    )

    companion object {
        @Volatile private var instance: AiPerformanceVariantCoordinator? = null
        fun get(context: Context): AiPerformanceVariantCoordinator = instance ?: synchronized(this) {
            instance ?: AiPerformanceVariantCoordinator(context.applicationContext).also { instance = it }
        }
    }
}
