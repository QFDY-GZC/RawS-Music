package com.rawsmusic.module.data.repository

import android.content.Context
import com.rawsmusic.core.common.model.Album
import com.rawsmusic.core.common.model.Artist
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.Folder
import com.rawsmusic.core.common.model.Genre
import com.rawsmusic.core.common.model.PlayStats
import com.rawsmusic.core.common.model.SortOrder
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.core.common.utils.CjkSortUtils
import com.rawsmusic.module.data.db.MusicDatabase
import com.rawsmusic.module.data.db.converter.EntityConverter
import com.rawsmusic.module.data.db.entity.FolderFileEntity
import com.rawsmusic.module.data.prefs.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * 音乐仓库 — 数据库层。
 *
 * 同步 API 继续保留给旧 UI 调用；新增 suspend API 给扫描/批量写入使用，避免在 IO 协程里再套 runBlocking。
 */
object MusicRepository {

    private const val TAG = "MusicRepo"

    @Volatile
    private var db: MusicDatabase? = null

    @Volatile
    private var cachedSongs: List<AudioFile>? = null
    private var cachedById: Map<Long, AudioFile> = emptyMap()
    private var cachedByLibraryKey: Map<String, AudioFile> = emptyMap()

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

    fun init(context: Context) {
        db = MusicDatabase.getInstance(context)
        AppLogger.d(TAG, "init: Room database initialized")
    }

    private fun getDb(): MusicDatabase {
        return db ?: throw IllegalStateException("MusicRepository not initialized. Call init(context) first.")
    }

    // ────────────────────── 缓存管理 ──────────────────────

    private fun updateCache(songs: List<AudioFile>) {
        cachedSongs = songs
        cachedById = songs.associateBy { it.id }
        cachedByLibraryKey = songs.associateBy { it.libraryKey() }
    }

    private fun invalidateCache() {
        cachedSongs = null
        cachedById = emptyMap()
        cachedByLibraryKey = emptyMap()
    }

    private fun AudioFile.libraryKey(): String = buildLibraryKey(path, cueOffsetMs, cueTrackIndex)
    private fun FolderFileEntity.libraryKey(): String = buildLibraryKey(filePath, cueOffsetMs, cueTrackIndex)

    private fun buildLibraryKey(path: String, cueOffsetMs: Long, cueTrackIndex: Int): String {
        return if (cueTrackIndex > 0 || cueOffsetMs > 0L) {
            "cue|$path|$cueTrackIndex|$cueOffsetMs"
        } else {
            "file|$path"
        }
    }

    private fun mergePreservedFields(newEntity: FolderFileEntity, existing: FolderFileEntity?): FolderFileEntity {
        if (existing == null) return newEntity
        return newEntity.copy(
            id = existing.id,
            playedTimes = existing.playedTimes,
            lastPos = existing.lastPos,
            playedAt = existing.playedAt,
            playedFullyAt = existing.playedFullyAt,
            rating = existing.rating.takeIf { it > 0 } ?: newEntity.rating,
            createdAt = existing.createdAt.takeIf { it > 0L } ?: newEntity.createdAt,
            fileCreatedAt = newEntity.fileCreatedAt.takeIf { it > 0L } ?: existing.fileCreatedAt,
            fileModifiedAt = newEntity.fileModifiedAt.takeIf { it > 0L } ?: existing.fileModifiedAt
        )
    }

    // ────────────────────── 加载 ──────────────────────

    private suspend fun loadSongsFromStorageSuspend(invalidate: Boolean = false): List<AudioFile> = withContext(Dispatchers.IO) {
        if (invalidate) invalidateCache()
        cachedSongs?.let { return@withContext it }

        val t0 = System.currentTimeMillis()
        val songs = try {
            getDb().folderFileDao().getAll().map { EntityConverter.folderFileToAudioFile(it) }
        } catch (e: Exception) {
            AppLogger.e(TAG, "loadSongsFromStorageSuspend: error", e)
            emptyList()
        }
        updateCache(songs)
        AppLogger.d(TAG, "loadSongsFromStorageSuspend: loaded ${songs.size} songs in ${System.currentTimeMillis() - t0}ms")
        songs
    }

