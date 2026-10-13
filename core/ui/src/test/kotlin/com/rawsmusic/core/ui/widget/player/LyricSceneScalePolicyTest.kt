package com.rawsmusic.core.ui.widget.player

import org.junit.Assert.assertEquals
import org.junit.Test

class LyricSceneScalePolicyTest {
    @Test fun scalingKeepsTextAlignedToTheArtworkInset() {
        assertEquals(.06f, LyricSceneScalePolicy.contentPivot(0f, .06f), .0001f)
        assertEquals(.5f, LyricSceneScalePolicy.contentPivot(.5f, .06f), .0001f)
        assertEquals(.94f, LyricSceneScalePolicy.contentPivot(1f, .06f), .0001f)
        // x' = pivot + (x - pivot) * scale: the text edge never moves.
        val pivot = LyricSceneScalePolicy.contentPivot(0f, .06f) * 400f
        for (scale in listOf(.65f, .98f, 1.3f)) {
            assertEquals(24f, pivot + (24f - pivot) * scale, .0001f)
        }
    }
    @Test
    fun inactiveHolderScaleUsesOnlyRegularToActiveSceneRatio() {
        assertEquals(0.65f / 0.85f, LyricSceneScalePolicy.inactiveHolderScale(0.65f, 0.85f), 0.0001f)
        assertEquals(1.0f / 1.1f, LyricSceneScalePolicy.inactiveHolderScale(1.0f, 1.1f), 0.0001f)
        assertEquals(1.2f / 1.3f, LyricSceneScalePolicy.inactiveHolderScale(1.2f, 1.3f), 0.0001f)
    }
}
