package com.rawsmusic.ai.variant

import android.content.Context
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.module.player.PlaybackSourceSwitchResult
import com.rawsmusic.module.player.PlayerController
import java.util.concurrent.atomic.AtomicLong

/**
 * App-layer bridge from an AI media artifact to the player's generic source-override primitive.
 *
 * No AI type crosses into module:player. The player keeps [AudioFile] as semantic identity while
 * this coordinator supplies only a prepared, playback-ready local source and its renderer hints.
 */
internal object AiMediaVariantPlaybackPolicy {
    fun supportsSong(song: AudioFile): Boolean =
        song.cueTrackIndex <= 0 && song.cueOffsetMs <= 0L && song.cueEndMs <= 0L

    fun sourceTag(artifact: AiMediaVariantArtifact): String =
        "ai_variant:${artifact.mode.name.lowercase()}:${artifact.fingerprint.take(12)}"
}

class AiMediaVariantPlaybackCoordinator private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = AiMediaVariantResolver.get(appContext)
    private val requestGeneration = AtomicLong(0L)

    suspend fun switchToVariant(
        song: AudioFile,
        selection: AiMediaVariantSelection,
    ): AiMediaVariantPlaybackResult {
        val generation = requestGeneration.incrementAndGet()
        if (!AiMediaVariantPlaybackPolicy.supportsSong(song)) {
            return AiMediaVariantPlaybackResult.UnsupportedCue
        }
        val resolved = resolver.resolve(song, selection)
            ?: return AiMediaVariantPlaybackResult.VariantNotReady
        if (generation != requestGeneration.get()) return AiMediaVariantPlaybackResult.Superseded
        val controller = PlayerController.getInstanceOrNull()
            ?: return AiMediaVariantPlaybackResult.PlayerUnavailable
        val artifact = resolved.artifact
        if (generation != requestGeneration.get()) return AiMediaVariantPlaybackResult.Superseded
        val switch = controller.switchCurrentPlaybackSource(
            expectedSong = resolved.originalSong,
            playbackPath = resolved.playbackPath,
            sourceTag = AiMediaVariantPlaybackPolicy.sourceTag(artifact),
            sampleRate = artifact.sampleRate,
            channels = artifact.channels,
            bitsPerSample = 16,
            format = artifact.audioFormat.uppercase(),
        )
        return switch.toAiResult(resolved)
    }

    suspend fun switchToOriginal(song: AudioFile): AiMediaVariantPlaybackResult {
        requestGeneration.incrementAndGet()
        val controller = PlayerController.getInstanceOrNull()
            ?: return AiMediaVariantPlaybackResult.PlayerUnavailable
        val switch = controller.switchCurrentPlaybackSource(
            expectedSong = song,
            playbackPath = null,
            sourceTag = "original_source",
        )
        return when (switch) {
            PlaybackSourceSwitchResult.APPLIED,
            PlaybackSourceSwitchResult.ALREADY_ACTIVE -> AiMediaVariantPlaybackResult.OriginalActive
            PlaybackSourceSwitchResult.NO_CURRENT_SONG -> AiMediaVariantPlaybackResult.NoCurrentSong
            PlaybackSourceSwitchResult.STALE_SONG -> AiMediaVariantPlaybackResult.StaleSong
            PlaybackSourceSwitchResult.SOURCE_UNAVAILABLE -> AiMediaVariantPlaybackResult.SourceUnavailable
            PlaybackSourceSwitchResult.PLAYER_RELEASED -> AiMediaVariantPlaybackResult.PlayerUnavailable
        }
    }

    private fun PlaybackSourceSwitchResult.toAiResult(
        resolved: AiResolvedMediaVariant,
    ): AiMediaVariantPlaybackResult = when (this) {
        PlaybackSourceSwitchResult.APPLIED -> AiMediaVariantPlaybackResult.VariantActive(resolved, changed = true)
        PlaybackSourceSwitchResult.ALREADY_ACTIVE -> AiMediaVariantPlaybackResult.VariantActive(resolved, changed = false)
        PlaybackSourceSwitchResult.NO_CURRENT_SONG -> AiMediaVariantPlaybackResult.NoCurrentSong
        PlaybackSourceSwitchResult.STALE_SONG -> AiMediaVariantPlaybackResult.StaleSong
        PlaybackSourceSwitchResult.SOURCE_UNAVAILABLE -> AiMediaVariantPlaybackResult.SourceUnavailable
        PlaybackSourceSwitchResult.PLAYER_RELEASED -> AiMediaVariantPlaybackResult.PlayerUnavailable
    }

    companion object {
        @Volatile private var instance: AiMediaVariantPlaybackCoordinator? = null

        fun get(context: Context): AiMediaVariantPlaybackCoordinator = instance ?: synchronized(this) {
            instance ?: AiMediaVariantPlaybackCoordinator(context.applicationContext).also { instance = it }
        }
    }
}

sealed interface AiMediaVariantPlaybackResult {
    data class VariantActive(
        val resolved: AiResolvedMediaVariant,
        val changed: Boolean,
    ) : AiMediaVariantPlaybackResult

    data object OriginalActive : AiMediaVariantPlaybackResult
    data object VariantNotReady : AiMediaVariantPlaybackResult
    data object UnsupportedCue : AiMediaVariantPlaybackResult
    data object NoCurrentSong : AiMediaVariantPlaybackResult
    data object StaleSong : AiMediaVariantPlaybackResult
    data object SourceUnavailable : AiMediaVariantPlaybackResult
    data object PlayerUnavailable : AiMediaVariantPlaybackResult
    data object Superseded : AiMediaVariantPlaybackResult
}