    private fun loadSongsFromStorage(): List<AudioFile> {
        cachedSongs?.let { return it }
        return runBlocking { loadSongsFromStorageSuspend() }
    }

    // ────────────────────── 刷新 ──────────────────────

    fun refreshAll() {
        runBlocking { refreshAllSuspend() }
    }

    suspend fun refreshAllSuspend() {
        val t0 = System.currentTimeMillis()
        val allSongs = refreshSongsOnlySuspend(invalidate = true)
        val tLoad = System.currentTimeMillis()
        refreshLibraryIndexes(allSongs)
        AppLogger.d(TAG, "refreshAll: ${allSongs.size} songs, songs=${tLoad - t0}ms, indexes=${System.currentTimeMillis() - tLoad}ms, total=${System.currentTimeMillis() - t0}ms")
    }

    fun refreshSongsOnly(invalidate: Boolean = false): List<AudioFile> {
        return runBlocking { refreshSongsOnlySuspend(invalidate) }
    }

    suspend fun refreshSongsOnlySuspend(invalidate: Boolean = false): List<AudioFile> {
        val t0 = System.currentTimeMillis()
        val allSongs = loadSongsFromStorageSuspend(invalidate)
        val tLoad = System.currentTimeMillis()
        val sortedSongs = sortSongs(allSongs, AppPreferences.Sort.songSortOrder)
        _songs.value = sortedSongs
        AppLogger.d(TAG, "refreshSongsOnly: ${sortedSongs.size} songs, load=${tLoad - t0}ms, sort=${System.currentTimeMillis() - tLoad}ms, total=${System.currentTimeMillis() - t0}ms")
        return sortedSongs
    }

    fun refreshLibraryIndexes(sourceSongs: List<AudioFile> = _songs.value.ifEmpty { loadSongsFromStorage() }) {
        val t0 = System.currentTimeMillis()
        val sortedSongs = if (sourceSongs === _songs.value) sourceSongs else sortSongs(sourceSongs, AppPreferences.Sort.songSortOrder)
        _artists.value = buildArtists(sortedSongs)
        _albums.value = buildAlbums(sortedSongs)
        _genres.value = buildGenres(sortedSongs)
        _folders.value = buildFolders(sortedSongs)
        AppLogger.d(TAG, "refreshLibraryIndexes: ${sortedSongs.size} songs, total=${System.currentTimeMillis() - t0}ms")
    }

    // ────────────────────── 查询 ──────────────────────

    fun getAllSongs(sortOrder: SortOrder = SortOrder.TITLE_ASC): List<AudioFile> {
        return sortSongs(loadSongsFromStorage(), sortOrder)
    }

    suspend fun getAllSongsSuspend(sortOrder: SortOrder = SortOrder.TITLE_ASC): List<AudioFile> {
        return sortSongs(loadSongsFromStorageSuspend(), sortOrder)
    }

    fun getSongById(songId: Long): AudioFile? {
        cachedById[songId]?.let { return it }
        return loadSongsFromStorage().find { it.id == songId }
    }

    fun getSongByPath(path: String): AudioFile? {
        cachedByLibraryKey[buildLibraryKey(path, 0L, 0)]?.let { return it }
        return loadSongsFromStorage().firstOrNull { it.path == path && it.cueOffsetMs == 0L && it.cueTrackIndex == 0 }
            ?: loadSongsFromStorage().firstOrNull { it.path == path }
    }

    fun getSongsByArtist(artist: String): List<AudioFile> = loadSongsFromStorage().filter { it.artist == artist }
    fun getSongsByAlbum(album: String): List<AudioFile> = loadSongsFromStorage().filter { it.album == album }
    fun getSongsByGenre(genre: String): List<AudioFile> = loadSongsFromStorage().filter { it.genre == genre }
    fun getSongsByFolder(folderPath: String): List<AudioFile> = loadSongsFromStorage().filter { it.path.startsWith(folderPath) }
    fun getFavorites(): List<AudioFile> = loadSongsFromStorage().filter { it.isFavorite }

