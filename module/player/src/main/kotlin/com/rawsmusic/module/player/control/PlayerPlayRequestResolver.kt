package com.rawsmusic.module.player.control

import com.rawsmusic.core.common.model.AudioFile

internal data class ResolvedPlayRequest(
    val queue: List<AudioFile>,
    val index: Int,
)

/** Pure queue/identity policy used before a transport play request reaches decoder ownership. */
internal class PlayerPlayRequestResolver(
    private val logWarning: (String) -> Unit,
) {
    fun sameItem(left: AudioFile, right: AudioFile): Boolean =
        left.path == right.path &&
            left.cueOffsetMs == right.cueOffsetMs &&
            left.cueTrackIndex == right.cueTrackIndex

    fun sameQueue(left: List<AudioFile>, right: List<AudioFile>): Boolean {
        if (left.size != right.size) return false
        return left.indices.all { index -> sameItem(left[index], right[index]) }
    }

    fun resolve(
        song: AudioFile,
        requestedQueue: List<AudioFile>,
        requestedIndex: Int,
        currentQueue: List<AudioFile>,
    ): ResolvedPlayRequest {
        if (requestedQueue.isNotEmpty()) {
            val safeIndex = requestedIndex.coerceIn(0, requestedQueue.lastIndex)
            if (sameItem(requestedQueue[safeIndex], song)) {
                return ResolvedPlayRequest(requestedQueue, safeIndex)
            }
            val identityIndex = requestedQueue.indexOfFirst { sameItem(it, song) }
            if (identityIndex >= 0) {
                logWarning(
                    "play queue index corrected: requested=$requestedIndex resolved=$identityIndex " +
                        "title=${song.title} queueSize=${requestedQueue.size}"
                )
                return ResolvedPlayRequest(requestedQueue, identityIndex)
            }
            logWarning(
                "play queue snapshot does not contain requested item; isolating selection " +
                    "title=${song.title} path=${song.path} requested=$requestedIndex " +
                    "queueSize=${requestedQueue.size}"
            )
            return ResolvedPlayRequest(listOf(song), 0)
        }

        val existingIndex = currentQueue.indexOfFirst { sameItem(it, song) }
        return if (existingIndex >= 0) {
            ResolvedPlayRequest(currentQueue, existingIndex)
        } else {
            ResolvedPlayRequest(listOf(song), 0)
        }
    }

    fun shouldUseManualSwitch(
        currentSong: AudioFile?,
        requestedSong: AudioFile,
        controllerPlaying: Boolean,
        enginePlaying: Boolean,
        usbExclusiveActive: Boolean,
        configuredManualFadeMs: Int,
    ): Boolean {
        val current = currentSong ?: return false
        if (sameItem(current, requestedSong)) return false
        if (!controllerPlaying && !enginePlaying) return false
        return usbExclusiveActive || configuredManualFadeMs > 0
    }
}
