package com.rawsmusic.core.ui.widget.bitmaps

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackArtworkMotionPolicyTest {
    @Test
    fun retainedArtworkImageMovesBoundIdentityBeforePixelsReturn() {
        assertTrue(
            referenceBoundArtworkHolderReadyForMotion(
                providerViewOwnerActive = true,
                targetKeyBound = true,
                targetToken = 0,
            )
        )
    }

    @Test
    fun fallbackRendererStillNeedsDrawableToken() {
        assertFalse(
            referenceBoundArtworkHolderReadyForMotion(
                providerViewOwnerActive = false,
                targetKeyBound = true,
                targetToken = 0,
            )
        )
        assertTrue(
            referenceBoundArtworkHolderReadyForMotion(
                providerViewOwnerActive = false,
                targetKeyBound = true,
                targetToken = 7,
            )
        )
    }

    @Test
    fun artworkImageCallbackDoesNotRepublishOuterPagerTopology() {
        assertFalse(referenceShouldRepublishForegroundForArtworkCallback(providerViewOwnerActive = true))
        assertTrue(referenceShouldRepublishForegroundForArtworkCallback(providerViewOwnerActive = false))
    }

    @Test
    fun noTargetIdentityNeverMoves() {
        assertFalse(
            referenceBoundArtworkHolderReadyForMotion(
                providerViewOwnerActive = true,
                targetKeyBound = false,
                targetToken = 9,
            )
        )
    }
}
