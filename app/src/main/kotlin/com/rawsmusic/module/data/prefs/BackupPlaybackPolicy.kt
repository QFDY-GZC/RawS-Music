package com.rawsmusic.module.data.prefs

import com.rawsmusic.core.common.model.AudioFile

internal data class BackupPlaybackResolution(val playback: BackupPlayback, val missingStats: Int, val missingHistory: Int)

/** Statistics are useful only for real local media; daily totals do not depend on a media ID. */
internal object BackupPlaybackPolicy {
    /** Export references only rows whose current local identity is still unambiguous. */
    fun forExport(playback: BackupPlayback, library: List<AudioFile>): BackupPlayback {
        val identities = mutableMapOf<Long, MutableSet<Triple<String, String, String>>>()
        playback.stats.forEach { identities.getOrPut(it.songId) { mutableSetOf() }.add(Triple(it.title, it.artist, it.album)) }
        playback.history.forEach { identities.getOrPut(it.songId) { mutableSetOf() }.add(Triple(it.title, it.artist, it.album)) }
        val paths = library.groupBy { it.id }.mapNotNull { (id, candidates) ->
            val song = candidates.singleOrNull() ?: return@mapNotNull null
            if (song.path.isBlank() || identities[id] != setOf(Triple(song.title, song.artist, song.album))) null
            else id to song.path
        }.toMap()
        return playback.copy(paths = paths)
    }

    fun resolve(playback: BackupPlayback, library: List<AudioFile>, readable: (String) -> Boolean): BackupPlaybackResolution {
        val byId = library.groupBy { it.id }
        val byPath = library.groupBy { it.path }
        val readablePaths = mutableMapOf<String, Boolean>()
        fun find(id: Long, title: String, artist: String, album: String): AudioFile? {
            val path = playback.paths[id]
            val song = if (path != null) byPath[path]?.singleOrNull() else byId[id]?.singleOrNull()?.takeIf {
                it.title == title && it.artist == artist && it.album == album
            }
            return song?.takeIf { it.path.isNotBlank() && readablePaths.getOrPut(it.path) { readable(it.path) } }
        }
        val resolvedStats = playback.stats.mapNotNull { stat -> find(stat.songId, stat.title, stat.artist, stat.album)?.let {
            stat.copy(songId = it.id, title = it.title, artist = it.artist, album = it.album)
        } }
        // Multiple old IDs converging on one file are ambiguous, not last-write-wins totals.
        val duplicates = resolvedStats.groupingBy { it.songId }.eachCount().filterValues { it > 1 }.keys
        val stats = resolvedStats.filterNot { it.songId in duplicates }
        val history = playback.history.mapNotNull { row -> find(row.songId, row.title, row.artist, row.album)?.takeIf { it.id !in duplicates }?.let {
            row.copy(songId = it.id, title = it.title, artist = it.artist, album = it.album)
        } }
        val retainedIds = (stats.map { it.songId } + history.map { it.songId }).toSet()
        val paths = library.filter { it.id in retainedIds }.associate { it.id to it.path }
        return BackupPlaybackResolution(playback.copy(stats = stats, history = history, paths = if (playback.paths.isEmpty()) emptyMap() else paths), playback.stats.size - stats.size, playback.history.size - history.size)
    }
}
