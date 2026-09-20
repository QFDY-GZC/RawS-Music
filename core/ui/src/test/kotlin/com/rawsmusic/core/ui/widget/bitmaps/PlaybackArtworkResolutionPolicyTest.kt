package com.rawsmusic.core.ui.widget.bitmaps

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackArtworkResolutionPolicyTest {
    @Test
    fun playbackBaselineAndIncreaseResolutionHaveDistinctTargets() {
        assertEquals(
            1024,
            AlbumArtTiers.playbackTargetSide(
                increaseResolution = false,
                displayShortSidePx = 1200,
                maxMemoryBytes = 512L * 1024L * 1024L,
            ),
        )
        assertEquals(
            1536,
            AlbumArtTiers.playbackTargetSide(
                increaseResolution = true,
                displayShortSidePx = 1200,
                maxMemoryBytes = 512L * 1024L * 1024L,
            ),
        )
        assertEquals(
            512,
            AlbumArtTiers.playbackTargetSide(
                increaseResolution = false,
                displayShortSidePx = 999,
                maxMemoryBytes = 512L * 1024L * 1024L,
            ),
        )
        assertEquals(
            1024,
            AlbumArtTiers.playbackTargetSide(
                increaseResolution = true,
                displayShortSidePx = 999,
                maxMemoryBytes = 512L * 1024L * 1024L,
            ),
        )
        assertEquals(
            1024,
            AlbumArtTiers.playbackTargetSide(
                increaseResolution = true,
                displayShortSidePx = 1200,
                maxMemoryBytes = 127L * 1024L * 1024L,
            ),
        )

        val baseline = AlbumArtTiers.resolve(
            requestedWidth = 1024,
            requestedHeight = 1024,
            allowHiRes = true,
            priority = BitmapRequest.Priority.LOADING_WIDGET,
            highResEnabled = false,
        )
        val increased = AlbumArtTiers.resolve(
            requestedWidth = 1536,
            requestedHeight = 1536,
            allowHiRes = true,
            priority = BitmapRequest.Priority.LOADING_WIDGET,
            highResEnabled = true,
        )
        assertEquals(1024, baseline.maxSide)
        assertEquals(1536, increased.maxSide)
    }

    @Test
    fun playbackHighTierUsesFifoProviderPriority() {
        assertEquals(BitmapRequest.Priority.LOADING_WIDGET, AlbumArtTiers.PLAYBACK_PROVIDER_PRIORITY)
    }

    @Test
    fun increaseResolutionUsesItsOwnProviderBucket() {
        assertEquals(1024, SizeSlotCache.computeBucket(1024, 1024))
        assertEquals(1536, SizeSlotCache.computeBucket(1536, 1536))
    }
}
