package com.rawsmusic.module.scanner

import com.rawsmusic.core.common.model.AudioFile
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class LibraryScanPublicationTest {
    @Test fun differentCoordinatorsDoNotRunSourcesConcurrently() = runBlocking {
        val firstStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val repository = Repository(emptyList())
        val first = launch {
            LibraryScanCoordinator(repository, {}).scanAndSyncWithSource(options) {
                flow { firstStarted.complete(Unit); release.await(); emit(completed(emptyList())) }
            }.collect()
        }
        firstStarted.await()
        val second = launch {
            LibraryScanCoordinator(repository, {}).scanAndSyncWithSource(options) {
                flow { secondStarted.complete(Unit); emit(completed(emptyList())) }
            }.collect()
        }
        repeat(20) { yield() }
        val overlapped = secondStarted.isCompleted
        release.complete(Unit)
        first.join(); second.join()
        assertFalse(overlapped)
    }
    private val options = TwoStageMediaScanner.Options(
        scannerOptions = MediaStoreScanner.ScanOptions(),
        sourceMode = TwoStageMediaScanner.SourceMode.SELECTED_FOLDERS,
        incrementalMetadataOnly = true, publishQuickVisible = false,
    )
    private fun completed(songs: List<AudioFile>, reliable: Boolean = true, snapshots: Map<String, Long> = emptyMap()) =
        TwoStageMediaScanner.Event.FullyCompleted(songs, songs.size, 0, 0, 0, canDeleteMissing = reliable, directorySnapshots = snapshots)

    @Test fun completionUsesCommittedRowsNotRejectedScanInput() = runBlocking {
        val song = AudioFile(path = "/deleted.flac", title = "Deleted")
        val repository = Repository(listOf(song))
        val events = LibraryScanCoordinator(repository, {}).scanAndSyncWithSource(options) {
            flow {
                repository.rows = emptyList()
                repository.rejected = setOf(song.path)
                emit(completed(listOf(song)))
            }
        }.toList()
        assertTrue(events.filterIsInstance<LibraryScanCoordinator.Event.Completed>().single().songs.isEmpty())
    }
    @Test fun partialSourceRetainsOldRowsAndOnlyCommitsSuccessfulSnapshots() = runBlocking {
        val old = AudioFile(path = "/failed/old.flac")
        val new = AudioFile(path = "/ok/new.flac")
        val repository = Repository(listOf(old))
        val events = LibraryScanCoordinator(repository, {}).scanAndSyncWithSource(options) {
            flowOf(completed(listOf(new), false, mapOf("ok" to 12)))
        }.toList()
        assertEquals(setOf(old, new), events.filterIsInstance<LibraryScanCoordinator.Event.Completed>().single().songs.toSet())
        assertEquals(mapOf("ok" to 12L), repository.snapshots)
    }
    @Test fun cancelledDiscoveryCannotCommitOrPublish() = runBlocking {
        val repository = Repository(emptyList())
        val coordinator = LibraryScanCoordinator(repository, {})
        try {
            coordinator.scanAndSyncWithSource(options) {
                flow { throw CancellationException("fixture") }
            }.collect { if (it is LibraryScanCoordinator.Event.Completed) fail("Cancelled scan published") }
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
        assertEquals(0, repository.refreshes)
        assertTrue(repository.snapshots.isEmpty())
    }
    @Test fun unchangedScanKeepsRepositoryListIdentityAndAvoidsRefresh() = runBlocking {
        val original = listOf(AudioFile(path = "/old.flac"))
        val repository = Repository(original)
        val events = LibraryScanCoordinator(repository, {}).scanAndSyncWithSource(options) { flowOf(completed(original)) }.toList()
        assertSame(original, events.filterIsInstance<LibraryScanCoordinator.Event.Completed>().single().songs)
        assertFalse(events.filterIsInstance<LibraryScanCoordinator.Event.Completed>().single().hasChanges)
        assertEquals(0, repository.refreshes)
    }
    private class Repository(var rows: List<AudioFile>) : AudioLibraryRepository {
        var rejected: Set<String> = emptySet()
        var snapshots: Map<String, Long> = emptyMap()
        var refreshes = 0
        override suspend fun getAllSongs() = rows
        override suspend fun upsertSongs(songs: List<AudioFile>) {
            val allowed = songs.filterNot { it.path in rejected }
            val keys = allowed.mapTo(HashSet(), ::scanRowKey)
            if (allowed.isNotEmpty()) rows = rows.filterNot { scanRowKey(it) in keys } + allowed
        }
        override suspend fun deleteSongs(songs: List<AudioFile>) {
            val keys = songs.mapTo(HashSet(), ::scanRowKey)
            rows = rows.filterNot { scanRowKey(it) in keys }
        }
        override suspend fun refreshAfterScanSync() { refreshes++ }
        override suspend fun commitScanDirectories(snapshots: Map<String, Long>) { this.snapshots = snapshots }
    }
}