    fun searchSongs(query: String): List<AudioFile> {
        if (query.isBlank()) return emptyList()
        val lowerQuery = query.lowercase()
        return loadSongsFromStorage().filter {
            it.title.lowercase().contains(lowerQuery) ||
                it.artist.lowercase().contains(lowerQuery) ||
                it.album.lowercase().contains(lowerQuery)
        }
    }

    // ────────────────────── 写入 ──────────────────────

    fun insertSongs(songs: List<AudioFile>): Int = runBlocking { insertSongsSuspend(songs) }

    suspend fun insertSongsSuspend(songs: List<AudioFile>): Int = withContext(Dispatchers.IO) {
        AppLogger.d(TAG, "insertSongsSuspend: inserting ${songs.size} songs")
        val t0 = System.currentTimeMillis()
        val result = try {
            val database = getDb()
            val folderDao = database.folderDao()
            val fileDao = database.folderFileDao()
            val existingKeys = fileDao.getAll().mapTo(HashSet()) { it.libraryKey() }
            val newSongs = songs.distinctBy { it.libraryKey() }.filter { it.libraryKey() !in existingKeys }
            var insertedCount = 0

            for ((folderPath, folderSongs) in newSongs.groupBy { it.path.substringBeforeLast("/") }) {
                var folder = folderDao.getByPath(folderPath)
                if (folder == null) {
                    folderDao.insert(EntityConverter.pathToFolderEntity(folderPath))
                    folder = folderDao.getByPath(folderPath)
                }
                if (folder != null) {
                    fileDao.insertBatch(folderSongs.map { EntityConverter.audioFileToFolderFile(it, folder.id) })
                    insertedCount += folderSongs.size
                }
            }
            insertedCount
        } catch (e: Exception) {
            AppLogger.e(TAG, "insertSongsSuspend: error", e)
            0
        }

        invalidateCache()
        refreshAllSuspend()
        AppLogger.d(TAG, "insertSongsSuspend: new=$result, total=${songs.size}, time=${System.currentTimeMillis() - t0}ms")
        result
    }

