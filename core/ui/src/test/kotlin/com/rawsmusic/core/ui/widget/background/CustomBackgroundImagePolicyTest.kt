package com.rawsmusic.core.ui.widget.background

import com.rawsmusic.core.ui.widget.bitmaps.ArtworkScaledSize
import com.rawsmusic.core.ui.widget.bitmaps.artworkAspectCacheSourceKey
import com.rawsmusic.core.ui.widget.bitmaps.resolveArtworkScaledSize
import com.rawsmusic.core.ui.widget.bitmaps.ArtworkAspectPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CustomBackgroundImagePolicyTest {
    @Test
    fun landscapeDecodeRetainsBothSides() {
        assertEquals(ArtworkScaledSize(1536, 864), resolveArtworkScaledSize(
            3840, 2160, 1536, 1536, CustomBackgroundImagePolicy.decodeAspect,
        ))
    }

    @Test
    fun portraitDecodeRetainsTopAndBottom() {
        assertEquals(ArtworkScaledSize(864, 1536), resolveArtworkScaledSize(
            2160, 3840, 1536, 1536, CustomBackgroundImagePolicy.decodeAspect,
        ))
    }

    @Test
    fun backgroundCannotReuseOldSquareCroppedCache() {
        val key = "content://media/external/images/media/42"
        assertNotEquals(
            artworkAspectCacheSourceKey(key, ArtworkAspectPolicy.Crop),
            artworkAspectCacheSourceKey(key, CustomBackgroundImagePolicy.decodeAspect),
        )
    }
}
