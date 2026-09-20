package com.rawsmusic.core.ui.widget.virtuallist

import org.junit.Assert.assertEquals
import org.junit.Test

class ComposeVirtualListInitialAnchorTest {
    private fun state(): ComposeVirtualListState = ComposeVirtualListState(
        initialLevel = ListZoomIndex.NORMAL,
        initialColumns = 1,
        persistZoomLevel = {},
        persistColumns = {},
    )

    @Test
    fun latestSeedWinsUntilFirstViewportIsBound() {
        val state = state()

        state.seedInitialScrollToIndex(3)
        state.seedInitialScrollToIndex(7)

        assertEquals(7, state.initialScrollToIndexRequest())
    }

    @Test
    fun consumedInitialAnchorCannotRecenterARememberedViewport() {
        val state = state()
        state.seedInitialScrollToIndex(7)
        state.consumeInitialScrollToIndexRequest(7)

        state.seedInitialScrollToIndex(12)

        assertEquals(-1, state.initialScrollToIndexRequest())
    }

    @Test
    fun ordinaryFirstBindDisablesLaterInitialSeeds() {
        val state = state()
        state.markInitialViewportBound()

        state.seedInitialScrollToIndex(4)

        assertEquals(-1, state.initialScrollToIndexRequest())
    }
}
