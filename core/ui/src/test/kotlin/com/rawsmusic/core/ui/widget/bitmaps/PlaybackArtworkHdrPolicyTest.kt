package com.rawsmusic.core.ui.widget.bitmaps

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackArtworkHdrPolicyTest {
    @Test fun nativeArtworkRequestsHdrOnlyOnCapableVisibleHardwareSurface() {
        assertTrue(shouldRequestPlaybackArtworkHdr(34, true, true, true, true))
    }
    @Test fun ordinaryArtworkAndUnsupportedSystemRemainSdr() {
        assertFalse(shouldRequestPlaybackArtworkHdr(34, true, true, true, false))
        assertFalse(shouldRequestPlaybackArtworkHdr(33, true, true, true, true))
        assertFalse(shouldRequestPlaybackArtworkHdr(34, false, true, true, true))
    }
    @Test fun HiddenAndSoftwareSurfacesReleaseHdr() {
        assertFalse(shouldRequestPlaybackArtworkHdr(34, true, true, false, true))
        assertFalse(shouldRequestPlaybackArtworkHdr(34, true, false, true, true))
    }
}
