package com.rawsmusic.core.ui.widget.bitmaps

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackArtworkContinuousPositionPolicyTest {
    @Test
    fun nextDistancesRemainContinuousAcrossMiddleBoundary() {
        assertEquals(-0.999f, referenceContinuousHolderSignedDistance(0.999f, 0f, +1), 0.0001f)
        assertEquals(+0.001f, referenceContinuousHolderSignedDistance(0.999f, 1f, +1), 0.0001f)
        assertEquals(+1.001f, referenceContinuousHolderSignedDistance(0.999f, 2f, +1), 0.0001f)

        assertEquals(-1.001f, referenceContinuousHolderSignedDistance(1.001f, 0f, +1), 0.0001f)
        assertEquals(-0.001f, referenceContinuousHolderSignedDistance(1.001f, 1f, +1), 0.0001f)
        assertEquals(+0.999f, referenceContinuousHolderSignedDistance(1.001f, 2f, +1), 0.0001f)
    }

    @Test
    fun previousIsExactMirror() {
        val s = 1.35f
        for (offset in listOf(0f, 1f, 2f)) {
            val next = referenceContinuousHolderSignedDistance(s, offset, +1)
            val previous = referenceContinuousHolderSignedDistance(s, offset, -1)
            assertEquals(-next, previous, 0.0001f)
        }
    }

    @Test
    fun baseRatioDoesNotResetAtMiddleBoundary() {
        assertEquals(0.999f, referenceContinuousBaseRatio(0.999f), 0.0001f)
        assertEquals(1f, referenceContinuousBaseRatio(1.001f), 0.0001f)
        assertEquals(1f, referenceContinuousBaseRatio(1.80f), 0.0001f)
        assertEquals(0.80f, referenceContinuousLocalProgress(1.80f), 0.0001f)
    }
}
