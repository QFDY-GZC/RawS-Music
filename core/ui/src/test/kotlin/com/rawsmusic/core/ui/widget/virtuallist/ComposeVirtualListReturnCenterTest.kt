package com.rawsmusic.core.ui.widget.virtuallist

import org.junit.Assert.assertEquals
import org.junit.Test

class ComposeVirtualListReturnCenterTest {
    private fun state(): ComposeVirtualListState = ComposeVirtualListState(
        initialLevel = ListZoomIndex.NORMAL,
        initialColumns = 1,
        persistZoomLevel = {},
        persistColumns = {},
    )

    @Test
    fun `return center moves top item down by reducing scroll`() {
        val state = state()
        state.viewportScrollY = 500f
        state.recordSettledGeometry(
            geometrySignature = 1L,
            viewportHeightPx = 1000,
            maxScrollY = 3000,
        )

        val delta = state.prepareCollectionReturnCenter(
            itemTopPx = 100f,
            itemBottomPx = 300f,
        )

        assertEquals(-300f, delta ?: 0f, 0.001f)
        assertEquals(200f, state.viewportScrollY, 0.001f)
    }

    @Test
    fun `return center moves bottom item up by increasing scroll`() {
        val state = state()
        state.viewportScrollY = 500f
        state.recordSettledGeometry(
            geometrySignature = 1L,
            viewportHeightPx = 1000,
            maxScrollY = 3000,
        )

        val delta = state.prepareCollectionReturnCenter(
            itemTopPx = 800f,
            itemBottomPx = 1000f,
        )

        assertEquals(400f, delta ?: 0f, 0.001f)
        assertEquals(900f, state.viewportScrollY, 0.001f)
    }

    @Test
    fun `return center clamps at list boundary like reference player`() {
        val state = state()
        state.viewportScrollY = 50f
        state.recordSettledGeometry(
            geometrySignature = 1L,
            viewportHeightPx = 1000,
            maxScrollY = 3000,
        )

        val delta = state.prepareCollectionReturnCenter(
            itemTopPx = 0f,
            itemBottomPx = 200f,
        )

        assertEquals(-50f, delta ?: 0f, 0.001f)
        assertEquals(0f, state.viewportScrollY, 0.001f)
    }
}
