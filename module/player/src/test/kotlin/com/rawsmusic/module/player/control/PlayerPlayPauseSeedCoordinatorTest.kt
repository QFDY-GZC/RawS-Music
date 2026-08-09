package com.rawsmusic.module.player.control

import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.PlayQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerPlayPauseSeedCoordinatorTest {
    private fun song(path: String, duration: Long = 0L) =
        AudioFile(path = path, title = path, duration = duration)

    @Test
    fun `current and restored song win before queue or repository`() {
        val current = song("/current")
        var restoreCalls = 0
        var repositoryCalls = 0
        val coordinator = coordinator(
            current = current,
            restore = { restoreCalls++; song("/restored") },
            queue = PlayQueue(listOf(song("/queue")), 0),
            repository = { repositoryCalls++; listOf(song("/repo")) },
        )

        assertEquals(current, coordinator.resolve())
        assertEquals(0, restoreCalls)
        assertEquals(0, repositoryCalls)
    }

    @Test
    fun `queue fallback clamps index and applies timeline identity`() {
        val first = song("/first")
        val second = song("/second", 120_000L)
        var appliedSong: AudioFile? = null
        var duration = 0L
        val coordinator = coordinator(
            queue = PlayQueue(listOf(first, second), 99),
            applySong = { appliedSong = it },
            applyDuration = { duration = it },
        )

        assertEquals(second, coordinator.resolve())
        assertEquals(second, appliedSong)
        assertEquals(120_000L, duration)
    }

    @Test
    fun `repository fallback creates queue and empty source reports warning`() {
        val repo = song("/repo")
        var appliedQueue = PlayQueue()
        var warning = ""
        val withRepository = coordinator(
            repository = { listOf(repo) },
            applyQueue = { appliedQueue = it },
        )
        assertEquals(repo, withRepository.resolve())
        assertEquals(listOf(repo), appliedQueue.songs)

        val empty = coordinator(warn = { warning = it })
        assertNull(empty.resolve())
        assertEquals("playPause seed missing: currentSong=null queue=empty repo=empty", warning)
    }

    private fun coordinator(
        current: AudioFile? = null,
        restore: () -> AudioFile? = { null },
        queue: PlayQueue = PlayQueue(),
        repository: () -> List<AudioFile> = { emptyList() },
        applySong: (AudioFile) -> Unit = {},
        applyDuration: (Long) -> Unit = {},
        applyQueue: (PlayQueue) -> Unit = {},
        warn: (String) -> Unit = {},
    ) = PlayerPlayPauseSeedCoordinator(
        PlayerPlayPauseSeedCoordinator.Callbacks(
            currentSong = { current },
            restoreLastSong = restore,
            currentQueue = { queue },
            applyCurrentSong = applySong,
            applyDurationMs = applyDuration,
            loadRepositorySongs = repository,
            applyQueue = applyQueue,
            elapsedRealtimeMs = { 100L },
            traceStartup = { _, _, _ -> },
            logDebug = {},
            logWarning = warn,
        )
    )
}
