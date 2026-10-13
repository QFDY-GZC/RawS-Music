package com.rawsmusic

import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.PlayQueue

internal data class HomeCarouselQueueProjection(
    val songs: List<AudioFile>,
    val currentIndex: Int,
)

/**
 * Builds the carousel from the same policy-resolved neighbours shown by the mini player.
 * This is a finite policy-resolved preview, not a circular copy of the physical queue.
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

    if (relativeSongs.isNotEmpty() && relativeSongs.any { it != null }) {
        val center = relativeSongs.size / 2
        // A null at an exhausted traversal boundary is meaningful. Never replace it with the
        // physical library order: doing so invents neighbours that are not in shuffle traversal.
        var first = center
        var last = center
        while (first > 0 && relativeSongs[first - 1] != null) first--
        while (last < relativeSongs.lastIndex && relativeSongs[last + 1] != null) last++
        return HomeCarouselQueueProjection(
            (first..last).map { if (it == center) current else requireNotNull(relativeSongs[it]) },
            center - first,
        )
    }
    val radius = com.rawsmusic.core.ui.scene.pages.HOME_CAROUSEL_PREVIEW_RADIUS
    val projected = (-radius..radius).map { offset ->
        when (offset) {
            -1 -> previousSong
            0 -> current
            1 -> nextSong
            else -> null
        } ?: physical(offset)
    }
    return HomeCarouselQueueProjection(projected, radius)
}

private fun AudioFile.sameQueueItem(other: AudioFile): Boolean =
    path == other.path &&
        cueOffsetMs == other.cueOffsetMs &&
        cueTrackIndex == other.cueTrackIndex

private fun Int.floorMod(modulus: Int): Int = ((this % modulus) + modulus) % modulus
