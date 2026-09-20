package com.rawsmusic.module.scanner

import android.content.Context
import com.rawsmusic.core.common.model.AudioFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

class LibraryScanCoordinator(
    private val repository: AudioLibraryRepository
) {
    sealed class Event {
        data class ScannerEvent(val event: TwoStageMediaScanner.Event) : Event()
        data class DatabaseSyncStarted(
            val oldCount: Int,
            val newCount: Int,
            val phase: SyncPhase = SyncPhase.FINAL
        ) : Event()
        data class DatabaseSyncCompleted(
            val added: Int,
            val updated: Int,
            val upserted: Int,
            val deleted: Int,
            val unchanged: Int,
            val phase: SyncPhase = SyncPhase.FINAL
        ) : Event()
        /**
         * Quick MediaStore results have already been written/refreshed and can be
         * shown immediately. Detailed FFmpeg/TagLib enrichment may continue in the
         * background after this event.
         */
        data class VisibleCompleted(val songs: List<AudioFile>, val found: Int, val timeMs: Long) : Event()
        data class Completed(
            val songs: List<AudioFile>,
            val timeMs: Long,
            val incremental: Boolean = false,
            val skippedDirectoryCount: Int = 0,
            val baselineHits: Int = 0,
            val metadataParsed: Int = 0
        ) : Event()
        data class Error(val message: String) : Event()
    }

    enum class SyncPhase { QUICK_VISIBLE, ENRICHED_BATCH, FINAL }

    fun scanAndSync(
        context: Context,
        options: TwoStageMediaScanner.Options = TwoStageMediaScanner.Options()
    ): Flow<Event> = flow {
        val start = System.currentTimeMillis()
        var finalSongs: List<AudioFile> = emptyList()
        var quickSongs: List<AudioFile> = emptyList()
        var hadError = false
        var canDeleteMissing = true
        var incremental = false
        var skippedDirectoryCount = 0
        var baselineHits = 0
        var metadataParsed = 0
        val lazySync = LibraryScanLazySync(repository)
        val baselineSongs = if (options.incrementalMetadataOnly) {
            repository.getAllSongsForSync()
        } else {
            emptyList()
        }

        android.util.Log.d(TAG, "scanAndSync start: lazy=true incremental=${options.incrementalMetadataOnly} baseline=${baselineSongs.size}")

        TwoStageMediaScanner.scan(
            context = context.applicationContext,
            options = options,
            baselineSongs = baselineSongs
        ).collect { scannerEvent ->
            emit(Event.ScannerEvent(scannerEvent))
            when (scannerEvent) {
                is TwoStageMediaScanner.Event.QuickCompleted -> {
                    quickSongs = scannerEvent.songs
                    if (scannerEvent.publishVisible) {
                        emit(Event.DatabaseSyncStarted(0, scannerEvent.found, SyncPhase.QUICK_VISIBLE))
                        val t0 = System.currentTimeMillis()
                        val result = lazySync.syncQuickVisible(scannerEvent.songs)
                        android.util.Log.d(
                            TAG,
                            "lazy quick sync: visible=${scannerEvent.found} changed=${result.changed} time=${System.currentTimeMillis() - t0}ms"
                        )
                        emit(
                            Event.DatabaseSyncCompleted(
                                added = result.changed,
                                updated = 0,
                                upserted = result.changed,
                                deleted = 0,
                                unchanged = (scannerEvent.found - result.changed).coerceAtLeast(0),
                                phase = SyncPhase.QUICK_VISIBLE
                            )
                        )
                        emit(Event.VisibleCompleted(scannerEvent.songs, scannerEvent.found, System.currentTimeMillis() - start))
                    }
                }

                is TwoStageMediaScanner.Event.EnrichBatchCompleted -> {
                    if (options.incrementalMetadataOnly) return@collect
                    val t0 = System.currentTimeMillis()
                    val result = lazySync.enqueueEnriched(scannerEvent.songs)
                    if (result != null) {
                        android.util.Log.d(
                            TAG,
                            "lazy enrich sync: batch=${result.requested} time=${System.currentTimeMillis() - t0}ms processed=${scannerEvent.processed}/${scannerEvent.total}"
                        )
                        emit(Event.DatabaseSyncStarted(0, result.requested, SyncPhase.ENRICHED_BATCH))
                        emit(
                            Event.DatabaseSyncCompleted(
                                added = 0,
                                updated = result.changed,
                                upserted = result.changed,
                                deleted = 0,
                                unchanged = 0,
                                phase = SyncPhase.ENRICHED_BATCH
                            )
                        )
                    }
                }

                is TwoStageMediaScanner.Event.FullyCompleted -> {
                    finalSongs = scannerEvent.songs
                    canDeleteMissing = scannerEvent.canDeleteMissing
                    incremental = scannerEvent.incremental
                    skippedDirectoryCount = scannerEvent.skippedDirectoryCount
                    baselineHits = scannerEvent.baselineHits
                    metadataParsed = scannerEvent.enrichedCount
                }

                is TwoStageMediaScanner.Event.Error -> {
                    hadError = true
                    emit(Event.Error(scannerEvent.message))
                }

                else -> Unit
            }
        }

        if (hadError) return@flow

        val syncInput = finalSongs.ifEmpty { quickSongs }
        android.util.Log.d(TAG, "final sync started: new=${syncInput.size}")
        emit(Event.DatabaseSyncStarted(oldCount = 0, newCount = syncInput.size, phase = SyncPhase.FINAL))

        val tFinal = System.currentTimeMillis()
        val result = lazySync.syncFinal(syncInput, deleteMissing = canDeleteMissing)
        android.util.Log.d(
            TAG,
            "final sync completed: added=${result.added}, updated=${result.updated}, upserts=${result.upserted}, deletes=${result.deleted}, unchanged=${result.unchanged}, time=${System.currentTimeMillis() - tFinal}ms"
        )

        emit(
            Event.DatabaseSyncCompleted(
                added = result.added,
                updated = result.updated,
                upserted = result.upserted,
                deleted = result.deleted,
                unchanged = result.unchanged,
                phase = SyncPhase.FINAL
            )
        )
        emit(
            Event.Completed(
                songs = syncInput,
                timeMs = System.currentTimeMillis() - start,
                incremental = incremental,
                skippedDirectoryCount = skippedDirectoryCount,
                baselineHits = baselineHits,
                metadataParsed = metadataParsed
            )
        )
    }.flowOn(Dispatchers.IO)

    companion object {
        private const val TAG = "LibraryScanCoordinator"
    }
}
