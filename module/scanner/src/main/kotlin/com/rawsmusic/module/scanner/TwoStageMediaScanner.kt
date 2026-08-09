package com.rawsmusic.module.scanner

import android.content.Context
import android.util.Log
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.module.data.prefs.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.math.roundToInt

object TwoStageMediaScanner {

    private const val TAG = "TwoStageScanner"
    private const val ENRICH_BATCH_SIZE = 96
    private const val CACHE_SAVE_BATCH_SIZE = 512
    private const val CACHE_SAVE_MIN_INTERVAL_MS = 15_000L

    sealed class Event {
        data class Started(val totalEstimated: Int) : Event()
        data class QuickProgress(val scanned: Int, val total: Int, val message: String = "读取媒体库") : Event()
        data class QuickCompleted(val songs: List<AudioFile>, val found: Int, val timeMs: Long) : Event()
        data class CacheLoaded(val cachedCount: Int) : Event()
        data class EnrichProgress(val processed: Int, val total: Int, val percent: Int,
                                  val cacheHits: Int, val enrichedCount: Int,
                                  val message: String = "补全音频信息") : Event()
        data class SongEnriched(val originalSongId: Long, val originalPath: String,
                                val songs: List<AudioFile>, val fromCache: Boolean) : Event()
        data class EnrichBatchCompleted(val songs: List<AudioFile>, val processed: Int, val total: Int,
                                        val cacheHits: Int, val enrichedCount: Int) : Event()
        data class FullyCompleted(val songs: List<AudioFile>, val found: Int, val timeMs: Long,
                                  val cacheHits: Int, val enrichedCount: Int) : Event()
        data class Error(val message: String) : Event()
    }

    enum class SourceMode {
        MEDIA_STORE,
        LEGACY_FILE_SYSTEM;

        companion object {
            fun fromPreferences(): SourceMode =
                if (AppPreferences.Scanner.legacyFileAccessEnabled) LEGACY_FILE_SYSTEM else MEDIA_STORE
        }
    }

    data class Options(
        val scannerOptions: MediaStoreScanner.ScanOptions = MediaStoreScanner.ScanOptions.fromPreferences(),
        val customPaths: List<String> = emptyList(),
        val sourceMode: SourceMode = SourceMode.fromPreferences(),
        val expandCueTracks: Boolean = true,
        val emitEachSong: Boolean = false,
        val usePersistentCache: Boolean = true,
        val saveCacheAtEnd: Boolean = true,
        val workerCount: Int = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
    )

