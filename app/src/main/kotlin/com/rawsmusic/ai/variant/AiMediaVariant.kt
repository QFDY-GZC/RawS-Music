package com.rawsmusic.ai.variant

import com.rawsmusic.core.common.model.AudioFile
import java.io.File

/**
 * Offline AI media variants keep the semantic library identity of the original song.
 * Only the playback source path changes once a generated artifact is ready.
 */
enum class AiMediaVariantMode {
    /** Instrument lead replaces the vocal role while the separated instrumental remains. */
    INSTRUMENT_PERFORMANCE,

    /** Original vocal + separated instrumental + generated instrument lead. */
    VOCAL_ENSEMBLE,
}

data class AiMediaVariantMixConfig(
    val vocalGainDb: Float,
    val instrumentalGainDb: Float,
    val leadGainDb: Float,
    val outputCeilingDb: Float = -1f,
) {
    init {
        require(vocalGainDb.isFinite() && vocalGainDb in -48f..12f)
        require(instrumentalGainDb.isFinite() && instrumentalGainDb in -48f..12f)
        require(leadGainDb.isFinite() && leadGainDb in -48f..12f)
        require(outputCeilingDb.isFinite() && outputCeilingDb in -12f..-0.1f)
    }

    companion object {
        fun defaultFor(mode: AiMediaVariantMode): AiMediaVariantMixConfig = when (mode) {
            AiMediaVariantMode.INSTRUMENT_PERFORMANCE -> AiMediaVariantMixConfig(
                vocalGainDb = -48f,
                instrumentalGainDb = -3f,
                leadGainDb = 0f,
            )
            AiMediaVariantMode.VOCAL_ENSEMBLE -> AiMediaVariantMixConfig(
                vocalGainDb = -1f,
                instrumentalGainDb = -4f,
                leadGainDb = -2f,
            )
        }
    }
}

data class AiMediaVariantArtifact(
    val fingerprint: String,
    val sourceFingerprint: String,
    val stemDependencyFingerprint: String,
    val leadFingerprint: String,
    val mode: AiMediaVariantMode,
    val packId: String,
    val packVersion: String,
    val rendererId: String,
    val rendererVersion: String,
    val mixConfig: AiMediaVariantMixConfig,
    val mixerVersion: String,
    val audioFormat: String,
    val sampleRate: Int,
    val channels: Int,
    val frameCount: Long,
    val peakBeforeNormalization: Float,
    val normalizationGainDb: Float,
    val createdAtEpochMs: Long,
    val audioFile: File,
) {
    val durationMs: Long
        get() = if (sampleRate > 0) frameCount * 1000L / sampleRate else 0L
}

data class AiMediaVariantSelection(
    val mode: AiMediaVariantMode,
    val packId: String = "",
    val packVersion: String = "",
)

data class AiResolvedMediaVariant(
    val originalSong: AudioFile,
    val artifact: AiMediaVariantArtifact,
) {
    /** Playback may use this path while UI/queue/history keep [originalSong] as the identity. */
    val playbackPath: String get() = artifact.audioFile.absolutePath
}
