package com.rawsmusic.module.data.db

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.rawsmusic.core.common.model.AudioFile
import com.tencent.mmkv.MMKV

object SongDao {

    private val kv by lazy { MMKV.defaultMMKV() }
    private val gson = Gson()
    private const val KEY_SONGS = "songs_data"
    private const val KEY_FAVORITES = "favorites_data"

    private var songsCache: MutableList<AudioFile>? = null

    private fun loadSongs(): MutableList<AudioFile> {
        songsCache?.let { return it }
        val json = kv.decodeString(KEY_SONGS, "") ?: ""
        songsCache = if (json.isBlank()) {
            mutableListOf()
        } else {
            try {
                val type = object : TypeToken<List<AudioFile>>() {}.type
                gson.fromJson(json, type) ?: mutableListOf()
            } catch (e: Exception) {
                mutableListOf()
            }
        }
        return songsCache!!
    }

    private fun saveSongs(songs: List<AudioFile>) {
        val json = gson.toJson(songs)
        kv.encode(KEY_SONGS, json)
        songsCache = songs.toMutableList()
    }

    fun getAll(): List<AudioFile> {
        return loadSongs().toList()
    }

    fun getById(songId: Long): AudioFile? {
        return loadSongs().find { it.id == songId }
    }

    fun getByArtist(artist: String): List<AudioFile> {
        return loadSongs().filter { it.artist == artist }
    }

    fun getByAlbum(album: String): List<AudioFile> {
        return loadSongs().filter { it.album == album }
    }

    fun getByGenre(genre: String): List<AudioFile> {
        return loadSongs().filter { it.genre == genre }
    }

    fun getByPath(path: String): AudioFile? {
        return loadSongs().find { it.path == path }
    }

    fun getFavorites(): List<AudioFile> {
        return loadSongs().filter { it.isFavorite }
    }

    fun search(query: String): List<AudioFile> {
        val lowerQuery = query.lowercase()
        return loadSongs().filter { song ->
            song.title.lowercase().contains(lowerQuery) ||
            song.artist.lowercase().contains(lowerQuery) ||
            song.album.lowercase().contains(lowerQuery)
        }
    }

    fun insert(song: AudioFile): Boolean {
        val songs = loadSongs()
        if (songs.any { it.path == song.path }) return false
        songs.add(song)
        saveSongs(songs)
        return true
    }

    fun insertAll(songs: List<AudioFile>): Int {
        val existing = loadSongs()
        val existingPaths = existing.map { it.path }.toSet()
        val newSongs = songs.filter { it.path !in existingPaths }
        if (newSongs.isEmpty()) return 0
        saveSongs(existing + newSongs)
        return newSongs.size
    }

    fun updateFavorite(songId: Long, isFavorite: Boolean): Boolean {
        val songs = loadSongs()
        val index = songs.indexOfFirst { it.id == songId }
        if (index == -1) return false
        songs[index] = songs[index].copy(isFavorite = isFavorite)
        saveSongs(songs)
        return true
    }

    fun deleteByPath(path: String): Int {
        val songs = loadSongs()
        val initialSize = songs.size
        val filtered = songs.filterNot { it.path == path }
        if (filtered.size < initialSize) {
            saveSongs(filtered)
        }
        return initialSize - filtered.size
    }

    fun deleteAll(): Int {
        val count = loadSongs().size
        kv.remove(KEY_SONGS)
        songsCache = null
        return count
    }

    fun count(): Int {
        return loadSongs().size
    }
}
