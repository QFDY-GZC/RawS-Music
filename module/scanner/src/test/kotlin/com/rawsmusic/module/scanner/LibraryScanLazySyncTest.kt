package com.rawsmusic.module.scanner

import com.rawsmusic.core.common.model.AudioFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryScanLazySyncTest {
    @Test
    fun incompleteScanUpsertsVisibleSongsWithoutDeletingMissingRows() = runBlocking {
        val existing = AudioFile(id = 1, path = "/music/existing.flac", title = "Existing")
        val discovered = AudioFile(id = 2, path = "/music/new.flac", title = "New")
        val repository = FakeRepository(mutableListOf(existing))

        val result = LibraryScanLazySync(repository).syncFinal(
            songs = listOf(discovered),
            deleteMissing = false
        )

        assertEquals(0, result.deleted)
        assertTrue(existing in repository.songs)
        assertTrue(repository.songs.any { it.path == discovered.path })
    }

    @Test
    fun completeScanDeletesRowsMissingFromAuthoritativeSources() = runBlocking {
        val existing = AudioFile(id = 1, path = "/music/existing.flac", title = "Existing")
        val repository = FakeRepository(mutableListOf(existing))

        val result = LibraryScanLazySync(repository).syncFinal(
            songs = emptyList(),
            deleteMissing = true
        )

        assertEquals(1, result.deleted)
        assertTrue(repository.songs.isEmpty())
    }

    private class FakeRepository(val songs: MutableList<AudioFile>) : AudioLibraryRepository {
        override suspend fun getAllSongs(): List<AudioFile> = songs.toList()

        override suspend fun upsertSongs(newSongs: List<AudioFile>) {
            newSongs.forEach { newSong ->
                songs.removeAll { it.path == newSong.path }
                songs += newSong
            }
        }

        override suspend fun deleteSongs(removedSongs: List<AudioFile>) {
            val paths = removedSongs.mapTo(HashSet()) { it.path }
            songs.removeAll { it.path in paths }
        }
    }
}
