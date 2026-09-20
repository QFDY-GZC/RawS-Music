package com.rawsmusic.core.ui.widget.bitmaps

import com.rawsmusic.core.common.model.AudioFile

/**
 * Resolves the queue position from the committed song identity and the same queue snapshot.
 *
 * PlayerController publishes currentSong and PlayQueue on separate StateFlows. Compose can therefore
 * observe the new song on one frame and the old queue.currentIndex on the next frame. Album-art
 * motion must not interpret that transient pair as a same-position metadata refresh: Reference starts
 * its ArtworkPagerMotion/ArtworkPager programmatic move from the committed current-track item, not from two independently
 * sampled fields.
 *
 * The exact audio/CUE identity wins when it is present in [queueSongs]. [reportedIndex] remains a
 * fallback for priority/virtual items that are intentionally outside that list snapshot.
 */
fun resolvePlaybackQueueBindingIndex(
    currentSong: AudioFile?,
    queueSongs: List<AudioFile>,
    reportedIndex: Int,
): Int {
    if (currentSong != null) {
        val exactIndex = queueSongs.indexOfFirst { candidate ->
            candidate.path == currentSong.path &&
                candidate.cueOffsetMs == currentSong.cueOffsetMs &&
                candidate.cueTrackIndex == currentSong.cueTrackIndex
        }
        if (exactIndex >= 0) return exactIndex
    }
    return reportedIndex.takeIf { it in queueSongs.indices } ?: -1
}
