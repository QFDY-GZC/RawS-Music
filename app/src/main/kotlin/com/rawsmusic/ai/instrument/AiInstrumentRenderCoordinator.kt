package com.rawsmusic.ai.instrument

import android.content.Context
import com.rawsmusic.ai.melody.AiPerformanceTrack

/** Thin orchestration boundary for Step 3. The playback/media-variant layer is intentionally absent. */
class AiInstrumentRenderCoordinator private constructor(context: Context) {
    private val leadStore = AiInstrumentLeadStore.get(context)

    suspend fun renderInstrumentLead(
        performance: AiPerformanceTrack,
        pack: AiInstalledInstrumentPack,
        config: AiInstrumentRenderConfig = AiInstrumentRenderConfig(),
        onProgress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): AiInstrumentLeadArtifact = leadStore.renderOrLoad(
        performance = performance,
        pack = pack,
        renderer = SampledPianoRenderer(),
        config = config,
        onProgress = onProgress,
        isCancelled = isCancelled,
    )

    @Deprecated("Use renderInstrumentLead", ReplaceWith("renderInstrumentLead(performance, pack, config, onProgress, isCancelled)"))
    suspend fun renderPianoLead(
        performance: AiPerformanceTrack,
        pack: AiInstalledInstrumentPack,
        config: AiInstrumentRenderConfig = AiInstrumentRenderConfig(),
        onProgress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): AiInstrumentLeadArtifact = renderInstrumentLead(performance, pack, config, onProgress, isCancelled)

    companion object {
        @Volatile private var instance: AiInstrumentRenderCoordinator? = null
        fun get(context: Context): AiInstrumentRenderCoordinator = instance ?: synchronized(this) {
            instance ?: AiInstrumentRenderCoordinator(context.applicationContext).also { instance = it }
        }
    }
}
