package com.rawsmusic

import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.PlayQueue

internal data class HomeCarouselQueueProjection(
    val songs: List<AudioFile>,
    val currentIndex: Int,
)

/**
 * Builds the carousel from the same policy-resolved neighbours shown by the mini player.
 * Outer lanes remain visual context only; a gesture is committed through next/previous transport.
 */
internal fun buildHomeCarouselQueueProjection(
    queue: PlayQueue,
    currentSong: AudioFile?,
    previousSong: AudioFile?,
    nextSong: AudioFile?,
    relativeSongs: List<AudioFile?> = emptyList(),
): HomeCarouselQueueProjection {
    val source = queue.songs
    val current = currentSong ?: queue.currentSong ?: source.firstOrNull()
        ?: return HomeCarouselQueueProjection(emptyList(), -1)
    val sourceIndex = queue.currentIndex
        .takeIf { it in source.indices && source[it].sameQueueItem(current) }
        ?: source.indexOfFirst { it.sameQueueItem(current) }.takeIf { it >= 0 }
        ?: 0

    fun physical(offset: Int): AudioFile {
        if (source.isEmpty()) return current
        return source[(sourceIndex + offset).floorMod(source.size)]
    }

    val projected = (-3..3).map { offset ->
        relativeSongs.getOrNull(offset + 3)
            ?: when (offset) {
                -1 -> previousSong
                0 -> current
                1 -> nextSong
                else -> null
            }
            ?: physical(offset)
    }
    return HomeCarouselQueueProjection(projected, 3)
}

private fun AudioFile.sameQueueItem(other: AudioFile): Boolean =
    path == other.path &&
        cueOffsetMs == other.cueOffsetMs &&
        cueTrackIndex == other.cueTrackIndex

private fun Int.floorMod(modulus: Int): Int = ((this % modulus) + modulus) % modulus
