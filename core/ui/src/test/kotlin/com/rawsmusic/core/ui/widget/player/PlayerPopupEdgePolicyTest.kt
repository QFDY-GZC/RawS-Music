package com.rawsmusic.core.ui.widget.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerPopupEdgePolicyTest {
    @Test fun hiddenContentSoftensOnlyItsOwnEdge() {
        assertEquals(1f, popupEdgeBlurWeight(0f, 200f, 20f, true, false), 0f)
        assertEquals(0.5f, popupEdgeBlurWeight(10f, 200f, 20f, true, false), 0.0001f)
        assertEquals(0f, popupEdgeBlurWeight(100f, 200f, 20f, true, true), 0f)
        assertEquals(1f, popupEdgeBlurWeight(200f, 200f, 20f, false, true), 0f)
    }
    @Test fun endpointsKeepFirstAndLastOptionsReadable() {
        assertEquals(0f, popupEdgeBlurWeight(0f, 200f, 20f, false, true), 0f)
        assertEquals(0f, popupEdgeBlurWeight(200f, 200f, 20f, true, false), 0f)
        assertEquals(0f, popupEdgeBlurWeight(0f, 200f, 20f, false, false), 0f)
    }
    @Test fun tinyViewportsStillHaveAnUnblurredCentre() {
        assertEquals(0f, popupEdgeBlurWeight(15f, 30f, 24f, true, true), 0f)
        assertEquals(0f, popupEdgeBlurWeight(0f, 0f, 24f, true, true), 0f)
    }
}
