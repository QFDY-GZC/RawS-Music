package com.rawsmusic.core.ui.widget.export

import org.junit.Assert.assertEquals
import org.junit.Test

class LyricVideoTimingTest {
    @Test fun pacedFrameWaitsUntilItsCaptureSlot() {
        assertEquals(8L, exportFrameWaitMillis(startNs = 1_000_000_000L, frameIndex = 1, fps = 120, nowNs = 1_000_001_000L))
        assertEquals(0L, exportFrameWaitMillis(startNs = 1_000_000_000L, frameIndex = 1, fps = 120, nowNs = 1_010_000_000L))
    }

    @Test fun droppedFrameDoesNotCompressVideoTimeline() {
        assertEquals(0L, exportVideoPresentationTimeUs(1_000_000L, 1_000_000L, -1L))
        assertEquals(16_666L, exportVideoPresentationTimeUs(1_016_666L, 1_000_000L, 0L))
    }
}
