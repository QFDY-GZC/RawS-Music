package com.rawsmusic.core.ui.widget.bitmaps

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArtworkAspectPolicyTest {
    @Test
    fun keepAspectPreservesLandscapeAndPortraitGeometry() {
        assertEquals(
            ArtworkScaledSize(512, 341),
            resolveArtworkScaledSize(1200, 800, 512, 512, ArtworkAspectPolicy.KeepAspect),
        )
        assertEquals(
            ArtworkScaledSize(341, 512),
            resolveArtworkScaledSize(800, 1200, 512, 512, ArtworkAspectPolicy.KeepAspect),
        )
        assertEquals(
            ArtworkScaledSize(512, 512),
            resolveArtworkScaledSize(1200, 800, 512, 512, ArtworkAspectPolicy.Crop),
        )
    }

    @Test
    fun keepAspectUsesDedicatedProviderIdentity() {
        val key = "audio:///music/song.flac|100|200"
        val crop = artworkAspectCacheSourceKey(key, ArtworkAspectPolicy.Crop)
        val fit = artworkAspectCacheSourceKey(key, ArtworkAspectPolicy.KeepAspect)

        assertEquals(key, crop)
        assertTrue(fit.startsWith(key))
        assertTrue(isKeepAspectArtworkCacheSourceKey(fit))
        assertFalse(isKeepAspectArtworkCacheSourceKey(crop))
    }
}
