package com.rawsmusic.core.ui.widget.virtuallist

import org.junit.Assert.assertEquals
import org.junit.Test

class ArtworkRadiusPolicyTest {
    @Test
    fun roundedThemeTrackAndAlbumMatrixMatchesPolicy() {
        val small = ListZoomLevels.params.getValue(ListZoomIndex.SMALL)
        val normal = ListZoomLevels.params.getValue(ListZoomIndex.NORMAL)
        val zoomed = ListZoomLevels.params.getValue(ListZoomIndex.ZOOMED)

        assertRadius(8f, ComposeVirtualListDisplayMode.LIST_SMALL, small, VirtualListArtworkRadiusType.TRACK, 32f)
        assertRadius(8f, ComposeVirtualListDisplayMode.LIST_SMALL, small, VirtualListArtworkRadiusType.ALBUM, 32f)
        assertRadius(18f, ComposeVirtualListDisplayMode.LIST_NORMAL, normal, VirtualListArtworkRadiusType.TRACK, 80f)
        assertRadius(8f, ComposeVirtualListDisplayMode.LIST_NORMAL, normal, VirtualListArtworkRadiusType.ALBUM, 80f)
        assertRadius(24f, ComposeVirtualListDisplayMode.LIST_ZOOMED, zoomed, VirtualListArtworkRadiusType.TRACK, 120f)
        assertRadius(12f, ComposeVirtualListDisplayMode.LIST_ZOOMED, zoomed, VirtualListArtworkRadiusType.ALBUM, 120f)
        assertRadius(16f, ComposeVirtualListDisplayMode.GRID_4, null, VirtualListArtworkRadiusType.TRACK, 88f)
        assertRadius(8f, ComposeVirtualListDisplayMode.GRID_4, null, VirtualListArtworkRadiusType.ALBUM, 88f)
        assertRadius(16f, ComposeVirtualListDisplayMode.GRID_3, null, VirtualListArtworkRadiusType.TRACK, 112f)
        assertRadius(8f, ComposeVirtualListDisplayMode.GRID_3, null, VirtualListArtworkRadiusType.ALBUM, 112f)
        assertRadius(24f, ComposeVirtualListDisplayMode.GRID_2, null, VirtualListArtworkRadiusType.TRACK, 168f)
        assertRadius(16f, ComposeVirtualListDisplayMode.GRID_2, null, VirtualListArtworkRadiusType.ALBUM, 168f)
    }

    @Test
    fun otherTypeUsesSmallRadiusThenMatchHeightCircle() {
        val small = ListZoomLevels.params.getValue(ListZoomIndex.SMALL)
        val normal = ListZoomLevels.params.getValue(ListZoomIndex.NORMAL)
        val zoomed = ListZoomLevels.params.getValue(ListZoomIndex.ZOOMED)

        assertRadius(8f, ComposeVirtualListDisplayMode.LIST_SMALL, small, VirtualListArtworkRadiusType.OTHER, 32f)
        assertRadius(40f, ComposeVirtualListDisplayMode.LIST_NORMAL, normal, VirtualListArtworkRadiusType.OTHER, 80f)
        assertRadius(60f, ComposeVirtualListDisplayMode.LIST_ZOOMED, zoomed, VirtualListArtworkRadiusType.OTHER, 120f)
        assertRadius(44f, ComposeVirtualListDisplayMode.GRID_4, null, VirtualListArtworkRadiusType.OTHER, 88f)
        assertRadius(56f, ComposeVirtualListDisplayMode.GRID_3, null, VirtualListArtworkRadiusType.OTHER, 112f)
        assertRadius(84f, ComposeVirtualListDisplayMode.GRID_2, null, VirtualListArtworkRadiusType.OTHER, 168f)
    }

    @Test
    fun radiusInterpolationIsContinuousAndEndpointExact() {
        assertEquals(18f, interpolateArtworkRadiusDp(18f, 16f, 0f), 0.0001f)
        assertEquals(17f, interpolateArtworkRadiusDp(18f, 16f, 0.5f), 0.0001f)
        assertEquals(16f, interpolateArtworkRadiusDp(18f, 16f, 1f), 0.0001f)

        assertEquals(8f, interpolateArtworkRadiusDp(8f, 16f, -1f), 0.0001f)
        assertEquals(16f, interpolateArtworkRadiusDp(8f, 16f, 2f), 0.0001f)
    }

    @Test
    fun circularOtherTypeRemainsCircularDuringLinearPinch() {
        val sourceSide = 80f
        val targetSide = 168f
        val sourceRadius = sourceSide * 0.5f
        val targetRadius = targetSide * 0.5f

        for (step in 0..20) {
            val progress = step / 20f
            val side = sourceSide + (targetSide - sourceSide) * progress
            val radius = interpolateArtworkRadiusDp(sourceRadius, targetRadius, progress)
            assertEquals(side * 0.5f, radius, 0.0001f)
        }
    }

    private fun assertRadius(
        expected: Float,
        mode: ComposeVirtualListDisplayMode,
        params: ListZoomParams?,
        type: VirtualListArtworkRadiusType,
        sideDp: Float,
    ) {
        assertEquals(
            expected,
            resolveArtworkRadiusDp(mode, params, type, sideDp),
            0.0001f,
        )
    }
}

