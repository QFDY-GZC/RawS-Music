package com.rawsmusic.module.data.repository

import android.content.Context
import androidx.room.withTransaction
import com.rawsmusic.core.common.model.Album
import com.rawsmusic.core.common.model.Artist
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.Folder
import com.rawsmusic.core.common.model.Genre
import com.rawsmusic.core.common.model.PlayStats
import com.rawsmusic.core.common.model.SortOrder
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.core.common.utils.CjkSortUtils
import com.rawsmusic.core.common.utils.PowerTraceLogger
import com.rawsmusic.module.data.db.MusicDatabase
import com.rawsmusic.module.data.db.converter.EntityConverter
import com.rawsmusic.module.data.db.entity.FolderFileEntity
import com.rawsmusic.module.data.prefs.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * 音乐仓库 — 数据库层。
 *
 * 同步 API 继续保留给旧 UI 调用；新增 suspend API 给扫描/批量写入使用，避免在 IO 协程里再套 runBlocking。
 */
object MusicRepository {

    private const val TAG = "MusicRepo"
    private const val SNAPSHOT_WARM_START_DB_DELAY_MS = 5_000L

    @Volatile
    private var db: MusicDatabase? = null

    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var cachedSongs: List<AudioFile>? = null

    @Volatile
    private var startupSnapshotContext: Context? = null

    @Volatile
    private var startupSnapshotPublished = false

