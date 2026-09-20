package com.rawsmusic.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class PeqPreampUiPolicyTest {
    @Test
    fun `normal preamp keeps compact slider range`() {
        assertEquals(-12f, peqPreampSliderRange(-6f).start, 0.0001f)
        assertEquals(12f, peqPreampSliderRange(-6f).endInclusive, 0.0001f)
    }

    @Test
    fun `deep imported preamp expands slider without clipping value`() {
        val range = peqPreampSliderRange(-18.5f)
        assertEquals(-24f, range.start, 0.0001f)
        assertEquals(12f, range.endInclusive, 0.0001f)
        assertEquals(71, peqPreampHalfDbSteps(range))
    }
}