    fun scan(context: Context, options: Options = Options()): Flow<Event> = flow {
        val appContext = context.applicationContext
        val startTime = System.currentTimeMillis()

        val cacheLoadStart = System.currentTimeMillis()
        val cache = if (options.usePersistentCache) PersistentMetadataCache.load(appContext) else null
        if (cache != null) {
            Log.d(TAG, "cache loaded: size=${cache.size()} time=${System.currentTimeMillis() - cacheLoadStart}ms")
            emit(Event.CacheLoaded(cache.size()))
        }

        val quickSongs = mutableListOf<AudioFile>()
        var hadError = false

        when (options.sourceMode) {
            SourceMode.MEDIA_STORE -> {
                val mediaStoreSongs = mutableListOf<AudioFile>()
                var mediaStoreTimeMs = 0L

                MediaStoreScanner.scan(
                    context = appContext,
                    customPaths = options.customPaths,
                    quickScan = true,
                    options = options.scannerOptions.copy(expandCueTracks = false)
                ).collect { progress ->
                    when (progress) {
                        is ScanProgress.Started -> emit(Event.Started(progress.totalEstimated))
                        is ScanProgress.Progress -> emit(
                            Event.QuickProgress(
                                progress.scanned,
                                progress.total,
                                progress.message ?: "读取媒体库"
                            )
                        )
                        is ScanProgress.Completed -> {
                            mediaStoreSongs.clear()
                            mediaStoreSongs.addAll(progress.songs)
                            mediaStoreTimeMs = progress.timeMs
                        }
                        is ScanProgress.Error -> {
                            hadError = true
                            emit(Event.Error(progress.message))
                        }
                    }
                }

                if (!hadError) {
                    val selectedPaths = options.customPaths
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                        .distinct()
                    val mediaStorePathKeys = mediaStoreSongs
                        .asSequence()
                        .map { it.path }
                        .filterNot { it.startsWith("content://", ignoreCase = true) }
                        .toSet()

                    // Folder selection means subtree selection. MediaStore can be stale or incomplete on
                    // vendor builds, so enumerate readable selected folders and only parse files missing
                    // from the MediaStore result. This preserves normal MediaStore behavior while fixing
                    // nested folders that have not been indexed yet.
                    val recursivePathSongs = if (selectedPaths.isNotEmpty()) {
                        emit(Event.QuickProgress(0, selectedPaths.size, "递归检查所选文件夹"))
                        runCatching {
                            MediaStoreScanner.scanCustomPathsByFileSystem(
                                context = appContext,
                                customPaths = selectedPaths,
                                excludedPaths = mediaStorePathKeys
                            )
                        }.onFailure { error ->
                            Log.w(TAG, "selected-folder recursive fallback failed", error)
                        }.getOrDefault(emptyList())
                    } else {
                        emptyList()
                    }

                    // Tree URIs are the authoritative recursion path under scoped storage. They are
                    // persisted by the folder dialog and may expose descendants that neither DATA nor
                    // RELATIVE_PATH queries return on some devices.
                    val safSongs = if (AppPreferences.Scanner.musicFolderUris.isNotEmpty()) {
                        emit(Event.QuickProgress(0, AppPreferences.Scanner.musicFolderUris.size, "递归读取授权文件夹"))
                        runCatching { SafMusicScanner.scanSelectedFolders(appContext) }
                            .onFailure { error -> Log.w(TAG, "selected-folder SAF recursion failed", error) }
                            .getOrDefault(emptyList())
                    } else {
                        emptyList()
                    }

                    val merged = deduplicateBySourceIdentity(
                        (mediaStoreSongs + recursivePathSongs + safSongs)
                            .filter { MediaStoreScanner.shouldInclude(it, options.scannerOptions) }
                    )
                    quickSongs.clear()
                    quickSongs.addAll(merged)
                    val elapsed = System.currentTimeMillis() - startTime
                    val cacheState = cache?.size() ?: -1
                    Log.d(
                        TAG,
                        "quick completed: source=MediaStore media=${mediaStoreSongs.size} " +
                            "recursive=${recursivePathSongs.size} saf=${safSongs.size} " +
                            "found=${merged.size} mediaTime=${mediaStoreTimeMs}ms totalTime=${elapsed}ms " +
                            "cacheSize=$cacheState visibleNow=true"
                    )
                    emit(Event.QuickProgress(selectedPaths.size, selectedPaths.size, "文件夹递归扫描完成"))
                    emit(Event.QuickCompleted(merged, merged.size, elapsed))
                }
            }

            SourceMode.LEGACY_FILE_SYSTEM -> {
                if (!LegacyFileAccess.hasPermission(appContext)) {
                    hadError = true
                    emit(Event.Error(LegacyFileAccess.unavailableMessage()))
                } else {
                    val paths = options.customPaths
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                        .distinct()
                    val safUris = AppPreferences.Scanner.musicFolderUris
                    if (paths.isEmpty() && safUris.isEmpty()) {
                        hadError = true
                        emit(Event.Error("请先选择音乐文件夹"))
                    } else {
                        val accessiblePaths = paths.filter { rawPath ->
                            val root = java.io.File(rawPath)
                            root.exists() && root.isDirectory && runCatching { root.listFiles() }.getOrNull() != null
                        }
                        if (paths.isNotEmpty() && accessiblePaths.isEmpty() && safUris.isEmpty()) {
                            hadError = true
                            emit(Event.Error("所选音乐文件夹无法读取，请检查所有文件访问权限"))
                        } else {
                            val sourceCount = accessiblePaths.size + safUris.size
                            emit(Event.Started(sourceCount))
                            emit(Event.QuickProgress(0, sourceCount, "递归扫描文件夹"))
                            val directSongs = MediaStoreScanner.scanCustomPathsByFileSystem(appContext, accessiblePaths)
                            val safSongs = if (safUris.isNotEmpty()) {
                                runCatching { SafMusicScanner.scanSelectedFolders(appContext) }
                                    .onFailure { Log.w(TAG, "legacy SAF merge failed", it) }
                                    .getOrDefault(emptyList())
                            } else {
                                emptyList()
                            }
                            val merged = deduplicateBySourceIdentity(
                                (directSongs + safSongs).filter { MediaStoreScanner.shouldInclude(it, options.scannerOptions) }
                            )
                            quickSongs.clear()
                            quickSongs.addAll(merged)
                            val elapsed = System.currentTimeMillis() - startTime
                            Log.d(
                                TAG,
                                "quick completed: source=Legacy paths=${accessiblePaths.size}/${paths.size} direct=${directSongs.size} saf=${safSongs.size} found=${merged.size} time=${elapsed}ms"
                            )
                            emit(Event.QuickProgress(sourceCount, sourceCount, "文件夹递归扫描完成"))
                            emit(Event.QuickCompleted(merged, merged.size, elapsed))
                        }
                    }
                }
            }
        }

        if (hadError || quickSongs.isEmpty()) {
            emit(Event.FullyCompleted(emptyList(), 0, System.currentTimeMillis() - startTime, 0, 0))
            return@flow
        }

        val finalSongs = mutableListOf<AudioFile>()
        var processed = 0; var cacheHits = 0; var enrichedCount = 0; var dirtyCacheCount = 0
        var lastCacheSaveMs = System.currentTimeMillis()
        val enrichWorkerCount = options.workerCount.coerceIn(1, 6)
        val enrichSemaphore = Semaphore(enrichWorkerCount)
        Log.d(TAG, "enrich start: total=${quickSongs.size} batch=$ENRICH_BATCH_SIZE workers=$enrichWorkerCount cacheSize=${cache?.size() ?: -1}")

        for (batch in quickSongs.chunked(ENRICH_BATCH_SIZE)) {
            val batchStartMs = System.currentTimeMillis()
            val results = coroutineScope {
                batch.map { song ->
                    async(Dispatchers.IO) {
                        enrichSemaphore.withPermit {
                            val cached = cache?.get(song)
                            if (cached != null) {
                                val expanded = if (options.expandCueTracks) MediaStoreScanner.expandCueTracks(cached) else listOf(cached)
                                EnrichedResult(song, expanded, fromCache = true)
                            } else {
                                val enriched = MediaStoreScanner.enrichSong(song)
                                cache?.put(enriched)
                                val expanded = if (options.expandCueTracks) MediaStoreScanner.expandCueTracks(enriched) else listOf(enriched)
                                EnrichedResult(song, expanded, fromCache = false)
                            }
                        }
                    }
                }.awaitAll()
            }

            val batchSongs = ArrayList<AudioFile>(results.sumOf { it.songs.size })
            for (r in results) {
                finalSongs.addAll(r.songs)
                batchSongs.addAll(r.songs)
                processed++
                if (r.fromCache) cacheHits++ else { enrichedCount++; dirtyCacheCount++ }
                if (options.emitEachSong) {
                    emit(Event.SongEnriched(r.original.id, r.original.path, r.songs, r.fromCache))
                }
            }

            emit(Event.EnrichBatchCompleted(batchSongs, processed, quickSongs.size, cacheHits, enrichedCount))
            val batchTimeMs = System.currentTimeMillis() - batchStartMs
            val avgPerSong = if (batch.isNotEmpty()) batchTimeMs.toFloat() / batch.size else 0f
            Log.d(TAG, "enrich batch: processed=$processed/${quickSongs.size} batchSongs=${batchSongs.size} cacheHits=$cacheHits enriched=$enrichedCount time=${batchTimeMs}ms avg=${"%.1f".format(avgPerSong)}ms/song")

            if (cache != null && options.saveCacheAtEnd && dirtyCacheCount >= CACHE_SAVE_BATCH_SIZE) {
                val now = System.currentTimeMillis()
                if (now - lastCacheSaveMs >= CACHE_SAVE_MIN_INTERVAL_MS) {
                    cache.save()
                    dirtyCacheCount = 0
                    lastCacheSaveMs = now
                }
            }

            val pct = ((processed.toFloat() / quickSongs.size) * 100f).roundToInt().coerceIn(0, 100)
            emit(Event.EnrichProgress(processed, quickSongs.size, pct, cacheHits, enrichedCount))
        }

        if (cache != null && options.saveCacheAtEnd) cache.save()

        Log.d(TAG, "fully completed: found=${finalSongs.size} totalTime=${System.currentTimeMillis() - startTime}ms cacheHits=$cacheHits enriched=$enrichedCount")
        emit(Event.FullyCompleted(finalSongs, finalSongs.size, System.currentTimeMillis() - startTime, cacheHits, enrichedCount))
    }.flowOn(Dispatchers.IO)

