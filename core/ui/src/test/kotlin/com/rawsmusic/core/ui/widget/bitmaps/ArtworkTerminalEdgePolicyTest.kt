package com.rawsmusic.core.ui.widget.bitmaps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtworkTerminalEdgePolicyTest {
    @Test
    fun `terminal aa edge is resisted and symmetric`() {
        val previousEdge = ReferenceArtworkTerminalEdgeDistance(1f)
        val nextEdge = ReferenceArtworkTerminalEdgeDistance(-1f)
        assertTrue(previousEdge in 0f..0.2f)
        assertEquals(-previousEdge, nextEdge, 0.0001f)
        assertEquals(0f, ReferenceArtworkTerminalEdgeDistance(0f), 0.0001f)
    }
}