    /** 增量扫描用：按 path + CUE 信息做稳定 upsert，并保留播放统计/收藏/数据库创建时间。 */
    suspend fun upsertSongsSuspend(songs: List<AudioFile>): Int = withContext(Dispatchers.IO) {
        val distinctSongs = songs.distinctBy { it.libraryKey() }
        if (distinctSongs.isEmpty()) return@withContext 0

        val database = getDb()
        val folderDao = database.folderDao()
        val fileDao = database.folderFileDao()
        var changed = 0

        try {
            for ((folderPath, folderSongs) in distinctSongs.groupBy { it.path.substringBeforeLast("/") }) {
                var folder = folderDao.getByPath(folderPath)
                if (folder == null) {
                    folderDao.insert(EntityConverter.pathToFolderEntity(folderPath))
                    folder = folderDao.getByPath(folderPath)
                }
                val folderId = folder?.id ?: continue
                for (song in folderSongs) {
                    val existing = fileDao.getByPathAndCue(song.path, song.cueOffsetMs, song.cueTrackIndex)
                        ?: if (song.cueOffsetMs == 0L && song.cueTrackIndex == 0) fileDao.getByPath(song.path) else null
                    val entity = mergePreservedFields(EntityConverter.audioFileToFolderFile(song, folderId), existing)
                    if (existing == null) fileDao.insert(entity) else fileDao.update(entity)
                    changed++
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "upsertSongsSuspend: error", e)
        }

        invalidateCache()
        refreshAllSuspend()
        changed
    }

    fun toggleFavorite(songId: Long, isFavorite: Boolean): Boolean {
        runBlocking {
            try {
                getDb().folderFileDao().updateRating(songId, if (isFavorite) 1 else 0)
            } catch (e: Exception) {
                AppLogger.e(TAG, "toggleFavorite: error", e)
            }
        }
        cachedSongs?.let { songs ->
            val updated = songs.map { if (it.id == songId) it.copy(isFavorite = isFavorite) else it }
            updateCache(updated)
            _songs.value = sortSongs(updated, AppPreferences.Sort.songSortOrder)
        }
        return true
    }

    fun removeSong(path: String) {
        runBlocking { deleteSongsSuspend(listOf(AudioFile(path = path))) }
    }

    suspend fun deleteSongsSuspend(songs: List<AudioFile>) = withContext(Dispatchers.IO) {
        try {
            val dao = getDb().folderFileDao()
            songs.forEach { song ->
                if (song.cueOffsetMs > 0L || song.cueTrackIndex > 0) {
                    dao.deleteByPathAndCue(song.path, song.cueOffsetMs, song.cueTrackIndex)
                } else {
                    dao.deleteByPath(song.path)
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "deleteSongsSuspend: error", e)
        }
        invalidateCache()
        refreshAllSuspend()
    }

    fun deleteSongFromDevice(context: android.content.Context, song: AudioFile): Boolean {
        var deleted = false
        if (song.id > 0) {
            try {
                val uri = android.content.ContentUris.withAppendedId(
                    android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, song.id
                )
                if (context.contentResolver.delete(uri, null, null) > 0) deleted = true
            } catch (_: Exception) {}
        }
        if (!deleted) {
            try {
                val file = java.io.File(song.path)
                if (file.exists() && file.delete()) deleted = true
            } catch (_: Exception) {}
        }
        if (deleted) {
            removeSong(song.path)
            try {
                android.media.MediaScannerConnection.scanFile(context, arrayOf(song.path), null, null)
            } catch (_: Exception) {}
        }
        return deleted
    }

    fun updateSong(updated: AudioFile) {
        runBlocking {
            try {
                val dao = getDb().folderFileDao()
                val existing = dao.getByPathAndCue(updated.path, updated.cueOffsetMs, updated.cueTrackIndex)
                    ?: if (updated.cueOffsetMs == 0L && updated.cueTrackIndex == 0) dao.getByPath(updated.path) else null
                if (existing != null) {
                    val newEntity = mergePreservedFields(EntityConverter.audioFileToFolderFile(updated, existing.folderId), existing)
                    dao.update(newEntity)
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "updateSong: error", e)
            }
        }
        invalidateCache()
    }

    /** 完全替换所有歌曲（用户触发重新扫描后使用）。保留歌单表；播放统计会随 folder_files 重建。 */
    fun replaceAllSongs(songs: List<AudioFile>) {
        runBlocking {
            try {
                val database = getDb()
                database.folderFileDao().deleteAll()
                database.folderDao().deleteAll()
            } catch (e: Exception) {
                AppLogger.e(TAG, "replaceAllSongs: error clearing", e)
            }
            insertSongsSuspend(songs)
        }
    }

    fun clearAll() {
        runBlocking {
            try {
                val database = getDb()
                database.folderFileDao().deleteAll()
                database.folderDao().deleteAll()
            } catch (e: Exception) {
                AppLogger.e(TAG, "clearAll: error", e)
            }
        }
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
            artistDistribution = allSongs.filter { it.artist.isNotBlank() }.groupingBy { it.artist }.eachCount(),
            albumDistribution = allSongs.filter { it.album.isNotBlank() }.groupingBy { it.album }.eachCount()
        )
    }

    // ────────────────────── 播放信息更新 ──────────────────────

    fun updatePlayedInfo(songId: Long, lastPos: Long) {
        runBlocking {
            try {
                getDb().folderFileDao().updatePlayedInfo(songId, System.currentTimeMillis(), lastPos)
            } catch (_: Exception) {}
        }
    }

    fun updatePlayedFully(songId: Long) {
        runBlocking {
            try {
                getDb().folderFileDao().updatePlayedFully(songId, System.currentTimeMillis())
            } catch (_: Exception) {}
        }
    }

    // ────────────────────── 分类聚合 ──────────────────────

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
            }.sortedBy { CjkSortUtils.sortKey(it.name) }
    }

    private fun buildAlbums(songs: List<AudioFile>): List<Album> {
        return songs.filter { it.album.isNotBlank() }
            .groupBy { it.album }
            .map { (albumName, list) ->
                val mostCommonArtist = list.groupBy { it.artist }.maxByOrNull { it.value.size }?.key ?: list.first().artist
                Album(
                    name = albumName,
                    artist = mostCommonArtist,
                    songCount = list.size,
                    year = list.firstOrNull { it.year > 0 }?.year ?: list.first().year,
                    coverPath = list.firstOrNull { it.albumArtPath.isNotBlank() }?.albumArtPath ?: list.first().albumArtPath,
                    hasHiRes = list.any { it.isHiRes }
                )
            }.sortedBy { CjkSortUtils.sortKey(it.name) }
    }

    private fun buildGenres(songs: List<AudioFile>): List<Genre> {
        return songs.filter { it.genre.isNotBlank() }
            .groupBy { it.genre }
            .map { (name, list) -> Genre(name = name, songCount = list.size) }
            .sortedBy { CjkSortUtils.sortKey(it.name) }
    }

    private fun buildFolders(songs: List<AudioFile>): List<Folder> {
        return songs.map { it.path.substringBeforeLast("/") }
            .groupBy { it }
            .map { (path, list) -> Folder(path = path, name = path.substringAfterLast("/"), songCount = list.size) }
            .sortedBy { CjkSortUtils.sortKey(it.name) }
    }

    // ────────────────────── 排序 ──────────────────────

    private fun sortSongs(songs: List<AudioFile>, order: SortOrder): List<AudioFile> {
        fun fileName(song: AudioFile): String {
            return song.path.substringAfterLast('/').substringBeforeLast('.', song.title.ifBlank { song.displayName })
        }

        fun playCountMap(): Map<Long, Int> = runBlocking {
            try {
                getDb().folderFileDao().getAll()
                    .filter { it.playedTimes > 0 }
                    .associate { it.id to it.playedTimes }
            } catch (_: Exception) {
                emptyMap()
            }
        }

        return when (order) {
            SortOrder.TITLE_ASC -> songs.sortedBy { CjkSortUtils.sortKey(it.displayName) }
            SortOrder.TITLE_DESC -> songs.sortedByDescending { CjkSortUtils.sortKey(it.displayName) }
            SortOrder.FILE_NAME_ASC -> songs.sortedBy { CjkSortUtils.sortKey(fileName(it)) }
            SortOrder.FILE_NAME_DESC -> songs.sortedByDescending { CjkSortUtils.sortKey(fileName(it)) }
            SortOrder.PATH_ASC -> songs.sortedBy { CjkSortUtils.sortKey(it.path) }
            SortOrder.PATH_DESC -> songs.sortedByDescending { CjkSortUtils.sortKey(it.path) }
            SortOrder.ARTIST_ASC -> songs.sortedBy { CjkSortUtils.sortKey(it.artist) }
            SortOrder.ARTIST_DESC -> songs.sortedByDescending { CjkSortUtils.sortKey(it.artist) }
            SortOrder.ALBUM_ASC -> songs.sortedBy { CjkSortUtils.sortKey(it.album) }
            SortOrder.ALBUM_DESC -> songs.sortedByDescending { CjkSortUtils.sortKey(it.album) }
            SortOrder.DATE_ADDED_ASC -> songs.sortedBy { it.dateAdded }
            SortOrder.DATE_ADDED_DESC -> songs.sortedByDescending { it.dateAdded }
            SortOrder.DURATION_ASC -> songs.sortedBy { it.duration }
            SortOrder.DURATION_DESC -> songs.sortedByDescending { it.duration }
            SortOrder.YEAR_ASC -> songs.sortedWith(compareBy<AudioFile> { if (it.year <= 0) Int.MAX_VALUE else it.year }.thenBy { CjkSortUtils.sortKey(it.displayName) })
            SortOrder.YEAR_DESC -> songs.sortedWith(compareByDescending<AudioFile> { it.year }.thenBy { CjkSortUtils.sortKey(it.displayName) })
            SortOrder.PLAYBACK_INFO -> {
                val playCounts = playCountMap()
                if (playCounts.isEmpty()) songs.sortedByDescending { it.dateAdded }
                else songs.sortedByDescending { playCounts[it.id] ?: 0 }
            }
            SortOrder.PLAYBACK_INFO_DESC -> {
                val playCounts = playCountMap()
                if (playCounts.isEmpty()) songs.sortedBy { it.dateAdded }
                else songs.sortedBy { playCounts[it.id] ?: 0 }
            }
        }
    }
}
