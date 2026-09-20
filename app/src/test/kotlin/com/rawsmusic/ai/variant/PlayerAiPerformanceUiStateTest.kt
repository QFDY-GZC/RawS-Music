package com.rawsmusic.ai.variant

import com.rawsmusic.core.ui.widget.player.PlayerAiPerformanceMode
import com.rawsmusic.core.ui.widget.player.PlayerAiPerformanceUiState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerAiPerformanceUiStateTest {
    @Test
    fun busyPreparationCanAlwaysBeTurnedOff() {
        val state = PlayerAiPerformanceUiState(
            supported = true,
            busy = true,
        )

        assertTrue(state.enabled)
        assertTrue(state.toggleEnabled)
    }

    @Test
    fun unsupportedInactiveSongCannotBeTurnedOn() {
        val state = PlayerAiPerformanceUiState(
            supported = false,
            busy = false,
        )

        assertFalse(state.enabled)
        assertFalse(state.toggleEnabled)
    }

    @Test
    fun activeModeCanStillBeTurnedOffAfterSupportChanges() {
        val state = PlayerAiPerformanceUiState(
            supported = false,
            mode = PlayerAiPerformanceMode.INSTRUMENT_PERFORMANCE,
        )

        assertTrue(state.enabled)
        assertTrue(state.toggleEnabled)
    }
}
