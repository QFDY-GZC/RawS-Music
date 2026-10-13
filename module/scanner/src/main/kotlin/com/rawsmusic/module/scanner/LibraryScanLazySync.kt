package com.rawsmusic.module.scanner

import com.rawsmusic.core.common.model.AudioFile
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * project-style scan synchronizer.
 *
 * The scanner emits a fast MediaStore list first, then lazily enriches technical
 * metadata. This helper makes the fast list visible in Room immediately and
 * defers expensive detailed updates into coarse background batches.
 */
class LibraryScanLazySync(
    private val repository: AudioLibraryRepository,
    private val enrichedFlushSize: Int = DEFAULT_ENRICHED_FLUSH_SIZE,
    private val enrichedFlushIntervalMs: Long = DEFAULT_ENRICHED_FLUSH_INTERVAL_MS
) {
    private val pendingEnriched = LinkedHashMap<String, AudioFile>()
    private var lastEnrichedFlushMs = 0L
    private var unpublishedChanges = false
    private val addedKeys = HashSet<String>()
    private val updatedKeys = HashSet<String>()

    private fun account(oldSongs: List<AudioFile>, changedSongs: List<AudioFile>) {
        val oldKeys = oldSongs.mapTo(HashSet()) { it.lazySyncKey() }
        for (song in changedSongs) {
            val key = song.lazySyncKey()
            if (key !in oldKeys) addedKeys += key
            else if (key !in addedKeys) updatedKeys += key
        }
    }

    suspend fun syncQuickVisible(songs: List<AudioFile>): BatchResult {
        if (songs.isEmpty()) return BatchResult(phase = Phase.QUICK_VISIBLE, requested = 0, changed = 0)

        val oldSongs = repository.getAllSongsForSync()
        val quickUpserts = LibrarySyncPlanner.calculateQuickVisibleUpserts(
            oldSongs = oldSongs,
            quickSongs = songs
        )

        if (quickUpserts.isNotEmpty()) {
            account(oldSongs, quickUpserts)
            repository.upsertSongsForScan(quickUpserts, refreshLibrary = true)
        }

        return BatchResult(
            phase = Phase.QUICK_VISIBLE,
            requested = songs.size,
            changed = quickUpserts.size
        )
    }

    suspend fun enqueueEnriched(songs: List<AudioFile>, force: Boolean = false): BatchResult? {
        if (songs.isEmpty()) return null
        for (song in songs) {
            pendingEnriched[song.lazySyncKey()] = song
        }

        val now = System.currentTimeMillis()
        val shouldFlush = force ||
            pendingEnriched.size >= enrichedFlushSize ||
            (lastEnrichedFlushMs > 0L && now - lastEnrichedFlushMs >= enrichedFlushIntervalMs)

        return if (shouldFlush) flushEnriched(forceRefresh = false) else null
    }

    suspend fun flushEnriched(forceRefresh: Boolean): BatchResult? {
        if (pendingEnriched.isEmpty()) return null
        coroutineContext.ensureActive()

        val batch = pendingEnriched.values.toList()
        pendingEnriched.clear()
        lastEnrichedFlushMs = System.currentTimeMillis()

        val oldSongs = repository.getAllSongsForSync()
        val changes = LibrarySyncPlanner.calculateDelta(oldSongs, batch).upserts
        if (changes.isNotEmpty()) {
            account(oldSongs, changes)
            repository.upsertSongsForScan(changes, refreshLibrary = forceRefresh)
            unpublishedChanges = unpublishedChanges || !forceRefresh
        }
        return BatchResult(
            phase = Phase.ENRICHED_BATCH,
            requested = batch.size,
            changed = changes.size
        )
    }

    suspend fun syncFinal(
        songs: List<AudioFile>,
        deleteMissing: Boolean = true
    ): FinalResult {
        flushEnriched(forceRefresh = false)
        coroutineContext.ensureActive()

        val oldSongs = repository.getAllSongsForSync()
        val delta = LibrarySyncPlanner.calculateDelta(oldSongs = oldSongs, newSongs = songs)

        if (deleteMissing && delta.deletes.isNotEmpty()) {
            repository.deleteSongsForScan(delta.deletes, refreshLibrary = false)
            unpublishedChanges = true
        }
        if (delta.upserts.isNotEmpty()) {
            account(oldSongs, delta.upserts)
            repository.upsertSongsForScan(delta.upserts, refreshLibrary = false)
            unpublishedChanges = true
        }
        val hasChanges = unpublishedChanges || addedKeys.isNotEmpty() || updatedKeys.isNotEmpty()
        if (unpublishedChanges) {
            repository.refreshAfterScanSync()
            unpublishedChanges = false
        }

        return FinalResult(
            added = addedKeys.size,
            updated = updatedKeys.size,
            upserted = addedKeys.size + updatedKeys.size,
            deleted = if (deleteMissing) delta.deletes.size else 0,
            unchanged = delta.unchanged.size,
            hasChanges = hasChanges
        )
    }

    enum class Phase { QUICK_VISIBLE, ENRICHED_BATCH }

    data class BatchResult(
        val phase: Phase,
        val requested: Int,
        val changed: Int
    )

    data class FinalResult(
        val added: Int,
        val updated: Int,
        val upserted: Int,
        val deleted: Int,
        val unchanged: Int,
        val hasChanges: Boolean = false
    )

    companion object {
        private const val DEFAULT_ENRICHED_FLUSH_SIZE = 160
        private const val DEFAULT_ENRICHED_FLUSH_INTERVAL_MS = 2_500L
    }
}

private fun AudioFile.lazySyncKey(): String {
    return if (cueTrackIndex > 0 || cueOffsetMs > 0L) {
        "cue|$path|$cueTrackIndex|$cueOffsetMs"
    } else {
        "file|$path"
    }
}
