package com.rawsmusic.module.player.control

import com.rawsmusic.core.common.model.AudioFile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerDuplicatePlayRequestGateTest {
    private val song = AudioFile(path = "/music/a.flac", cueOffsetMs = 100L, cueTrackIndex = 2)

    @Test
    fun ignoresSameBusyRequestInsideWindowButAllowsIdleOrLateRequest() {
        val gate = PlayerDuplicatePlayRequestGate(duplicateWindowMs = 1_200L)
        assertFalse(gate.shouldIgnore(song, 1_000L, backendPreparingPlayingOrPaused = true))
        assertTrue(gate.shouldIgnore(song, 1_500L, backendPreparingPlayingOrPaused = true))
        assertFalse(gate.shouldIgnore(song, 1_600L, backendPreparingPlayingOrPaused = false))
        assertFalse(gate.shouldIgnore(song, 3_000L, backendPreparingPlayingOrPaused = true))
    }

    @Test
    fun cueIdentityAndClearRemainPartOfAdmission() {
        val gate = PlayerDuplicatePlayRequestGate()
        assertFalse(gate.shouldIgnore(song, 100L, true))
        assertFalse(gate.shouldIgnore(song.copy(cueTrackIndex = 3), 200L, true))
        assertTrue(gate.shouldIgnore(song.copy(cueTrackIndex = 3), 300L, true))
        gate.clear()
        assertFalse(gate.shouldIgnore(song.copy(cueTrackIndex = 3), 350L, true))
    }
}
