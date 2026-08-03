package com.rawsmusic.module.player

import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.data.repository.MusicRepository
import org.json.JSONArray
import org.json.JSONObject

internal data class RestoredPlayerState(
    val song: AudioFile,
    val queue: List<AudioFile>,
    val queueIndex: Int,
    val positionMs: Long,
    val source: String,
    val repositorySongCount: Int
)

/** Owns the persisted representation of playback state and its cold-start hydration. */
internal class PlayerStatePersistence {
    private data class SongIdentity(
        val path: String,
        val cueOffsetMs: Long,
        val cueTrackIndex: Int,
    )

    fun saveSongSnapshot(song: AudioFile) {
        AppPreferences.Player.lastSongId = song.id
        AppPreferences.Player.lastSongPath = song.path
        AppPreferences.Player.lastSongTitle = song.title
        AppPreferences.Player.lastSongArtist = song.artist
        AppPreferences.Player.lastSongAlbum = song.album
        AppPreferences.Player.lastSongAlbumArtPath = song.albumArtPath
        AppPreferences.Player.lastSongDuration = song.duration
        AppPreferences.Player.lastSongAlbumId = song.albumId
    }

    fun saveRuntime(playStateOrdinal: Int, keepUsbExclusive: Boolean) {
        AppPreferences.Player.lastPlayStateOrdinal = playStateOrdinal
        AppPreferences.Player.lastUsbExclusiveActive =
            AppPreferences.Player.lastUsbExclusiveActive || keepUsbExclusive
    }

    fun savePosition(positionMs: Long) {
        AppPreferences.Player.lastPosition = if (AppPreferences.Player.trackProgressMemoryEnabled) {
            positionMs.coerceAtLeast(0L)
        } else {
            0L
        }
    }

    fun saveQueue(songs: List<AudioFile>, currentIndex: Int) {
        AppPreferences.Player.currentQueueIndex = currentIndex
        AppPreferences.Player.playQueueSongsJson = encodeSongList(songs)
    }

    fun encodeSongList(songs: List<AudioFile>): String = encodeSongs(songs)

    fun decodeSongList(json: String): List<AudioFile> = decodeSongs(
        json = json,
        songsByIdentity = emptyMap(),
        songsById = emptyMap(),
        songsByPath = emptyMap()
    )

    fun restore(): RestoredPlayerState? {
        val lastPath = AppPreferences.Player.lastSongPath
        if (lastPath.isBlank()) return null

        val repositorySongs = MusicRepository.songs.value
        val songsByIdentity = if (repositorySongs.isNotEmpty()) {
            repositorySongs.associateBy(::identityOf)
        } else {
            emptyMap()
        }
        val songsById = if (repositorySongs.isNotEmpty()) repositorySongs.associateBy { it.id } else emptyMap()
        val songsByPath = if (repositorySongs.isNotEmpty()) repositorySongs.associateBy { it.path } else emptyMap()
        val lastId = AppPreferences.Player.lastSongId
        val restoredQueue = decodeSongs(
            json = AppPreferences.Player.playQueueSongsJson,
            songsByIdentity = songsByIdentity,
            songsById = songsById,
            songsByPath = songsByPath
        )
        val savedQueueIndex = AppPreferences.Player.currentQueueIndex
        val indexedQueueSong = restoredQueue.getOrNull(savedQueueIndex)
            ?.takeIf { it.path == lastPath }
        val repositorySong = indexedQueueSong ?:
            (if (lastId != -1L) songsById[lastId] else null) ?:
            songsByPath[lastPath]
        val song = repositorySong ?: AudioFile(
            id = lastId,
            path = lastPath,
            title = AppPreferences.Player.lastSongTitle,
            artist = AppPreferences.Player.lastSongArtist,
            album = AppPreferences.Player.lastSongAlbum,
            albumId = AppPreferences.Player.lastSongAlbumId,
            duration = AppPreferences.Player.lastSongDuration,
            albumArtPath = AppPreferences.Player.lastSongAlbumArtPath
        )
        val decodedQueue = restoredQueue.ifEmpty { listOf(song) }
        val exactSongIndex = decodedQueue.indexOfFirst { identityOf(it) == identityOf(song) }
        val restoredIndex = exactSongIndex.takeIf { it >= 0 }
            ?: savedQueueIndex.coerceIn(0, decodedQueue.lastIndex)
        val hydratedQueue = if (exactSongIndex >= 0) {
            decodedQueue
        } else {
            decodedQueue.toMutableList().apply {
                this[restoredIndex] = song
            }
        }
        val positionMs = if (AppPreferences.Player.trackProgressMemoryEnabled) {
            AppPreferences.Player.lastPosition.coerceAtLeast(0L)
        } else {
            0L
        }
        return RestoredPlayerState(
            song = song,
            queue = hydratedQueue,
            queueIndex = restoredIndex,
            positionMs = positionMs,
            source = if (repositorySong != null) "repository_state" else "preference_snapshot",
            repositorySongCount = repositorySongs.size
        )
    }

    private fun encodeSongs(songs: List<AudioFile>): String {
        val array = JSONArray()
        songs.forEach { song ->
            array.put(JSONObject().apply {
                put("id", song.id)
                put("path", song.path)
                put("title", song.title)
                put("artist", song.artist)
                put("album", song.album)
                put("albumId", song.albumId)
                put("duration", song.duration)
                put("albumArtPath", song.albumArtPath)
                put("cueOffsetMs", song.cueOffsetMs)
                put("cueEndMs", song.cueEndMs)
                put("cueTrackIndex", song.cueTrackIndex)
            })
        }
        return array.toString()
    }

    private fun decodeSongs(
        json: String,
        songsByIdentity: Map<SongIdentity, AudioFile>,
        songsById: Map<Long, AudioFile>,
        songsByPath: Map<String, AudioFile>
    ): List<AudioFile> {
        if (json.isBlank()) return emptyList()
        return try {
            val array = JSONArray(json)
            buildList {
                for (index in 0 until array.length()) {
                    val item = try {
                        val obj = array.getJSONObject(index)
                        val path = obj.optString("path", "")
                        if (path.isBlank()) {
                            null
                        } else {
                            val id = obj.optLong("id", -1L)
                            val cueOffsetMs = obj.optLong("cueOffsetMs", 0L)
                            val cueTrackIndex = obj.optInt("cueTrackIndex", 0)
                            val exact = songsByIdentity[
                                SongIdentity(path, cueOffsetMs, cueTrackIndex)
                            ]
                            val legacy = if (cueOffsetMs == 0L && cueTrackIndex == 0) {
                                (if (id != -1L) songsById[id] else null) ?: songsByPath[path]
                            } else {
                                null
                            }
                            exact ?: legacy ?: AudioFile(
                                id = id,
                                path = path,
                                title = obj.optString("title", ""),
                                artist = obj.optString("artist", ""),
                                album = obj.optString("album", ""),
                                albumId = obj.optLong("albumId", -1L),
                                duration = obj.optLong("duration", 0L),
                                albumArtPath = obj.optString("albumArtPath", ""),
                                cueOffsetMs = cueOffsetMs,
                                cueEndMs = obj.optLong("cueEndMs", 0L),
                                cueTrackIndex = cueTrackIndex
                            )
                        }
                    } catch (_: Exception) {
                        null
                    }
                    if (item != null) add(item)
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun identityOf(song: AudioFile): SongIdentity =
        SongIdentity(song.path, song.cueOffsetMs, song.cueTrackIndex)
}
