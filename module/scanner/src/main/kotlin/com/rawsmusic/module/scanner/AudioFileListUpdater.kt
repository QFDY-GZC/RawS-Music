package com.rawsmusic.module.scanner

import com.rawsmusic.core.common.model.AudioFile

object AudioFileListUpdater {

    fun applyEnrichedResult(
        current: List<AudioFile>,
        originalSongId: Long,
        originalPath: String,
        enrichedSongs: List<AudioFile>
    ): List<AudioFile> {
        if (current.isEmpty()) return enrichedSongs

        val index = current.indexOfFirst { song ->
            song.id == originalSongId || song.path == originalPath
        }

        if (index < 0) return current + enrichedSongs

        val result = current.toMutableList()
        result.removeAt(index)
        if (enrichedSongs.isNotEmpty()) result.addAll(index, enrichedSongs)
        return result
    }

    fun replaceAllKeepingOrder(oldList: List<AudioFile>, newList: List<AudioFile>): List<AudioFile> {
        if (oldList.isEmpty()) return newList
        if (newList.isEmpty()) return oldList
        val newByPath = newList.associateBy { it.path }
        return oldList.map { old -> newByPath[old.path] ?: old }
    }
}
