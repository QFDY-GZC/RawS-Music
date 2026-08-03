package com.rawsmusic.core.ui.widget.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricPullSpecTest {
    @Test
    fun delayCompressesFromFiftyToFourMilliseconds() {
        assertEquals(50, LyricPullSpec.itemDelayMs(0f, 1_000f))
        assertEquals(27, LyricPullSpec.itemDelayMs(500f, 1_000f))
        assertEquals(4, LyricPullSpec.itemDelayMs(1_000f, 1_000f))
        assertEquals(4, LyricPullSpec.itemDelayMs(4_000f, 1_000f))
    }

    @Test
    fun distanceDerivedDecelerateStartsAndEndsExactly() {
        assertEquals(0f, LyricPullSpec.interpolate(0f, 720f), 0.0001f)
        assertEquals(1f, LyricPullSpec.interpolate(1f, 720f), 0.0001f)
        assertTrue(LyricPullSpec.interpolate(0.5f, 720f) > 0.5f)
    }
}
