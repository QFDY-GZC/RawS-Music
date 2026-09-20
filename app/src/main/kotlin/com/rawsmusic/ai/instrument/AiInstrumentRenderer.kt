package com.rawsmusic.ai.instrument

import com.rawsmusic.ai.melody.AiPerformanceTrack
import java.io.File

data class AiInstrumentRenderConfig(
    val masterGainDb: Float = -6f,
    val attackMs: Float = 3f,
    val releaseMs: Float = 180f,
    val maximumTailMs: Int = 900,
) {
    init {
        require(masterGainDb.isFinite() && masterGainDb in -36f..6f)
        require(attackMs.isFinite() && attackMs in 0f..100f)
        require(releaseMs.isFinite() && releaseMs in 0f..2_000f)
        require(maximumTailMs in 0..5_000)
    }
}

data class AiInstrumentRenderResult(
    val outputFile: File,
    val audioFormat: String,
    val sampleRate: Int,
    val channels: Int,
    val frameCount: Long,
    val renderedNotes: Int,
    val peakBeforeLimiter: Float,
)

interface AiInstrumentRenderer {
    val rendererId: String
    val rendererVersion: String

    fun render(
        performance: AiPerformanceTrack,
        pack: AiInstalledInstrumentPack,
        outputFile: File,
        config: AiInstrumentRenderConfig = AiInstrumentRenderConfig(),
        onProgress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): AiInstrumentRenderResult
}
