package com.rawsmusic.separation

import android.content.Context
import com.rawsmusic.core.common.model.AudioFile
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.security.MessageDigest

/**
 * Reusable, immutable stem dependency produced by an offline separation result.
 *
 * Playback, melody extraction and future AI media-variant generation should depend on this
 * contract rather than on the separation settings/result UI. The dependency fingerprint changes
 * whenever the source identity or the concrete separation result changes, so downstream caches
 * can invalidate without re-running source separation unnecessarily.
 */
data class AiStemDependency(
    val dependencyFingerprint: String,
    val separationResultId: String,
    val sourceFingerprint: String,
    val sourceName: String,
    val modelId: String,
    val modelVersion: String,
    val sampleRate: Int,
    val totalFrames: Long,
    val createdAtEpochMs: Long,
    val outputFormat: String,
    val vocalsFile: File,
    val instrumentalFile: File,
) {
    val durationMs: Long
        get() = if (sampleRate > 0) totalFrames * 1000L / sampleRate else 0L

    fun fileFor(stem: AiSeparationStem): File = when (stem) {
        AiSeparationStem.VOCALS -> vocalsFile
        AiSeparationStem.INSTRUMENTAL -> instrumentalFile
    }

    fun isUsable(): Boolean =
        sampleRate > 0 && totalFrames > 0L &&
            vocalsFile.isFile && vocalsFile.length() > MIN_STEM_BYTES &&
            instrumentalFile.isFile && instrumentalFile.length() > MIN_STEM_BYTES

    companion object {
        private const val MIN_STEM_BYTES = 44L

        internal fun from(song: AudioFile, result: AiSeparationResult): AiStemDependency {
            val sourceFingerprint = song.stableAudioFingerprint()
            return from(result, sourceFingerprint)
        }

        internal fun from(
            result: AiSeparationResult,
            sourceFingerprint: String,
        ): AiStemDependency {
            val vocals = result.vocalsFile
            val instrumental = result.instrumentalFile
            val dependencyFingerprint = sha256(
                buildString {
                    append("rawsmusic-ai-stems-v1|")
                    append(sourceFingerprint).append('|')
                    append(result.id).append('|')
                    append(result.modelId).append('|')
                    append(result.modelVersion).append('|')
                    append(result.sampleRate).append('|')
                    append(result.totalFrames).append('|')
                    append(result.outputFormat.lowercase()).append('|')
                    append(vocals.length()).append('|')
                    append(vocals.lastModified()).append('|')
                    append(instrumental.length()).append('|')
                    append(instrumental.lastModified())
                }
            )
            return AiStemDependency(
                dependencyFingerprint = dependencyFingerprint,
                separationResultId = result.id,
                sourceFingerprint = sourceFingerprint,
                sourceName = result.sourceName,
                modelId = result.modelId,
                modelVersion = result.modelVersion,
                sampleRate = result.sampleRate,
                totalFrames = result.totalFrames,
                createdAtEpochMs = result.createdAtEpochMs,
                outputFormat = result.outputFormat,
                vocalsFile = vocals,
                instrumentalFile = instrumental,
            )
        }

        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }
}

/**
 * Selection hint for consumers that prefer stems produced by one model revision but can still
 * reuse another valid completed result instead of forcing a second separation pass.
 */
data class AiStemDependencyRequirement(
    val preferredModelId: String = "",
    val preferredModelVersion: String = "",
)

internal object AiStemDependencyPolicy {
    fun select(
        candidates: List<AiStemDependency>,
        requirement: AiStemDependencyRequirement,
    ): AiStemDependency? {
        return candidates
            .asSequence()
            .filter(AiStemDependency::isUsable)
            .sortedWith(
                compareByDescending<AiStemDependency> {
                    modelPreferenceScore(it, requirement)
                }.thenByDescending { it.createdAtEpochMs }
                    .thenByDescending { it.separationResultId }
            )
            .firstOrNull()
    }

    private fun modelPreferenceScore(
        dependency: AiStemDependency,
        requirement: AiStemDependencyRequirement,
    ): Int {
        if (requirement.preferredModelId.isBlank()) return 0
        if (dependency.modelId != requirement.preferredModelId) return 0
        if (requirement.preferredModelVersion.isBlank()) return 1
        return if (dependency.modelVersion == requirement.preferredModelVersion) 2 else 1
    }
}

/**
 * Single resolution point for reusable AI stems.
 *
 * This deliberately owns no player behavior. It converts persisted separation results into a
 * stable dependency that any later AI stage can consume. The original source fingerprint and
 * the concrete result id are both part of the dependency fingerprint, mirroring a media-variant
 * cache: reusing the same stems is cheap, while a changed source or regenerated stems naturally
 * invalidates downstream work.
 */
class AiStemDependencyResolver private constructor(context: Context) {
    private val resultStore = AiSeparationResultStore.get(context.applicationContext)

    /** Used by observers to re-resolve when a separation result is committed or removed. */
    val resultChanges: StateFlow<List<AiSeparationResult>>
        get() = resultStore.results

    fun resolve(
        song: AudioFile,
        requirement: AiStemDependencyRequirement = AiStemDependencyRequirement(),
    ): AiStemDependency? {
        val candidates = resultStore.findCandidatesFor(song)
            .asSequence()
            .filter { resultStore.isExactMatch(song, it) }
            .map { result -> AiStemDependency.from(song, result) }
            .toList()
        return AiStemDependencyPolicy.select(candidates, requirement)
    }

    companion object {
        @Volatile private var instance: AiStemDependencyResolver? = null

        fun get(context: Context): AiStemDependencyResolver = instance ?: synchronized(this) {
            instance ?: AiStemDependencyResolver(context).also { instance = it }
        }
    }
}
