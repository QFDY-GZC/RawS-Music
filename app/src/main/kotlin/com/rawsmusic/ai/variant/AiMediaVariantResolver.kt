package com.rawsmusic.ai.variant

import android.content.Context
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.separation.AiStemDependencyResolver

internal object AiMediaVariantResolutionPolicy {
    fun select(
        candidates: List<AiMediaVariantArtifact>,
        currentStemDependencyFingerprint: String,
        selection: AiMediaVariantSelection,
    ): AiMediaVariantArtifact? = candidates.asSequence()
        .filter { it.stemDependencyFingerprint == currentStemDependencyFingerprint }
        .filter { it.mode == selection.mode }
        .filter { selection.packId.isBlank() || it.packId == selection.packId }
        .filter { selection.packVersion.isBlank() || it.packVersion == selection.packVersion }
        .sortedByDescending { it.createdAtEpochMs }
        .firstOrNull()
}

/**
 * Resolves a generated path without replacing the semantic [AudioFile] identity. This is the
 * boundary a later player-source switch can consume while queue, lyrics, artwork and history
 * continue to refer to the original library item.
 */
class AiMediaVariantResolver private constructor(context: Context) {
    private val stemResolver = AiStemDependencyResolver.get(context.applicationContext)
    private val store = AiMediaVariantStore.get(context.applicationContext)

    suspend fun resolve(
        song: AudioFile,
        selection: AiMediaVariantSelection,
    ): AiResolvedMediaVariant? {
        val dependency = stemResolver.resolve(song) ?: return null
        val candidates = store.listForSource(dependency.sourceFingerprint)
        val artifact = AiMediaVariantResolutionPolicy.select(
            candidates = candidates,
            currentStemDependencyFingerprint = dependency.dependencyFingerprint,
            selection = selection,
        ) ?: return null
        return AiResolvedMediaVariant(song, artifact)
    }

    companion object {
        @Volatile private var instance: AiMediaVariantResolver? = null
        fun get(context: Context): AiMediaVariantResolver = instance ?: synchronized(this) {
            instance ?: AiMediaVariantResolver(context.applicationContext).also { instance = it }
        }
    }
}
