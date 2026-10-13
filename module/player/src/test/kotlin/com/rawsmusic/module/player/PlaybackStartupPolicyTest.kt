package com.rawsmusic.module.player

import org.junit.Assert.*
import org.junit.Test

class PlaybackStartupPolicyTest {
    @Test fun zeroPcmTerminalInputCannotBeAnnouncedAsPlaying() {
        assertTrue(PlaybackStartupPolicy.rejectEmptyStart(0, true, 0, 298074))
        assertTrue(PlaybackStartupPolicy.rejectEmptyStart(0, true, 0, 0))
    }
    @Test fun slowProducerAndValidShortAudioAreNotRejected() {
        assertFalse(PlaybackStartupPolicy.rejectEmptyStart(0, false, 0, 298074))
        assertFalse(PlaybackStartupPolicy.rejectEmptyStart(8, true, 0, 1))
        assertFalse(PlaybackStartupPolicy.rejectEmptyStart(0, true, 298074, 298074))
    }
}
