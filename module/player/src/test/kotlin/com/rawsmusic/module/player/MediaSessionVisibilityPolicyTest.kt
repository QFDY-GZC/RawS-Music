package com.rawsmusic.module.player

import com.rawsmusic.core.common.model.PlayState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSessionVisibilityPolicyTest {
    @Test fun selectedPausedSongRemainsVisibleToSystemMediaControls() {
        assertTrue(shouldKeepMediaSessionActive(true, PlayState.PAUSED))
        assertFalse(shouldKeepMediaSessionActive(false, PlayState.PAUSED))
    }

    @Test fun explicitlyStoppedSongDoesNotClaimActiveSession() {
        assertFalse(shouldKeepMediaSessionActive(true, PlayState.STOPPED))
        assertFalse(shouldKeepMediaSessionActive(true, PlayState.IDLE))
        assertTrue(shouldKeepMediaSessionActive(true, PlayState.PLAYING))
    }
}
