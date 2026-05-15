package com.rawsmusic.module.data.repository

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.rawsmusic.core.common.model.Album
import com.rawsmusic.core.common.model.Artist
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.Folder
import com.rawsmusic.core.common.model.Genre
import com.rawsmusic.core.common.model.PlayStats
import com.rawsmusic.core.common.model.SortOrder
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object MusicRepository {

    private val kv by lazy { MMKV.defaultMMKV() }
    private val gson = Gson()

    private const val KEY_SONGS = "music_songs"
    private const val KEY_FAVORITES = "music_favorites"

    // 内存缓存：避免每次调用 getAllSongs() 都从 MMKV 读取并反序列化 JSON
    @Volatile
    private var cachedSongs: List<AudioFile>? = null
    // 索引缓存：加速按 id/path 查询
    private var cachedById: Map<Long, AudioFile> = emptyMap()
    private var cachedByPath: Map<String, AudioFile> = emptyMap()

    private val _songs = MutableStateFlow<List<AudioFile>>(emptyList())
    val songs: StateFlow<List<AudioFile>> = _songs.asStateFlow()

    private val _artists = MutableStateFlow<List<Artist>>(emptyList())
    val artists: StateFlow<List<Artist>> = _artists.asStateFlow()

    private val _albums = MutableStateFlow<List<Album>>(emptyList())
    val albums: StateFlow<List<Album>> = _albums.asStateFlow()

    private val _genres = MutableStateFlow<List<Genre>>(emptyList())
    val genres: StateFlow<List<Genre>> = _genres.asStateFlow()

    private val _folders = MutableStateFlow<List<Folder>>(emptyList())
    val folders: StateFlow<List<Folder>> = _folders.asStateFlow()

    private var favorites: MutableSet<Long> = mutableSetOf()

    init {
        loadFavorites()
    }

    private fun loadFavorites() {
        val json = kv.decodeString(KEY_FAVORITES, "") ?: ""
        if (json.isNotBlank()) {
            try {
                val type = object : TypeToken<Set<Long>>() {}.type
                favorites = (gson.fromJson<Set<Long>>(json, type) ?: emptySet()).toMutableSet()
            } catch (_: Exception) {}
        }
    }

    private fun saveFavorites() {
        kv.encode(KEY_FAVORITES, gson.toJson(favorites))
    }

    /**
     * 从 MMKV 加载歌曲列表并更新缓存。
     * 仅在缓存为空时才读取 MMKV，否则直接返回缓存。
     */
    private fun loadSongsFromStorage(): List<AudioFile> {
        cachedSongs?.let { return it }
        val json = kv.decodeString(KEY_SONGS, "") ?: return emptyList()
        if (json.isBlank()) return emptyList()
        return try {
            val type = object : TypeToken<List<AudioFile>>() {}.type
            val songs: List<AudioFile> = gson.fromJson(json, type) ?: emptyList()
            updateCache(songs)
            songs
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 更新内存缓存和索引 */
    private fun updateCache(songs: List<AudioFile>) {
        cachedSongs = songs
        cachedById = songs.associateBy { it.id }
        cachedByPath = songs.associateBy { it.path }
    }

    /** 清除缓存，下次访问时重新从 MMKV 加载 */
    private fun invalidateCache() {
        cachedSongs = null
        cachedById = emptyMap()
        cachedByPath = emptyMap()
    }

    fun refreshAll() {
        // 刷新时强制重新加载
        invalidateCache()
        val allSongs = loadSongsFromStorage()
        _songs.value = allSongs
        _artists.value = buildArtists(allSongs)
        _albums.value = buildAlbums(allSongs)
        _genres.value = buildGenres(allSongs)
        _folders.value = buildFolders(allSongs)
    }

    fun getAllSongs(sortOrder: SortOrder = SortOrder.TITLE_ASC): List<AudioFile> {
        val songs = loadSongsFromStorage()
        return sortSongs(songs, sortOrder)
    }

    fun getSongById(songId: Long): AudioFile? {
        // 优先使用索引缓存 O(1) 查询
        cachedById[songId]?.let { return it }
        // 索引未命中时从列表查找（首次加载场景）
        return loadSongsFromStorage().find { it.id == songId }
    }

    fun getSongsByArtist(artist: String): List<AudioFile> {
        return loadSongsFromStorage().filter { it.artist == artist }
    }

    fun getSongsByAlbum(album: String): List<AudioFile> {
        return loadSongsFromStorage().filter { it.album == album }
    }

    fun getSongsByGenre(genre: String): List<AudioFile> {
        return loadSongsFromStorage().filter { it.genre == genre }
    }

    fun getSongsByFolder(folderPath: String): List<AudioFile> {
        return loadSongsFromStorage().filter { it.path.startsWith(folderPath) }
    }

    fun getFavorites(): List<AudioFile> {
        return loadSongsFromStorage().filter { favorites.contains(it.id) }
    }

    fun searchSongs(query: String): List<AudioFile> {
        if (query.isBlank()) return emptyList()
        val lowerQuery = query.lowercase()
        return loadSongsFromStorage().filter {
            it.title.lowercase().contains(lowerQuery) ||
            it.artist.lowercase().contains(lowerQuery) ||
            it.album.lowercase().contains(lowerQuery)
        }
    }

    fun insertSongs(songs: List<AudioFile>): Int {
        val existing = loadSongsFromStorage().associateBy { it.path }.toMutableMap()
        var count = 0
        songs.forEach { song ->
            if (!existing.containsKey(song.path)) {
                existing[song.path] = song
                count++
            }
        }
        val newList = existing.values.toList()
        kv.encode(KEY_SONGS, gson.toJson(newList))
        updateCache(newList)
        refreshAll()
        return count
    }

    fun toggleFavorite(songId: Long, isFavorite: Boolean): Boolean {
        if (isFavorite) {
            favorites.add(songId)
        } else {
            favorites.remove(songId)
        }
        saveFavorites()
        return true
    }

    fun removeSong(path: String) {
        val songs = loadSongsFromStorage().filter { it.path != path }
        kv.encode(KEY_SONGS, gson.toJson(songs))
        updateCache(songs)
        refreshAll()
    }

    fun updateSong(updated: AudioFile) {
        val songs = loadSongsFromStorage().toMutableList()
        val index = songs.indexOfFirst { it.path == updated.path }
        if (index >= 0) {
            songs[index] = updated
            kv.encode(KEY_SONGS, gson.toJson(songs))
            updateCache(songs)
            _songs.value = songs
        }
    }

    /** 完全替换所有歌曲（用户触发重新扫描后使用） */
    fun replaceAllSongs(songs: List<AudioFile>) {
        kv.encode(KEY_SONGS, gson.toJson(songs))
        updateCache(songs)
        refreshAll()
    }

    fun clearAll() {
        kv.remove(KEY_SONGS)
        favorites.clear()
        saveFavorites()
        invalidateCache()
        refreshAll()
    }

    fun getPlayStats(): PlayStats {
        val allSongs = loadSongsFromStorage()
        return PlayStats(
            totalSongs = allSongs.size,
            totalDuration = allSongs.sumOf { it.duration },
            totalSize = allSongs.sumOf { it.fileSize },
            formatDistribution = allSongs.groupingBy { it.format }.eachCount(),
            artistDistribution = allSongs.filter { it.artist.isNotBlank() }
                .groupingBy { it.artist }.eachCount(),
            albumDistribution = allSongs.filter { it.album.isNotBlank() }
                .groupingBy { it.album }.eachCount()
        )
    }

    private fun buildArtists(songs: List<AudioFile>): List<Artist> {
        return songs.filter { it.artist.isNotBlank() }
            .groupBy { it.artist }
            .map { (name, list) ->
                Artist(
                    name = name,
                    songCount = list.size,
                    albumCount = list.map { it.album }.distinct().size,
                    coverPath = list.firstOrNull { it.albumArtPath.isNotBlank() }?.albumArtPath ?: ""
                )
            }.sortedBy { it.name.lowercase() }
    }

    private fun buildAlbums(songs: List<AudioFile>): List<Album> {
        return songs.filter { it.album.isNotBlank() }
            .groupBy { it.album }
            .map { (albumName, list) ->
                // 取最常见的艺术家名，避免同专辑因艺术家标签微小差异被拆分
                val mostCommonArtist = list.groupBy { it.artist }
                    .maxByOrNull { it.value.size }?.key ?: list.first().artist
                Album(
                    name = albumName,
                    artist = mostCommonArtist,
                    songCount = list.size,
                    year = list.firstOrNull { it.year > 0 }?.year ?: list.first().year,
                    coverPath = list.firstOrNull { it.albumArtPath.isNotBlank() }?.albumArtPath ?: list.first().albumArtPath,
                    hasHiRes = list.any { it.isHiRes }
                )
            }.sortedBy { it.name.lowercase() }
    }

    private fun buildGenres(songs: List<AudioFile>): List<Genre> {
        return songs.filter { it.genre.isNotBlank() }
            .groupBy { it.genre }
            .map { (name, list) -> Genre(name = name, songCount = list.size) }
            .sortedBy { it.name.lowercase() }
    }

    private fun buildFolders(songs: List<AudioFile>): List<Folder> {
        return songs.map { it.path.substringBeforeLast("/") }
            .groupBy { it }
            .map { (path, list) ->
                Folder(
                    path = path,
                    name = path.substringAfterLast("/"),
                    songCount = list.size
                )
            }.sortedBy { it.name.lowercase() }
    }

    private fun sortSongs(songs: List<AudioFile>, order: SortOrder): List<AudioFile> {
        return when (order) {
            SortOrder.TITLE_ASC -> songs.sortedBy { it.title.lowercase() }
            SortOrder.TITLE_DESC -> songs.sortedByDescending { it.title.lowercase() }
            SortOrder.ARTIST_ASC -> songs.sortedBy { it.artist.lowercase() }
            SortOrder.ARTIST_DESC -> songs.sortedByDescending { it.artist.lowercase() }
            SortOrder.ALBUM_ASC -> songs.sortedBy { it.album.lowercase() }
            SortOrder.ALBUM_DESC -> songs.sortedByDescending { it.album.lowercase() }
            SortOrder.DATE_ADDED_ASC -> songs.sortedBy { it.dateAdded }
            SortOrder.DATE_ADDED_DESC -> songs.sortedByDescending { it.dateAdded }
            SortOrder.DURATION_ASC -> songs.sortedBy { it.duration }
            SortOrder.DURATION_DESC -> songs.sortedByDescending { it.duration }
            SortOrder.YEAR_ASC -> songs.sortedBy { it.year }
            SortOrder.YEAR_DESC -> songs.sortedByDescending { it.year }
        }
    }
}