    @Volatile
    private var warmStartRequested = false
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
        val appContext = context.applicationContext
        startupSnapshotContext = appContext
        db = MusicDatabase.getInstance(appContext)
        AppLogger.d(TAG, "init: Room database initialized")
        PowerTraceLogger.repositoryStartup(
            stage = "init",
            reason = "repository_init",
            songs = _songs.value.size,
            elapsedMs = 0L
        )
        publishStartupSnapshotIfAvailable(appContext)
        warmStartCacheAsync("repository_init")
    }

    fun warmStartCacheAsync(reason: String = "warm_start") {
        if (warmStartRequested) return
        warmStartRequested = true
        repositoryScope.launch {
            val t0 = System.currentTimeMillis()
            PowerTraceLogger.repositoryStartup(
                stage = "warm_start_begin",
                reason = reason,
                songs = _songs.value.size,
                elapsedMs = 0L
            )
            try {
                val hasRealSongs = _songs.value.isNotEmpty() && !startupSnapshotPublished
                if (hasRealSongs) return@launch

                if (startupSnapshotPublished) {
                    // Project-style startup: show the compact snapshot first, then hydrate Room after
                    // the first UI frames / capsule bar are already visible. This avoids a large
                    // collection immediately competing with player restore and album-art drawing.
                    delay(SNAPSHOT_WARM_START_DB_DELAY_MS)
                }

                val loaded = loadSongsFromStorageSuspend(invalidate = false)
                if (loaded.isEmpty()) {
                    val elapsed = System.currentTimeMillis() - t0
                    AppLogger.d(TAG, "warmStartCacheAsync: empty reason=$reason time=${elapsed}ms")
                    PowerTraceLogger.repositoryStartup(
                        stage = "warm_start_empty",
                        reason = reason,
                        songs = 0,
                        elapsedMs = elapsed
                    )
                    return@launch
                }
                val sorted = sortSongs(loaded, AppPreferences.Sort.songSortOrder)
                startupSnapshotPublished = false
                _songs.value = sorted
                saveStartupSnapshotAsync(sorted, "warm_start_room")
                val publishElapsed = System.currentTimeMillis() - t0
                AppLogger.d(TAG, "warmStartCacheAsync: published real ${sorted.size} songs reason=$reason time=${publishElapsed}ms")
                PowerTraceLogger.repositoryStartup(
                    stage = "warm_start_publish",
                    reason = reason,
                    songs = sorted.size,
                    elapsedMs = publishElapsed
                )
                repositoryScope.launch {
                    val idxStart = System.currentTimeMillis()
                    refreshLibraryIndexes(sorted)
                    val indexElapsed = System.currentTimeMillis() - idxStart
                    AppLogger.d(TAG, "warmStartCacheAsync: indexes ${sorted.size} songs time=${indexElapsed}ms")
                    PowerTraceLogger.repositoryStartup(
                        stage = "warm_start_indexes",
                        reason = reason,
                        songs = sorted.size,
                        elapsedMs = indexElapsed
                    )
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "warmStartCacheAsync failed reason=$reason", e)
            }
        }
    }

    private fun publishStartupSnapshotIfAvailable(context: Context) {
        val t0 = System.currentTimeMillis()
        val snapshot = StartupSongSnapshotStore.load(context, AppPreferences.Sort.songSortOrder) ?: return
        startupSnapshotPublished = true
        _songs.value = snapshot
        val elapsed = System.currentTimeMillis() - t0
        AppLogger.d(TAG, "startup snapshot published: songs=${snapshot.size} time=${elapsed}ms")
        PowerTraceLogger.repositoryStartup(
            stage = "snapshot_publish",
            reason = "startup_snapshot",
            songs = snapshot.size,
            elapsedMs = elapsed
        )
    }

    private fun saveStartupSnapshotAsync(songs: List<AudioFile>, reason: String) {
        val context = startupSnapshotContext ?: return
        if (songs.isEmpty()) return
        repositoryScope.launch {
            StartupSongSnapshotStore.save(context, songs, AppPreferences.Sort.songSortOrder)
            AppLogger.d(TAG, "startup snapshot save requested: reason=$reason songs=${songs.size}")
        }
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
        cachedSongs?.let { cached ->
            PowerTraceLogger.libraryLoad(
                stage = "load_songs_cache",
                songs = cached.size,
                elapsedMs = 0L,
                fromCache = true
            )
            return@withContext cached
        }

        val t0 = System.currentTimeMillis()
        val songs = try {
            getDb().folderFileDao().getAll().map { EntityConverter.folderFileToAudioFile(it) }
        } catch (e: Exception) {
            AppLogger.e(TAG, "loadSongsFromStorageSuspend: error", e)
            emptyList()
        }
        updateCache(songs)
        val elapsed = System.currentTimeMillis() - t0
        AppLogger.d(TAG, "loadSongsFromStorageSuspend: loaded ${songs.size} songs in ${elapsed}ms")
        PowerTraceLogger.libraryLoad(
            stage = "load_songs_room",
            songs = songs.size,
            elapsedMs = elapsed,
            fromCache = false
        )
        songs
    }

    private fun loadSongsFromStorage(): List<AudioFile> {
        cachedSongs?.let { cached ->
            PowerTraceLogger.libraryLoad(
                stage = "sync_load_cache",
                songs = cached.size,
                elapsedMs = 0L,
                fromCache = true
            )
            return cached
        }
        val t0 = System.currentTimeMillis()
        return runBlocking { loadSongsFromStorageSuspend() }.also { loaded ->
            PowerTraceLogger.libraryLoad(
                stage = "sync_load_room",
                songs = loaded.size,
                elapsedMs = System.currentTimeMillis() - t0,
                fromCache = false
            )
        }
    }

    // ────────────────────── 刷新 ──────────────────────

    /**
     * 扫描期间逐步发布歌曲到 UI StateFlow。
     * 只更新 UI 的 _songs，不写 Room，不污染 cachedSongs。
     * DB 同步完成后 refreshAllSuspend() 会以 Room 结果为准覆盖。
     *
     * @param songs 快速扫描/enriched 阶段的歌曲列表
     */
    fun publishTransientScanSongs(songs: List<AudioFile>) {
        if (songs.isEmpty()) return
        val sorted = sortSongs(songs, AppPreferences.Sort.songSortOrder)
        _songs.value = sorted
        saveStartupSnapshotAsync(sorted, "transient_scan")
        AppLogger.d(TAG, "publishTransientScanSongs: published ${sorted.size} songs to UI (transient)")
    }

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

    /** 扫描批量写入后统一刷新。给两阶段/惰性扫描使用，避免每个 enrich 批次都重建 UI 索引。 */
    suspend fun refreshAfterBulkScanSyncSuspend() {
        refreshAllSuspend()
    }

    fun refreshSongsOnly(invalidate: Boolean = false): List<AudioFile> {
        return runBlocking { refreshSongsOnlySuspend(invalidate) }
    }

    suspend fun refreshSongsOnlySuspend(invalidate: Boolean = false): List<AudioFile> {
        val t0 = System.currentTimeMillis()
        val allSongs = loadSongsFromStorageSuspend(invalidate)
        val tLoad = System.currentTimeMillis()
        val sortedSongs = sortSongs(allSongs, AppPreferences.Sort.songSortOrder)
        startupSnapshotPublished = false
        _songs.value = sortedSongs
        saveStartupSnapshotAsync(sortedSongs, "refresh_songs_only")
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

    /** 未排序的全量歌曲快照，供扫描/同步链路使用，避免在 IO 协程里做无谓的排序。 */
    suspend fun getAllSongsUnsortedSuspend(): List<AudioFile> {
        return loadSongsFromStorageSuspend()
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
    suspend fun upsertSongsSuspend(
        songs: List<AudioFile>,
        refreshLibrary: Boolean = true
    ): Int = withContext(Dispatchers.IO) {
        val distinctSongs = songs.distinctBy { it.libraryKey() }
        if (distinctSongs.isEmpty()) return@withContext 0

        val database = getDb()
        val folderDao = database.folderDao()
        val fileDao = database.folderFileDao()
        var changed = 0
        val t0 = System.currentTimeMillis()

        try {
            // 一次性载入已有文件和文件夹，避免 1000 首歌扫描时逐首/逐目录 N+1 查询。
            val allExisting = fileDao.getAll()
            val existingByKey = allExisting.associateBy { it.libraryKey() }
            val existingByPath = allExisting.associateBy { it.filePath }

            val targetFolderPaths = distinctSongs
                .map { it.path.substringBeforeLast("/") }
                .filter { it.isNotBlank() }
                .distinct()

            val existingFolders = folderDao.getAll().associateBy { it.path }.toMutableMap()
            val missingFolders = targetFolderPaths
                .filter { it !in existingFolders }
                .map { EntityConverter.pathToFolderEntity(it) }

            if (missingFolders.isNotEmpty()) {
                folderDao.insertBatch(missingFolders)
                existingFolders.clear()
                existingFolders.putAll(folderDao.getAll().associateBy { it.path })
            }

            val inserts = ArrayList<FolderFileEntity>(distinctSongs.size)
            val updates = ArrayList<FolderFileEntity>(distinctSongs.size)

            for (song in distinctSongs) {
                val folderPath = song.path.substringBeforeLast("/")
                val folderId = existingFolders[folderPath]?.id ?: continue
                val existing = existingByKey[song.libraryKey()]
                    ?: if (song.cueOffsetMs == 0L && song.cueTrackIndex == 0) existingByPath[song.path] else null
                val entity = mergePreservedFields(EntityConverter.audioFileToFolderFile(song, folderId), existing)
                if (existing == null) inserts += entity else updates += entity
            }

            database.withTransaction {
                if (inserts.isNotEmpty()) {
                    fileDao.insertBatch(inserts)
                    changed += inserts.size
                }
                if (updates.isNotEmpty()) {
                    fileDao.updateBatch(updates)
                    changed += updates.size
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "upsertSongsSuspend: error", e)
        }

        invalidateCache()
        if (refreshLibrary) {
            refreshAllSuspend()
        }
        AppLogger.d(
            TAG,
            "upsertSongsSuspend: changed=$changed, input=${songs.size}, refresh=$refreshLibrary, time=${System.currentTimeMillis() - t0}ms"
        )
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
            val sorted = sortSongs(updated, AppPreferences.Sort.songSortOrder)
            _songs.value = sorted
            saveStartupSnapshotAsync(sorted, "favorite_toggle")
        }
        return true
    }

    fun removeSong(path: String) {
        runBlocking { deleteSongsSuspend(listOf(AudioFile(path = path))) }
    }

    suspend fun deleteSongsSuspend(
        songs: List<AudioFile>,
        refreshLibrary: Boolean = true
    ) = withContext(Dispatchers.IO) {
        if (songs.isEmpty()) return@withContext
        try {
            val database = getDb()
            val dao = database.folderFileDao()
            // 单事务批量删除，避免逐首 delete 各自开/提交事务（v7c 性能补丁）。
            database.withTransaction {
                songs.forEach { song ->
                    if (song.cueOffsetMs > 0L || song.cueTrackIndex > 0) {
                        dao.deleteByPathAndCue(song.path, song.cueOffsetMs, song.cueTrackIndex)
                    } else {
                        dao.deleteByPath(song.path)
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "deleteSongsSuspend: error", e)
        }
        invalidateCache()
        if (refreshLibrary) {
            refreshAllSuspend()
        }
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
        startupSnapshotContext?.let { StartupSongSnapshotStore.clear(it) }
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
                    coverPath = list.firstOrNull { it.albumArtPath.isNotBlank() }?.coverKey ?: ""
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
                    coverPath = list.firstOrNull { it.albumArtPath.isNotBlank() }?.coverKey ?: list.first().coverKey,
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
