package com.rawsmusic.core.ui.widget.player

import org.junit.Assert.assertEquals
import org.junit.Test

class MiniLyricHandoffPolicyTest {
    @Test
    fun mixedHeightAdjacentRowsUseExactCenterDistance() {
        val distance = compactLyricCenterTransitionDistancePx(
            previousAnchor = 10,
            newAnchor = 11,
            orderedRenderableIndices = listOf(10, 11),
            rowHeightsPx = mapOf(10 to 40, 11 to 80),
            fallbackHeightPx = 50f,
            spacingPx = 10f,
        )
        assertEquals(70f, distance, 0.001f)
    }

    @Test
    fun mixedHeightMultiRowDistanceIsContinuousInBothDirections() {
        val indices = listOf(10, 11, 12)
        val heights = mapOf(10 to 40, 11 to 80, 12 to 60)
        assertEquals(
            150f,
            compactLyricCenterTransitionDistancePx(10, 12, indices, heights, 50f, 10f),
            0.001f,
        )
        assertEquals(
            -150f,
            compactLyricCenterTransitionDistancePx(12, 10, indices, heights, 50f, 10f),
            0.001f,
        )
    }

    @Test
    fun bufferedWindowKeepsOutgoingAndIncomingEdgeRowsMounted() {
        val all = (0..10).toList()
        assertEquals(
            listOf(1, 2, 3, 4, 5, 6, 7),
            bufferedCompactLyricWindowIndices(all, listOf(3, 4, 5), extraRows = 2),
        )
        assertEquals(
            listOf(0, 1, 2, 3, 4),
            bufferedCompactLyricWindowIndices(all, listOf(0, 1, 2), extraRows = 2),
        )
    }
}
