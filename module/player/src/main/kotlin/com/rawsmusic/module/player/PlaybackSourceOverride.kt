package com.rawsmusic.module.player

import com.rawsmusic.core.common.model.AudioFile
import java.io.File

/**
 * Physical decoder source override for one semantic library item.
 *
 * The queue/currentSong identity remains the original [AudioFile]. Only the renderer source and
 * renderer format hints are replaced. This keeps lyrics, artwork, history and queue ownership on
 * the library item while allowing a prepared local media variant to be decoded instead.
 */
data class PlaybackSourceOverride(
    val semanticPath: String,
    val cueOffsetMs: Long,
    val cueTrackIndex: Int,
    val playbackPath: String,
    val sourceTag: String,
    val sampleRate: Int = 0,
    val channels: Int = 0,
    val bitsPerSample: Int = 0,
    val format: String = "",
    val generation: Long = 0L,
) {
    fun matches(song: AudioFile): Boolean =
        semanticPath == song.path &&
            cueOffsetMs == song.cueOffsetMs &&
            cueTrackIndex == song.cueTrackIndex

    fun rendererSong(song: AudioFile): AudioFile {
        if (!matches(song)) return song
        return song.copy(
            path = playbackPath,
            sampleRate = sampleRate.takeIf { it > 0 } ?: song.sampleRate,
            channelCount = channels.takeIf { it > 0 } ?: song.channelCount,
            bitsPerSample = bitsPerSample.takeIf { it > 0 } ?: song.bitsPerSample,
            format = format.ifBlank { song.format },
            encodingFormat = format.ifBlank { song.encodingFormat },
            cueOffsetMs = 0L,
            cueEndMs = 0L,
            cueTrackIndex = 0,
        )
    }

    fun sourceExists(): Boolean {
        if (playbackPath.startsWith("http://") || playbackPath.startsWith("https://")) return true
        return playbackPath.isNotBlank() && File(playbackPath).isFile
    }
}

enum class PlaybackSourceSwitchResult {
    APPLIED,
    ALREADY_ACTIVE,
    NO_CURRENT_SONG,
    STALE_SONG,
    SOURCE_UNAVAILABLE,
    PLAYER_RELEASED,
}

internal object PlaybackSourceOverridePolicy {
    fun normalizeForSong(
        song: AudioFile,
        playbackPath: String?,
        sourceTag: String,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int,
        format: String,
        generation: Long,
    ): PlaybackSourceOverride? {
        val target = playbackPath?.trim().orEmpty()
        if (target.isBlank() || target == song.path) return null
        return PlaybackSourceOverride(
            semanticPath = song.path,
            cueOffsetMs = song.cueOffsetMs,
            cueTrackIndex = song.cueTrackIndex,
            playbackPath = target,
            sourceTag = sourceTag.trim().take(96),
            sampleRate = sampleRate.coerceAtLeast(0),
            channels = channels.coerceAtLeast(0),
            bitsPerSample = bitsPerSample.coerceAtLeast(0),
            format = format.trim(),
            generation = generation,
        )
    }

    fun effectivePath(song: AudioFile, override: PlaybackSourceOverride?): String =
        override?.takeIf { it.matches(song) }?.playbackPath ?: song.path

    fun rendererSong(song: AudioFile, override: PlaybackSourceOverride?): AudioFile =
        override?.takeIf { it.matches(song) }?.rendererSong(song) ?: song

    fun sameTarget(
        left: PlaybackSourceOverride?,
        right: PlaybackSourceOverride?,
    ): Boolean {
        if (left == null || right == null) return left == null && right == null
        return left.semanticPath == right.semanticPath &&
            left.cueOffsetMs == right.cueOffsetMs &&
            left.cueTrackIndex == right.cueTrackIndex &&
            left.playbackPath == right.playbackPath &&
            left.sampleRate == right.sampleRate &&
            left.channels == right.channels &&
            left.bitsPerSample == right.bitsPerSample &&
            left.format.equals(right.format, ignoreCase = true)
    }
}
