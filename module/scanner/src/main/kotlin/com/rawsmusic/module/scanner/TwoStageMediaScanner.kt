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
        data class QuickCompleted(
            val songs: List<AudioFile>,
            val found: Int,
            val timeMs: Long,
            val publishVisible: Boolean = true
        ) : Event()
        data class CacheLoaded(val cachedCount: Int) : Event()
        data class EnrichProgress(val processed: Int, val total: Int, val percent: Int,
                                  val cacheHits: Int, val enrichedCount: Int,
                                  val message: String = "补全音频信息") : Event()
        data class SongEnriched(val originalSongId: Long, val originalPath: String,
                                val songs: List<AudioFile>, val fromCache: Boolean) : Event()
        data class EnrichBatchCompleted(val songs: List<AudioFile>, val processed: Int, val total: Int,
                                        val cacheHits: Int, val enrichedCount: Int) : Event()
        data class FullyCompleted(
            val songs: List<AudioFile>,
            val found: Int,
            val timeMs: Long,
            val cacheHits: Int,
            val enrichedCount: Int,
            /** False when at least one configured source was unavailable or failed. */
            val canDeleteMissing: Boolean = true,
            /** Number of unchanged directories whose file metadata was reused. */
            val skippedDirectoryCount: Int = 0,
            /** Number of database rows reused by the cold-start fingerprint gate. */
            val baselineHits: Int = 0,
            val incremental: Boolean = false
        ) : Event()
        data class Error(val message: String) : Event()
    }

    enum class SourceMode {
        MEDIA_STORE,
        LEGACY_FILE_SYSTEM,
        SELECTED_FOLDERS;

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
        val incrementalMetadataOnly: Boolean = false,
        val publishQuickVisible: Boolean = true,
        val workerCount: Int = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
    )

    fun scan(
        context: Context,
        options: Options = Options(),
        baselineSongs: List<AudioFile> = emptyList()
    ): Flow<Event> = flow {
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
        var canDeleteMissing = true
        var skippedDirectoryCount = 0

        when (options.sourceMode) {
            SourceMode.MEDIA_STORE -> {
                val mediaStoreSongs = mutableListOf<AudioFile>()
                var mediaStoreTimeMs = 0L
                var mediaStoreSucceeded = false
                var mediaStoreError: String? = null

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
                            mediaStoreSucceeded = true
                        }
                        is ScanProgress.Error -> {
                            mediaStoreError = progress.message
                            Log.w(TAG, "MediaStore source failed; independent folder sources will continue: ${progress.message}")
                        }
                    }
                }

                val selectedPaths = options.customPaths
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .distinct()
                val accessiblePaths = selectedPaths.filter(::isReadableDirectory)
                val inaccessiblePaths = selectedPaths - accessiblePaths.toSet()
                val mediaStorePathKeys = mediaStoreSongs
                    .asSequence()
                    .map { it.path }
                    .filterNot { it.startsWith("content://", ignoreCase = true) }
                    .toSet()

                val directResult = if (accessiblePaths.isNotEmpty()) {
                    emit(Event.QuickProgress(0, accessiblePaths.size, "递归检查所选文件夹"))
                    runCatching {
                        MediaStoreScanner.scanCustomPathsByFileSystem(
                            context = appContext,
                            customPaths = accessiblePaths,
                            excludedPaths = mediaStorePathKeys,
                            quickScan = true
                        )
                    }
                } else {
                    Result.success(emptyList())
                }
                directResult.exceptionOrNull()?.let { Log.w(TAG, "selected-folder recursive source failed", it) }

                val safUris = AppPreferences.Scanner.musicFolderUris
                val safReport = if (safUris.isNotEmpty()) {
                    emit(Event.QuickProgress(0, safUris.size, "递归读取授权文件夹"))
                    SafMusicScanner.scanSelectedFoldersReport(appContext, quickScan = true)
                } else {
                    SafMusicScanner.ScanReport(emptyList(), emptySet(), emptyMap())
                }

                val hasConfiguredFolders = selectedPaths.isNotEmpty() || safUris.isNotEmpty()
                val hasUsableFolderSource =
                    (accessiblePaths.isNotEmpty() && directResult.isSuccess) || safReport.hasSuccessfulSource
                val hasUsableSource = if (hasConfiguredFolders) hasUsableFolderSource else mediaStoreSucceeded

                if (!hasUsableSource) {
                    hadError = true
                    val reason = buildString {
                        append("所选音乐文件夹无法读取")
                        mediaStoreError?.let { append("；MediaStore: ").append(it) }
                        if (inaccessiblePaths.isNotEmpty()) append("；不可访问路径: ").append(inaccessiblePaths.joinToString())
                        if (safReport.failedUris.isNotEmpty()) append("；SAF 授权失效: ").append(safReport.failedUris.keys.joinToString())
                    }
                    emit(Event.Error(reason))
                } else {
                    skippedDirectoryCount = safReport.skippedDirectoryCount
                    canDeleteMissing = mediaStoreSucceeded &&
                        directResult.isSuccess &&
                        inaccessiblePaths.isEmpty() &&
                        safReport.allConfiguredSourcesSucceeded

                    val merged = deduplicateBySourceIdentity(
                        (mediaStoreSongs + directResult.getOrDefault(emptyList()) + safReport.songs)
                            .filter { MediaStoreScanner.shouldInclude(it, options.scannerOptions) }
                    )
                    quickSongs.clear()
                    quickSongs.addAll(merged)
                    val elapsed = System.currentTimeMillis() - startTime
                    Log.d(
                        TAG,
                        "quick completed: media=${mediaStoreSongs.size} mediaOk=$mediaStoreSucceeded " +
                            "direct=${directResult.getOrDefault(emptyList()).size} paths=${accessiblePaths.size}/${selectedPaths.size} " +
                            "saf=${safReport.songs.size} safOk=${safReport.successfulUris.size}/${safUris.size} " +
                            "found=${merged.size} mediaTime=${mediaStoreTimeMs}ms totalTime=${elapsed}ms " +
                            "canDeleteMissing=$canDeleteMissing cacheSize=${cache?.size() ?: -1}"
                    )
                    emit(Event.QuickProgress(selectedPaths.size + safUris.size, selectedPaths.size + safUris.size, "文件夹递归扫描完成"))
                    emit(Event.QuickCompleted(merged, merged.size, elapsed, options.publishQuickVisible))
                }
            }

            SourceMode.LEGACY_FILE_SYSTEM, SourceMode.SELECTED_FOLDERS -> {
                val paths = options.customPaths.map { it.trim() }.filter { it.isNotBlank() }.distinct()
                val safUris = AppPreferences.Scanner.musicFolderUris
                if (paths.isEmpty() && safUris.isEmpty()) {
                    hadError = true
                    emit(Event.Error("请先选择音乐文件夹"))
                } else {
                    val directPermission = options.sourceMode == SourceMode.SELECTED_FOLDERS ||
                        LegacyFileAccess.hasPermission(appContext)
                    val accessiblePaths = if (directPermission) paths.filter(::isReadableDirectory) else emptyList()
                    val inaccessiblePaths = paths - accessiblePaths.toSet()
                    val emptyDirectReport = MediaStoreScanner.FileSystemScanReport(emptyList())
                    val directResult = runCatching {
                        MediaStoreScanner.scanCustomPathsByFileSystemReport(
                            appContext,
                            customPaths = accessiblePaths,
                            quickScan = true,
                            incrementalDirectoryScan = options.incrementalMetadataOnly,
                            baselineSongs = baselineSongs
                        )
                    }
                    val safReport = if (safUris.isNotEmpty()) {
                        SafMusicScanner.scanSelectedFoldersReport(
                            appContext,
                            quickScan = true,
                            incrementalDirectoryScan = options.incrementalMetadataOnly,
                            baselineSongs = baselineSongs
                        )
                    } else {
                        SafMusicScanner.ScanReport(emptyList(), emptySet(), emptyMap())
                    }
                    val hasUsableSource =
                        (accessiblePaths.isNotEmpty() && directResult.isSuccess) || safReport.hasSuccessfulSource

                    if (!hasUsableSource) {
                        hadError = true
                        emit(Event.Error(if (!directPermission) LegacyFileAccess.unavailableMessage() else "所选音乐文件夹无法读取"))
                    } else {
                        val directReport = directResult.getOrDefault(emptyDirectReport)
                        skippedDirectoryCount = directReport.skippedDirectoryCount + safReport.skippedDirectoryCount
                        canDeleteMissing = directResult.isSuccess &&
                            inaccessiblePaths.isEmpty() &&
                            safReport.allConfiguredSourcesSucceeded &&
                            directReport.failedDirectories.isEmpty()
                        val merged = deduplicateBySourceIdentity(
                            (directReport.songs + safReport.songs)
                                .filter { MediaStoreScanner.shouldInclude(it, options.scannerOptions) }
                        )
                        quickSongs.clear()
                        quickSongs.addAll(merged)
                        val elapsed = System.currentTimeMillis() - startTime
                        Log.d(TAG, "quick completed: source=Legacy paths=${accessiblePaths.size}/${paths.size} " +
                            "skippedDirs=${directReport.skippedDirectoryCount} failedDirs=${directReport.failedDirectories.size} " +
                            "saf=${safReport.successfulUris.size}/${safUris.size} found=${merged.size} " +
                            "canDeleteMissing=$canDeleteMissing time=${elapsed}ms")
                        emit(Event.QuickCompleted(merged, merged.size, elapsed, options.publishQuickVisible))
                    }
                }
            }
        }

        if (hadError || quickSongs.isEmpty()) {
            emit(
                Event.FullyCompleted(
                    songs = emptyList(),
                    found = 0,
                    timeMs = System.currentTimeMillis() - startTime,
                    cacheHits = 0,
                    enrichedCount = 0,
                    canDeleteMissing = canDeleteMissing && !hadError,
                    skippedDirectoryCount = skippedDirectoryCount,
                    incremental = options.incrementalMetadataOnly
                )
            )
            return@flow
        }

        val finalSongs = mutableListOf<AudioFile>()
        var processed = 0; var cacheHits = 0; var enrichedCount = 0; var dirtyCacheCount = 0
        var baselineHits = 0
        var lastCacheSaveMs = System.currentTimeMillis()
        val enrichWorkerCount = options.workerCount.coerceIn(1, 6)
        val enrichSemaphore = Semaphore(enrichWorkerCount)
        val baselineIndex = if (options.incrementalMetadataOnly) {
            IncrementalBaselineIndex(baselineSongs)
        } else {
            null
        }
        Log.d(TAG, "enrich start: total=${quickSongs.size} incremental=${options.incrementalMetadataOnly} batch=$ENRICH_BATCH_SIZE workers=$enrichWorkerCount cacheSize=${cache?.size() ?: -1}")

        for (batch in quickSongs.chunked(ENRICH_BATCH_SIZE)) {
            val batchStartMs = System.currentTimeMillis()
            val resolved = arrayOfNulls<EnrichedResult>(batch.size)
            val unresolved = ArrayList<Pair<Int, AudioFile>>()
            batch.forEachIndexed { index, song ->
                val baseline = baselineIndex?.reusableSongs(song)
                if (baseline != null) {
                    resolved[index] = EnrichedResult(song, baseline, fromCache = true, reusedBaseline = true)
                } else {
                    unresolved += index to song
                }
            }
            coroutineScope {
                unresolved.map { (index, song) ->
                    async(Dispatchers.IO) {
                        val result = enrichSemaphore.withPermit {
                            val cached = cache?.get(song)
                            if (cached != null) {
                                val expanded = (if (options.expandCueTracks) MediaStoreScanner.expandCueTracks(cached) else listOf(cached))
                                    .filter { MediaStoreScanner.shouldInclude(it, options.scannerOptions) }
                                EnrichedResult(song, expanded, fromCache = true, reusedBaseline = false)
                            } else {
                                val enriched = if (song.path.startsWith("content://", ignoreCase = true)) {
                                    SafMusicScanner.enrichSong(appContext, song)
                                } else {
                                    MediaStoreScanner.enrichSong(song)
                                }
                                cache?.put(enriched)
                                val expanded = (if (options.expandCueTracks) MediaStoreScanner.expandCueTracks(enriched) else listOf(enriched))
                                    .filter { MediaStoreScanner.shouldInclude(it, options.scannerOptions) }
                                EnrichedResult(song, expanded, fromCache = false, reusedBaseline = false)
                            }
                        }
                        index to result
                    }
                }.awaitAll().forEach { (index, result) -> resolved[index] = result }
            }
            val results = resolved.filterNotNull()

            val batchSongs = ArrayList<AudioFile>(results.sumOf { it.songs.size })
            for (r in results) {
                finalSongs.addAll(r.songs)
                if (!r.reusedBaseline) batchSongs.addAll(r.songs)
                processed++
                if (r.reusedBaseline) baselineHits++
                if (r.fromCache) cacheHits++ else { enrichedCount++; dirtyCacheCount++ }
                if (options.emitEachSong) {
                    emit(Event.SongEnriched(r.original.id, r.original.path, r.songs, r.fromCache))
                }
            }

            emit(Event.EnrichBatchCompleted(batchSongs, processed, quickSongs.size, cacheHits, enrichedCount))
            val batchTimeMs = System.currentTimeMillis() - batchStartMs
            val avgPerSong = if (batch.isNotEmpty()) batchTimeMs.toFloat() / batch.size else 0f
            Log.d(TAG, "enrich batch: processed=$processed/${quickSongs.size} changedBatch=${batchSongs.size} baselineHits=$baselineHits cacheHits=$cacheHits enriched=$enrichedCount time=${batchTimeMs}ms avg=${"%.1f".format(avgPerSong)}ms/song")

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

        Log.d(TAG, "fully completed: found=${finalSongs.size} totalTime=${System.currentTimeMillis() - startTime}ms baselineHits=$baselineHits cacheHits=$cacheHits enriched=$enrichedCount")
        emit(
            Event.FullyCompleted(
                songs = finalSongs,
                found = finalSongs.size,
                timeMs = System.currentTimeMillis() - startTime,
                cacheHits = cacheHits,
                enrichedCount = enrichedCount,
                canDeleteMissing = canDeleteMissing,
                skippedDirectoryCount = skippedDirectoryCount,
                baselineHits = baselineHits,
                incremental = options.incrementalMetadataOnly
            )
        )
    }.flowOn(Dispatchers.IO)

    private fun deduplicateBySourceIdentity(songs: List<AudioFile>): List<AudioFile> {
        return ScanSourceDeduplicator.deduplicate(songs)
    }

    private data class EnrichedResult(
        val original: AudioFile,
        val songs: List<AudioFile>,
        val fromCache: Boolean,
        val reusedBaseline: Boolean
    )

    private fun isReadableDirectory(path: String): Boolean {
        val root = java.io.File(path)
        return root.exists() && root.isDirectory && runCatching { root.listFiles() }.getOrNull() != null
    }
}
