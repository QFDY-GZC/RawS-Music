package com.rawsmusic.core.ui.widget.player

import org.junit.Assert.*
import org.junit.Test

class PlayerFavoriteCelebrationGateTest {
    @Test fun waitsForPlaylistExitThenPlaysExactlyOnce() {
        val gate = PlayerFavoriteCelebrationGate("song-a", 10)
        assertFalse(gate.update(11, "song-a", true))
        assertTrue(gate.update(11, "song-a", false))
        assertFalse(gate.update(11, "song-a", false))
    }

    @Test fun reopeningMoreDoesNotReplayHistoricalSuccess() {
        val gate = PlayerFavoriteCelebrationGate("song-a", 11)
        assertFalse(gate.update(11, "song-a", false))
    }

    @Test fun cancellationWithoutSuccessDoesNotCelebrate() {
        val gate = PlayerFavoriteCelebrationGate("song-a", 10)
        assertFalse(gate.update(10, "song-a", true))
        assertFalse(gate.update(10, "song-a", false))
    }

    @Test fun successForAnotherSongDoesNotAnimateCurrentHeart() {
        val gate = PlayerFavoriteCelebrationGate("song-b", 10)
        assertFalse(gate.update(11, "song-a", false))
    }

    @Test fun aLaterRealAdditionCanCelebrateAgain() {
        val gate = PlayerFavoriteCelebrationGate("song-a", 10)
        assertTrue(gate.update(11, "song-a", false))
        assertTrue(gate.update(12, "song-a", false))
    }
}
