package com.rawsmusic.module.data.prefs

import com.rawsmusic.core.common.model.AudioFile
import org.junit.Assert.*
import org.junit.Test

class BackupPlaybackPolicyTest {
    private val song = AudioFile(id = 7, path = "/Music/a.flac", title = "A", artist = "Artist", album = "Album")
    private val data = BackupPlayback(listOf(SongPlaybackStats(7, "A", "Artist", "Album", 5, 1000, 2)),
        listOf(PlaybackHistoryEntry(7, "A", "Artist", "Album", 2)), mapOf("2026-10-05" to 1000))
    @Test fun exportAttachesOnlyReferencedVerifiedLocalPaths() {
        val exported = BackupPlaybackPolicy.forExport(data, listOf(song, song.copy(id = 80, path = "/other.flac")))
        assertEquals(mapOf(7L to song.path), exported.paths)
        assertEquals(data.stats, exported.stats)
        assertEquals(data.history, exported.history)
    }
    @Test fun exportDoesNotAttachRecycledIdsOrConflictingHistory() {
        assertTrue(BackupPlaybackPolicy.forExport(data, listOf(song.copy(title = "Other"))).paths.isEmpty())
        val conflicting = data.copy(history = listOf(data.history.single().copy(title = "Other")))
        assertTrue(BackupPlaybackPolicy.forExport(conflicting, listOf(song)).paths.isEmpty())
    }
    @Test fun reinstallWithRealMatchingSongRetainsStatistics() {
        assertEquals(data, BackupPlaybackPolicy.resolve(data, listOf(song)) { true }.playback)
    }
    @Test fun newSystemWithNoMediaDegradesToDailyTotalsWithoutPhantomRows() {
        val result = BackupPlaybackPolicy.resolve(data, emptyList()) { true }
        assertTrue(result.playback.stats.isEmpty())
        assertTrue(result.playback.history.isEmpty())
        assertEquals(mapOf("2026-10-05" to 1000L), result.playback.daily)
        assertEquals(1, result.missingStats)
        assertEquals(1, result.missingHistory)
    }
    @Test fun recycledSongIdCannotDisplayAnotherSongsHistory() {
        val result = BackupPlaybackPolicy.resolve(data, listOf(song.copy(title = "Other"))) { true }
        assertTrue(result.playback.stats.isEmpty())
        assertTrue(result.playback.history.isEmpty())
    }
    @Test fun unreadableOrRemovedMediaDoesNotCountAsExisting() {
        assertTrue(BackupPlaybackPolicy.resolve(data, listOf(song)) { false }.playback.history.isEmpty())
    }
    @Test fun titleOnlyMatchDoesNotBindHistoryToNewId() {
        assertTrue(BackupPlaybackPolicy.resolve(data, listOf(song.copy(id = 8))) { true }.playback.stats.isEmpty())
    }
    @Test fun exactLocalPathRestoresAfterRescanChangesIdAndRefreshesTags() {
        val changed = song.copy(id = 90, title = "New tag")
        val result = BackupPlaybackPolicy.resolve(data.copy(paths = mapOf(7L to song.path)), listOf(changed)) { true }
        assertEquals(90L, result.playback.stats.single().songId)
        assertEquals("New tag", result.playback.stats.single().title)
        assertEquals(90L, result.playback.history.single().songId)
        assertEquals(5, result.playback.stats.single().playCount)
    }
    @Test fun duplicatePathsDoNotPickAnArbitraryCueTrack() {
        val result = BackupPlaybackPolicy.resolve(data.copy(paths = mapOf(7L to song.path)), listOf(song.copy(id = 9), song.copy(id = 10))) { true }
        assertTrue(result.playback.stats.isEmpty())
    }
}
