package com.rawsmusic.core.ui.widget.player

import org.junit.Assert.assertEquals
import org.junit.Test

class LyricSceneScalePolicyTest {
    @Test
    fun inactiveHolderScaleUsesOnlyRegularToActiveSceneRatio() {
        assertEquals(0.65f / 0.85f, LyricSceneScalePolicy.inactiveHolderScale(0.65f, 0.85f), 0.0001f)
        assertEquals(1.0f / 1.1f, LyricSceneScalePolicy.inactiveHolderScale(1.0f, 1.1f), 0.0001f)
        assertEquals(1.2f / 1.3f, LyricSceneScalePolicy.inactiveHolderScale(1.2f, 1.3f), 0.0001f)
    }
}
