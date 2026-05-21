package com.rawsmusic.module.data.prefs

import android.content.Context
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.FAVORITES_PLAYLIST_ID
import com.rawsmusic.core.common.model.UserPlaylist
import com.rawsmusic.core.common.model.UserPlaylistSong
import com.rawsmusic.core.common.model.playlistIdentityKey
import com.rawsmusic.core.common.model.toUserPlaylistSong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

class PlaylistStore private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var instance: PlaylistStore? = null

        fun getInstance(context: Context): PlaylistStore {
            return instance ?: synchronized(this) {
                instance ?: PlaylistStore(context.applicationContext).also { instance = it }
            }
        }
    }

    private val lock = Any()
    private val fileName = "rawsmusic_playlists.json"

    private val _playlists = MutableStateFlow<List<UserPlaylist>>(emptyList())
    val playlists: StateFlow<List<UserPlaylist>> = _playlists.asStateFlow()

    init {
        _playlists.value = loadPlaylists()
    }

    fun favoriteSongKeys(): Set<String> {
        val fav = _playlists.value.find { it.isFavorites } ?: return emptySet()
        return fav.songs.map { it.key }.toSet()
    }

    fun isFavorite(song: AudioFile): Boolean {
        return song.playlistIdentityKey() in favoriteSongKeys()
    }

    suspend fun toggleFavorite(song: AudioFile): Boolean {
        val key = song.playlistIdentityKey()
        val fav = _playlists.value.find { it.isFavorites } ?: return false
        val existing = fav.songs.indexOfFirst { it.key == key }
        return if (existing >= 0) {
            removeSongFromPlaylist(fav.id, key)
            false
        } else {
            addSongToPlaylist(fav.id, song)
            true
        }
    }

    suspend fun createPlaylist(name: String): UserPlaylist? {
        val id = "playlist-${java.util.UUID.randomUUID()}"
        val now = System.currentTimeMillis()
        val playlist = UserPlaylist(id, name, emptyList(), now, now)
        synchronized(lock) {
            val updated = _playlists.value + playlist
            saveLocked(updated)
        }
        _playlists.value = loadPlaylists()
        return _playlists.value.find { it.id == id }
    }

    suspend fun deletePlaylist(id: String) {
        if (id == FAVORITES_PLAYLIST_ID) return
        synchronized(lock) {
            val updated = _playlists.value.filter { it.id != id }
            saveLocked(updated)
        }
        _playlists.value = loadPlaylists()
    }

    suspend fun renamePlaylist(id: String, newName: String) {
        synchronized(lock) {
            val updated = _playlists.value.map {
                if (it.id == id) it.copy(name = newName, updatedAt = System.currentTimeMillis()) else it
            }
            saveLocked(updated)
        }
        _playlists.value = loadPlaylists()
    }

    suspend fun addSongToPlaylist(playlistId: String, song: AudioFile) {
        val ps = song.toUserPlaylistSong()
        synchronized(lock) {
            val updated = _playlists.value.map { pl ->
                if (pl.id == playlistId) {
                    if (pl.songs.any { it.key == ps.key }) pl
                    else pl.copy(songs = pl.songs + ps, updatedAt = System.currentTimeMillis())
                } else pl
            }
            saveLocked(updated)
        }
        _playlists.value = loadPlaylists()
    }

    suspend fun addSongsToPlaylist(playlistId: String, songs: Collection<AudioFile>) {
        val newSongs = songs.map { it.toUserPlaylistSong() }
        synchronized(lock) {
            val updated = _playlists.value.map { pl ->
                if (pl.id == playlistId) {
                    val existingKeys = pl.songs.map { it.key }.toSet()
                    val toAdd = newSongs.filter { it.key !in existingKeys }
                    pl.copy(songs = pl.songs + toAdd, updatedAt = System.currentTimeMillis())
                } else pl
            }
            saveLocked(updated)
        }
        _playlists.value = loadPlaylists()
    }

    suspend fun removeSongFromPlaylist(playlistId: String, songKey: String) {
        synchronized(lock) {
            val updated = _playlists.value.map { pl ->
                if (pl.id == playlistId) {
                    pl.copy(songs = pl.songs.filter { it.key != songKey }, updatedAt = System.currentTimeMillis())
                } else pl
            }
            saveLocked(updated)
        }
        _playlists.value = loadPlaylists()
    }

    fun exportJson(): JSONObject {
        val arr = JSONArray()
        _playlists.value.forEach { pl -> arr.put(playlistToJson(pl)) }
        return JSONObject().put("playlists", arr)
    }

    suspend fun restoreJson(json: JSONObject) {
        val arr = json.optJSONArray("playlists") ?: return
        val list = mutableListOf<UserPlaylist>()
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { list.add(jsonToPlaylist(it)) }
        }
        synchronized(lock) { saveLocked(list) }
        _playlists.value = loadPlaylists()
    }

    private fun loadPlaylists(): List<UserPlaylist> {
        val file = context.filesDir.resolve(fileName)
        if (!file.exists()) return listOf(createFavoritesPlaylist())
        return try {
            val json = JSONObject(file.readText(Charsets.UTF_8))
            val arr = json.optJSONArray("playlists") ?: return listOf(createFavoritesPlaylist())
            val list = mutableListOf<UserPlaylist>()
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { list.add(jsonToPlaylist(it)) }
            }
            ensureFavorites(list)
        } catch (_: Exception) {
            listOf(createFavoritesPlaylist())
        }
    }

    private fun createFavoritesPlaylist(): UserPlaylist {
        val now = System.currentTimeMillis()
        return UserPlaylist(FAVORITES_PLAYLIST_ID, "我喜欢的音乐", emptyList(), now, now)
    }

    private fun ensureFavorites(list: List<UserPlaylist>): List<UserPlaylist> {
        return if (list.any { it.isFavorites }) list
        else list + createFavoritesPlaylist()
    }

    private fun saveLocked(playlists: List<UserPlaylist>) {
        val json = JSONObject()
        val arr = JSONArray()
        playlists.forEach { arr.put(playlistToJson(it)) }
        json.put("playlists", arr)
        context.filesDir.resolve(fileName).writeText(json.toString(), Charsets.UTF_8)
    }

    private fun playlistToJson(pl: UserPlaylist): JSONObject {
        val songsArr = JSONArray()
        pl.songs.forEach { songsArr.put(userPlaylistSongToJson(it)) }
        return JSONObject()
            .put("id", pl.id)
            .put("name", pl.name)
            .put("songs", songsArr)
            .put("createdAt", pl.createdAt)
            .put("updatedAt", pl.updatedAt)
    }

    private fun jsonToPlaylist(json: JSONObject): UserPlaylist {
        val songsArr = json.optJSONArray("songs") ?: JSONArray()
        val songs = mutableListOf<UserPlaylistSong>()
        for (i in 0 until songsArr.length()) {
            songsArr.optJSONObject(i)?.let { songs.add(jsonToUserPlaylistSong(it)) }
        }
        return UserPlaylist(
            id = json.optString("id", ""),
            name = json.optString("name", ""),
            songs = songs,
            createdAt = json.optLong("createdAt", 0),
            updatedAt = json.optLong("updatedAt", 0)
        )
    }

    private fun userPlaylistSongToJson(ps: UserPlaylistSong): JSONObject {
        return JSONObject()
            .put("key", ps.key)
            .put("id", ps.id)
            .put("title", ps.title)
            .put("artist", ps.artist)
            .put("album", ps.album)
            .put("albumId", ps.albumId)
            .put("duration", ps.duration)
            .put("path", ps.path)
            .put("fileSize", ps.fileSize)
            .put("format", ps.format)
            .put("sampleRate", ps.sampleRate)
            .put("bitRate", ps.bitRate)
            .put("bitsPerSample", ps.bitsPerSample)
            .put("albumArtPath", ps.albumArtPath)
            .put("addedAt", ps.addedAt)
    }

    private fun jsonToUserPlaylistSong(json: JSONObject): UserPlaylistSong {
        return UserPlaylistSong(
            key = json.optString("key", ""),
            id = json.optLong("id", 0),
            title = json.optString("title", ""),
            artist = json.optString("artist", ""),
            album = json.optString("album", ""),
            albumId = json.optLong("albumId", -1),
            duration = json.optLong("duration", 0),
            path = json.optString("path", ""),
            fileSize = json.optLong("fileSize", 0),
            format = json.optString("format", ""),
            sampleRate = json.optInt("sampleRate", 0),
            bitRate = json.optInt("bitRate", 0),
            bitsPerSample = json.optInt("bitsPerSample", 0),
            albumArtPath = json.optString("albumArtPath", ""),
            addedAt = json.optLong("addedAt", 0)
        )
    }
}
