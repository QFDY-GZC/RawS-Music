package com.rawsmusic.core.ui.widget.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoCoverFirstFramePolicyTest {
    @Test fun pausedNewSourceStaysActiveUntilAFrameIsPresented() {
        assertTrue(shouldKeepVideoCoverSessionActive(playbackActive = false, firstFramePending = true))
        assertFalse(shouldKeepVideoCoverSessionActive(playbackActive = false, firstFramePending = false))
    }

    @Test fun playingCoverRemainsActiveAfterFirstFrame() {
        assertTrue(shouldKeepVideoCoverSessionActive(playbackActive = true, firstFramePending = false))
    }
}
