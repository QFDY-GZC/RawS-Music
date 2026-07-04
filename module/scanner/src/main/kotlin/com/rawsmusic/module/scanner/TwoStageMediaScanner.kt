package com.rawsmusic.module.scanner

import android.content.Context
import com.rawsmusic.core.common.model.AudioFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlin.math.roundToInt

object TwoStageMediaScanner {

    private const val ENRICH_BATCH_SIZE = 32
    private const val CACHE_SAVE_BATCH_SIZE = 64

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
        data class FullyCompleted(val songs: List<AudioFile>, val found: Int, val timeMs: Long,
                                  val cacheHits: Int, val enrichedCount: Int) : Event()
        data class Error(val message: String) : Event()
    }

    data class Options(
        val scannerOptions: MediaStoreScanner.ScanOptions = MediaStoreScanner.ScanOptions.fromPreferences(),
        val customPaths: List<String> = emptyList(),
        val expandCueTracks: Boolean = true,
        val emitEachSong: Boolean = true,
        val usePersistentCache: Boolean = true,
        val saveCacheAtEnd: Boolean = true,
        val workerCount: Int = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
    )

    fun scan(context: Context, options: Options = Options()): Flow<Event> = flow {
        val appContext = context.applicationContext
        val startTime = System.currentTimeMillis()

        val cache = if (options.usePersistentCache) PersistentMetadataCache.load(appContext) else null
        if (cache != null) emit(Event.CacheLoaded(cache.size()))

        val quickSongs = mutableListOf<AudioFile>()
        var hadError = false

        MediaStoreScanner.scan(
            context = appContext, customPaths = options.customPaths,
            quickScan = true, options = options.scannerOptions.copy(expandCueTracks = false)
        ).collect { progress ->
            when (progress) {
                is ScanProgress.Started -> emit(Event.Started(progress.totalEstimated))
                is ScanProgress.Progress -> emit(Event.QuickProgress(progress.scanned, progress.total, progress.message ?: "读取媒体库"))
                is ScanProgress.Completed -> {
                    quickSongs.clear()
                    quickSongs.addAll(progress.songs)
                    emit(Event.QuickCompleted(progress.songs, progress.found, progress.timeMs))
                }
                is ScanProgress.Error -> { hadError = true; emit(Event.Error(progress.message)) }
            }
        }

        if (hadError || quickSongs.isEmpty()) {
            emit(Event.FullyCompleted(emptyList(), 0, System.currentTimeMillis() - startTime, 0, 0))
            return@flow
        }

        val finalSongs = mutableListOf<AudioFile>()
        var processed = 0; var cacheHits = 0; var enrichedCount = 0; var dirtyCacheCount = 0

        for (batch in quickSongs.chunked(ENRICH_BATCH_SIZE)) {
            val results = coroutineScope {
                batch.map { song ->
                    async(Dispatchers.IO) {
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
                }.awaitAll()
            }

            for (r in results) {
                finalSongs.addAll(r.songs); processed++
                if (r.fromCache) cacheHits++ else { enrichedCount++; dirtyCacheCount++ }
                if (options.emitEachSong) {
                    emit(Event.SongEnriched(r.original.id, r.original.path, r.songs, r.fromCache))
                }
            }

            if (cache != null && options.saveCacheAtEnd && dirtyCacheCount >= CACHE_SAVE_BATCH_SIZE) {
                cache.save(); dirtyCacheCount = 0
            }

            val pct = ((processed.toFloat() / quickSongs.size) * 100f).roundToInt().coerceIn(0, 100)
            emit(Event.EnrichProgress(processed, quickSongs.size, pct, cacheHits, enrichedCount))
        }

        if (cache != null && options.saveCacheAtEnd) cache.save()

        emit(Event.FullyCompleted(finalSongs, finalSongs.size, System.currentTimeMillis() - startTime, cacheHits, enrichedCount))
    }.flowOn(Dispatchers.IO)

    private data class EnrichedResult(val original: AudioFile, val songs: List<AudioFile>, val fromCache: Boolean)
}
