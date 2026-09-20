package com.rawsmusic.separation

import java.io.File

/**
 * Model selection policy for the lightweight lyric alignment pass.
 *
 * The fast pass is deliberately separate from the high-quality offline separation selection.
 * A Spleeter 2-stem FP16 package wins when its published contract can be executed by the current
 * native bridge. Until such a package is installed, a small compatible two-stem model is used as
 * a fallback. RoFormer and other high-memory packages are never selected for this pass.
 */
object AiFastVocalAlignmentModel {
    // Bump when activity interpretation changes so stale timing maps are not reused.
    const val ANALYZER_VERSION = "fast-vocal-activity-v2"
    const val PREFERRED_ID_PREFIX = "spleeter.2stem"
    const val PREFERRED_ARCHITECTURE = "spleeter"

    /**
     * Runtime contract for the standalone sherpa-onnx Spleeter graph. The graph accepts a
     * magnitude STFT, not the complex mask used by the older catalog models. A single split is
     * intentionally used here because this pass only needs a vocal activity curve.
     */
    fun spleeterActivityContract(): AiSeparationModelContract = AiSeparationModelContract(
        task = "spleeter_vocal_activity",
        tensorLayout = "channel_split_time_frequency",
        tensorDataType = "float32",
        inputName = "x",
        outputName = "y",
        inputShape = listOf(2L, 1L, 512L, 1024L),
        outputShape = listOf(2L, 1L, 512L, 1024L),
        fftSize = 4096,
        hopLength = 1024,
        frequencyBins = 1024,
        timeFrames = 512,
        window = "hann",
        center = false,
        paddingMode = "constant",
        normalization = "none",
        outputType = "spectrum",
        intraOpThreads = 1,
    )

    fun isCandidate(entry: AiSeparationCatalogEntry): Boolean {
        val contract = entry.contract ?: return false
        if (contract.task != "vocals_2stem") return false
        if (entry.modelFormat !in setOf("onnx", "ort")) return false
        if (entry.estimatedMemoryMb > MAX_MEMORY_MB) return false
        return !isHighQualityOffline(entry)
    }

    fun isPreferred(entry: AiSeparationCatalogEntry): Boolean =
        isCandidate(entry) && (
            entry.id.startsWith(PREFERRED_ID_PREFIX, ignoreCase = true) ||
                entry.architecture.contains(PREFERRED_ARCHITECTURE, ignoreCase = true) ||
                entry.name.contains("Spleeter", ignoreCase = true)
            ) && entry.modelFormat in setOf("onnx", "ort")

    fun resolve(installed: List<AiSeparationInstalledModel>): AiSeparationInstalledModel? =
        installed
            .filter { isCandidate(it.catalog) && File(it.directory, it.catalog.modelFile).isFile }
            .sortedWith(
                compareBy<AiSeparationInstalledModel> {
                    if (isPreferred(it.catalog)) 0 else 1
                }.thenBy { it.catalog.estimatedMemoryMb }
                    .thenBy { it.catalog.name },
            )
            .firstOrNull()

    private fun isHighQualityOffline(entry: AiSeparationCatalogEntry): Boolean =
        entry.architecture.contains("roformer", ignoreCase = true) ||
            entry.name.contains("roformer", ignoreCase = true) ||
            entry.id.contains("roformer", ignoreCase = true)

    private const val MAX_MEMORY_MB = 768
}
