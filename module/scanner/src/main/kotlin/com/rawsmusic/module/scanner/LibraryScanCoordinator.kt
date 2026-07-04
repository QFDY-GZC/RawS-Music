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
        data class DatabaseSyncStarted(val oldCount: Int, val newCount: Int) : Event()
        data class DatabaseSyncCompleted(
            val added: Int,
            val updated: Int,
            val upserted: Int,
            val deleted: Int,
            val unchanged: Int
        ) : Event()
        data class Completed(val songs: List<AudioFile>, val timeMs: Long) : Event()
        data class Error(val message: String) : Event()
    }

    fun scanAndSync(
        context: Context,
        options: TwoStageMediaScanner.Options = TwoStageMediaScanner.Options()
    ): Flow<Event> = flow {
        val start = System.currentTimeMillis()
        var finalSongs: List<AudioFile> = emptyList()

        var hadError = false
        TwoStageMediaScanner.scan(
            context = context.applicationContext, options = options
        ).collect { scannerEvent ->
            emit(Event.ScannerEvent(scannerEvent))
            if (scannerEvent is TwoStageMediaScanner.Event.FullyCompleted) {
                finalSongs = scannerEvent.songs
            }
            if (scannerEvent is TwoStageMediaScanner.Event.Error) {
                hadError = true
                emit(Event.Error(scannerEvent.message))
            }
        }

        if (hadError) return@flow

        val oldSongs = repository.getAllSongs()
        android.util.Log.d(TAG, "sync started: old=${oldSongs.size}, new=${finalSongs.size}")
        emit(Event.DatabaseSyncStarted(oldCount = oldSongs.size, newCount = finalSongs.size))

        val oldKeys = oldSongs.mapTo(HashSet()) { it.scanStableKey() }
        val newKeys = finalSongs.mapTo(HashSet()) { it.scanStableKey() }
        val addedCount = newKeys.count { it !in oldKeys }

        val delta = LibrarySyncPlanner.calculateDelta(
            oldSongs = oldSongs,
            newSongs = finalSongs
        )

        val updatedCount = (delta.upserts.size - addedCount).coerceAtLeast(0)

        if (delta.deletes.isNotEmpty()) {
            repository.deleteSongs(delta.deletes)
        }

        if (delta.upserts.isNotEmpty()) {
            repository.upsertSongs(delta.upserts)
        }

        android.util.Log.d(
            TAG,
            "sync completed: added=$addedCount, updated=$updatedCount, upserts=${delta.upserts.size}, deletes=${delta.deletes.size}, unchanged=${delta.unchanged.size}"
        )

        emit(
            Event.DatabaseSyncCompleted(
                added = addedCount,
                updated = updatedCount,
                upserted = delta.upserts.size,
                deleted = delta.deletes.size,
                unchanged = delta.unchanged.size
            )
        )
        emit(Event.Completed(songs = finalSongs, timeMs = System.currentTimeMillis() - start))
    }.flowOn(Dispatchers.IO)

    companion object {
        private const val TAG = "LibraryScanCoordinator"
    }
}

private fun AudioFile.scanStableKey(): String {
    return if (cueTrackIndex > 0 || cueOffsetMs > 0L) {
        "cue|$path|$cueTrackIndex|$cueOffsetMs"
    } else {
        "file|$path"
    }
}
