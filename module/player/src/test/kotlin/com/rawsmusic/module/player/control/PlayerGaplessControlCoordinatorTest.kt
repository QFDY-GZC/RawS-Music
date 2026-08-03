package com.rawsmusic.module.player.control

import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.PlayMode
import com.rawsmusic.core.common.model.PlayQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerGaplessControlCoordinatorTest {
    private fun song(path: String) = AudioFile(path = path, title = path)

    @Test
    fun `disabled gapless clears decoder plan`() {
        var plan: GaplessPlaybackPlan? = null
        coordinator(
            gaplessEnabled = false,
            crossfadeSeconds = 0,
            queue = PlayQueue(listOf(song("/a"), song("/b")), currentIndex = 0),
            apply = { plan = it },
        ).prepareNextSong()

        assertNull(plan?.nextSongPath)
        assertEquals(0, plan?.crossfadeDurationMs)
    }

    @Test
    fun `sequential and repeat one resolve next decoder source`() {
        val songs = listOf(song("/a"), song("/b"))
        val plans = mutableListOf<GaplessPlaybackPlan>()
        coordinator(
            queue = PlayQueue(songs, currentIndex = 0),
            playMode = PlayMode.SEQUENTIAL,
            crossfadeSeconds = 3,
            apply = plans::add,
        ).prepareNextSong()
        coordinator(
            queue = PlayQueue(songs, currentIndex = 1),
            playMode = PlayMode.REPEAT_ONE,
            apply = plans::add,
        ).prepareNextSong()

        assertEquals("/b", plans[0].nextSongPath)
        assertEquals(3_000, plans[0].crossfadeDurationMs)
        assertEquals("/b", plans[1].nextSongPath)
    }

    @Test
    fun `enabled invalid queue preserves existing decoder plan`() {
        var applyCalls = 0
        coordinator(
            queue = PlayQueue(emptyList(), currentIndex = -1),
            apply = { applyCalls++ },
        ).prepareNextSong()

        assertEquals(0, applyCalls)
    }

    @Test
    fun `shuffle delegates preview without mutating queue`() {
        val songs = listOf(song("/a"), song("/b"), song("/c"))
        var previewCalls = 0
        var plan: GaplessPlaybackPlan? = null
        val coordinator = PlayerGaplessControlCoordinator(
            PlayerGaplessControlCoordinator.Callbacks(
                gaplessEnabled = { true },
                crossfadeSeconds = { 0 },
                currentQueue = { PlayQueue(songs, currentIndex = 0) },
                currentPlayMode = { PlayMode.SHUFFLE_ALL },
                peekShuffleIndex = { previewCalls += 1; 2 },
                applyPlan = { plan = it },
                logInfo = {},
                logWarning = { _, error -> throw error },
            )
        )

        coordinator.prepareNextSong()

        assertEquals(1, previewCalls)
        assertEquals("/c", plan?.nextSongPath)
    }

    private fun coordinator(
        gaplessEnabled: Boolean = true,
        crossfadeSeconds: Int = 0,
        queue: PlayQueue,
        playMode: PlayMode = PlayMode.SEQUENTIAL,
        apply: (GaplessPlaybackPlan) -> Unit,
    ) = PlayerGaplessControlCoordinator(
        PlayerGaplessControlCoordinator.Callbacks(
            gaplessEnabled = { gaplessEnabled },
            crossfadeSeconds = { crossfadeSeconds },
            currentQueue = { queue },
            currentPlayMode = { playMode },
            peekShuffleIndex = { -1 },
            applyPlan = apply,
            logInfo = {},
            logWarning = { _, error -> throw error },
        )
    )
}
