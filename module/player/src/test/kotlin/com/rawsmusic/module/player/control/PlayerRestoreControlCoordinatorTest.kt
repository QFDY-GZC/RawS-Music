package com.rawsmusic.module.player.control

import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.PlayQueue
import com.rawsmusic.module.player.RestoredPlayerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerRestoreControlCoordinatorTest {
    @Test
    fun `empty snapshot reports empty startup stage`() {
        val traces = mutableListOf<String>()
        val coordinator = PlayerRestoreControlCoordinator(
            PlayerRestoreControlCoordinator.Callbacks(
                elapsedRealtimeMs = { 100L },
                restoreSnapshot = { null },
                applyCurrentSong = {},
                clearRequestedSong = {},
                applyDurationMs = {},
                applyPositionMs = {},
                armPendingSeek = { _, _ -> },
                applyQueue = {},
                logInfo = {},
                traceStartup = { stage, _, _ -> traces += stage },
            )
        )

        assertNull(coordinator.restoreLastSong())
        assertEquals(listOf("restore_last_song_empty"), traces)
    }

    @Test
    fun `restored snapshot atomically applies song timeline queue and pending seek`() {
        val song = AudioFile(path = "/music/a.flac", title = "A", duration = 240_000L)
        val restored = RestoredPlayerState(
            song = song,
            queue = listOf(song),
            queueIndex = 0,
            positionMs = 42_000L,
            source = "queue",
            repositorySongCount = 10,
        )
        var current: AudioFile? = null
        var duration = 0L
        var position = 0L
        var pending: Pair<Long, String>? = null
        var queue = PlayQueue()
        var cleared = 0
        val traces = mutableListOf<String>()
        var clock = 100L
        val coordinator = PlayerRestoreControlCoordinator(
            PlayerRestoreControlCoordinator.Callbacks(
                elapsedRealtimeMs = { clock++ },
                restoreSnapshot = { restored },
                applyCurrentSong = { current = it },
                clearRequestedSong = { cleared++ },
                applyDurationMs = { duration = it },
                applyPositionMs = { position = it },
                armPendingSeek = { value, path -> pending = value to path },
                applyQueue = { queue = it },
                logInfo = {},
                traceStartup = { stage, _, _ -> traces += stage },
            )
        )

        assertEquals(song, coordinator.restoreLastSong())
        assertEquals(song, current)
        assertEquals(240_000L, duration)
        assertEquals(42_000L, position)
        assertEquals(42_000L to song.path, pending)
        assertEquals(listOf(song), queue.songs)
        assertEquals(0, queue.currentIndex)
        assertEquals(1, cleared)
        assertEquals(listOf("restore_last_song_done"), traces)
    }
}
