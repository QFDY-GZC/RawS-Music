package com.rawsmusic.module.data.db

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.rawsmusic.core.common.model.Playlist
import com.rawsmusic.core.common.model.PlaylistSong
import com.tencent.mmkv.MMKV

object PlaylistDao {

    private val kv by lazy { MMKV.defaultMMKV() }
    private val gson = Gson()
    private const val KEY_PLAYLISTS = "playlists_data"
    private const val KEY_PLAYLIST_SONGS = "playlist_songs_data"
    private const val KEY_NEXT_PLAYLIST_ID = "next_playlist_id"
    private const val KEY_NEXT_SONG_ID = "next_playlist_song_id"

    private data class PlaylistRecord(
        val id: Long,
        val name: String,
        val coverPath: String? = null,
        val songCount: Int = 0,
        val createDate: Long = System.currentTimeMillis(),
        val updateDate: Long = System.currentTimeMillis(),
        val isSystem: Boolean = false
    )

    private data class PlaylistSongRecord(
        val id: Long,
        val playlistId: Long,
        val audioId: Long,
        val sortOrder: Int = 0,
        val dateAdded: Long = System.currentTimeMillis()
    )

    private var playlistsCache: MutableList<PlaylistRecord>? = null
    private var playlistSongsCache: MutableList<PlaylistSongRecord>? = null

    private fun loadPlaylists(): MutableList<PlaylistRecord> {
        playlistsCache?.let { return it }
        val json = kv.decodeString(KEY_PLAYLISTS, "") ?: ""
        playlistsCache = if (json.isBlank()) {
            mutableListOf()
        } else {
            try {
                val type = object : TypeToken<List<PlaylistRecord>>() {}.type
                gson.fromJson(json, type) ?: mutableListOf()
            } catch (e: Exception) {
                mutableListOf()
            }
        }
        return playlistsCache!!
    }

    private fun savePlaylists(playlists: List<PlaylistRecord>) {
        val json = gson.toJson(playlists)
        kv.encode(KEY_PLAYLISTS, json)
        playlistsCache = playlists.toMutableList()
    }

    private fun loadPlaylistSongs(): MutableList<PlaylistSongRecord> {
        playlistSongsCache?.let { return it }
        val json = kv.decodeString(KEY_PLAYLIST_SONGS, "") ?: ""
        playlistSongsCache = if (json.isBlank()) {
            mutableListOf()
        } else {
            try {
                val type = object : TypeToken<List<PlaylistSongRecord>>() {}.type
                gson.fromJson(json, type) ?: mutableListOf()
            } catch (e: Exception) {
                mutableListOf()
            }
        }
        return playlistSongsCache!!
    }

    private fun savePlaylistSongs(songs: List<PlaylistSongRecord>) {
        val json = gson.toJson(songs)
        kv.encode(KEY_PLAYLIST_SONGS, json)
        playlistSongsCache = songs.toMutableList()
    }

    private fun nextPlaylistId(): Long {
        val id = kv.decodeLong(KEY_NEXT_PLAYLIST_ID, 1)
        kv.encode(KEY_NEXT_PLAYLIST_ID, id + 1)
        return id
    }

    private fun nextSongId(): Long {
        val id = kv.decodeLong(KEY_NEXT_SONG_ID, 1)
        kv.encode(KEY_NEXT_SONG_ID, id + 1)
        return id
    }

    fun getAll(): List<Playlist> {
        return loadPlaylists().map { record ->
            Playlist(
                id = record.id,
                name = record.name,
                coverPath = record.coverPath ?: "",
                songCount = record.songCount,
                createDate = record.createDate,
                updateDate = record.updateDate,
                isSystem = record.isSystem
            )
        }
    }

    fun getById(id: Long): Playlist? {
        return loadPlaylists().find { it.id == id }?.let { record ->
            Playlist(
                id = record.id,
                name = record.name,
                coverPath = record.coverPath ?: "",
                songCount = record.songCount,
                createDate = record.createDate,
                updateDate = record.updateDate,
                isSystem = record.isSystem
            )
        }
    }

    fun getByName(name: String): Playlist? {
        return loadPlaylists().find { it.name == name }?.let { record ->
            Playlist(
                id = record.id,
                name = record.name,
                coverPath = record.coverPath ?: "",
                songCount = record.songCount,
                createDate = record.createDate,
                updateDate = record.updateDate,
                isSystem = record.isSystem
            )
        }
    }

    fun create(name: String): Boolean {
        if (getByName(name) != null) return false
        val playlists = loadPlaylists()
        val newPlaylist = PlaylistRecord(
            id = nextPlaylistId(),
            name = name,
            createDate = System.currentTimeMillis(),
            updateDate = System.currentTimeMillis()
        )
        playlists.add(newPlaylist)
        savePlaylists(playlists)
        return true
    }

    fun delete(id: Long): Int {
        val playlists = loadPlaylists()
        val initialSize = playlists.size
        val filtered = playlists.filterNot { it.id == id }
        if (filtered.size < initialSize) {
            savePlaylists(filtered)
            val songs = loadPlaylistSongs().filterNot { it.playlistId == id }
            savePlaylistSongs(songs)
        }
        return initialSize - filtered.size
    }

    fun rename(id: Long, newName: String): Boolean {
        val playlists = loadPlaylists()
        val index = playlists.indexOfFirst { it.id == id }
        if (index == -1) return false
        playlists[index] = playlists[index].copy(
            name = newName,
            updateDate = System.currentTimeMillis()
        )
        savePlaylists(playlists)
        return true
    }

    fun getSongs(playlistId: Long): List<PlaylistSong> {
        return loadPlaylistSongs()
            .filter { it.playlistId == playlistId }
            .sortedBy { it.sortOrder }
            .mapIndexed { index, record ->
                PlaylistSong(
                    id = record.id,
                    playlistId = record.playlistId,
                    audioId = record.audioId,
                    sortOrder = index,
                    dateAdded = record.dateAdded
                )
            }
    }

    fun addSong(playlistId: Long, audioId: Long): Boolean {
        val songs = loadPlaylistSongs()
        if (songs.any { it.playlistId == playlistId && it.audioId == audioId }) return false

        val maxOrder = songs.filter { it.playlistId == playlistId }.maxOfOrNull { it.sortOrder } ?: -1
        val newSong = PlaylistSongRecord(
            id = nextSongId(),
            playlistId = playlistId,
            audioId = audioId,
            sortOrder = maxOrder + 1,
            dateAdded = System.currentTimeMillis()
        )
        songs.add(newSong)
        savePlaylistSongs(songs)
        updateSongCount(playlistId)
        return true
    }

    fun removeSong(playlistId: Long, audioId: Long): Boolean {
        val songs = loadPlaylistSongs()
        val initialSize = songs.size
        val filtered = songs.filterNot { it.playlistId == playlistId && it.audioId == audioId }
        if (filtered.size < initialSize) {
            savePlaylistSongs(filtered)
            updateSongCount(playlistId)
            return true
        }
        return false
    }

    private fun updateSongCount(playlistId: Long) {
        val playlists = loadPlaylists()
        val index = playlists.indexOfFirst { it.id == playlistId }
        if (index == -1) return
        val count = loadPlaylistSongs().count { it.playlistId == playlistId }
        playlists[index] = playlists[index].copy(
            songCount = count,
            updateDate = System.currentTimeMillis()
        )
        savePlaylists(playlists)
    }
}
