package com.rawsmusic.core.ui.widget.bitmaps

import org.junit.Assert.assertEquals
import org.junit.Test

class ArtworkReplacementPolicyTest {
    @Test
    fun interruptedReplacementCarriesActuallyVisibleAlpha() {
        assertEquals(0.30f, ReferenceInterruptedPreviousAlpha(0.30f, 0f), 0.0001f)
        assertEquals(0.20f, ReferenceInterruptedPreviousAlpha(0.30f, 0.10f), 0.0001f)
        assertEquals(0f, ReferenceInterruptedPreviousAlpha(0.30f, 0.30f), 0.0001f)
    }

    @Test
    fun ordinaryReplacementDrainsPreviousFromFullOpacity() {
        assertEquals(0.75f, ReferenceInterruptedPreviousAlpha(1f, 0.25f), 0.0001f)
        assertEquals(0f, ReferenceInterruptedPreviousAlpha(1f, 1f), 0.0001f)
    }

    @Test
    fun alphaByteIsClampedAndStable() {
        assertEquals(0, ReferenceArtworkAlphaByte(-1f))
        assertEquals(76, ReferenceArtworkAlphaByte(0.30f))
        assertEquals(255, ReferenceArtworkAlphaByte(2f))
    }

    @Test
    fun realArtworkNeverStartsAnotherLocalDissolve() {
        assertEquals(false, shouldAnimateArtworkReplacement(false, false))
        assertEquals(false, shouldAnimateArtworkReplacement(false, true))
        assertEquals(false, shouldAnimateArtworkReplacement(true, true))
        assertEquals(true, shouldAnimateArtworkReplacement(true, false))
    }

    @Test
    fun fallbackIdentityIsExplicit() {
        assertEquals(true, isFallbackArtworkSourceKey("default-art:song"))
        assertEquals(false, isFallbackArtworkSourceKey("/music/song.flac|1|2"))
    }
}
