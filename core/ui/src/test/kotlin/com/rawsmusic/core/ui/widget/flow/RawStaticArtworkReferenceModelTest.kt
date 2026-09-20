package com.rawsmusic.core.ui.widget.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RawStaticArtworkReferenceModelTest {
    @Test
    fun `detail uses reference one-shift-scale source limits`() {
        assertEquals(1, referenceAaDetailLimit(0f))
        assertEquals(2, referenceAaDetailLimit(1f))
        assertEquals(32, referenceAaDetailLimit(5f))
        assertEquals(512, referenceAaDetailLimit(9f))
    }

    @Test
    fun `detail ten disables AA-side source limit`() {
        assertNull(referenceAaDetailLimit(10f))
        assertNull(referenceAaDetailLimit(12f))
    }
}