    private fun deduplicateBySourceIdentity(songs: List<AudioFile>): List<AudioFile> {
        val seenPaths = HashSet<String>(songs.size)
        val fingerprintKinds = HashMap<String, MutableSet<Boolean>>()

        return songs.filter { song ->
            val pathKey = song.path.trim().lowercase()
            val cueSuffix = if (song.cueOffsetMs > 0L || song.cueTrackIndex > 0) {
                "@cue${song.cueOffsetMs}_${song.cueTrackIndex}"
            } else {
                ""
            }
            val exactKey = pathKey + cueSuffix
            if (exactKey.isBlank() || !seenPaths.add(exactKey)) {
                false
            } else {
                val isContentUri = pathKey.startsWith("content://")
                val fingerprint = buildString {
                    append(song.title.trim().lowercase())
                    append('|').append(song.artist.trim().lowercase())
                    append('|').append(song.album.trim().lowercase())
                    append('|').append(song.duration)
                    append('|').append(song.fileSize)
                    append(cueSuffix)
                }
                val existingKinds = fingerprintKinds.getOrPut(fingerprint) { mutableSetOf() }
                val duplicateAcrossAccessModes = existingKinds.isNotEmpty() && isContentUri !in existingKinds
                existingKinds += isContentUri
                !duplicateAcrossAccessModes
            }
        }
    }

    private data class EnrichedResult(val original: AudioFile, val songs: List<AudioFile>, val fromCache: Boolean)
}
