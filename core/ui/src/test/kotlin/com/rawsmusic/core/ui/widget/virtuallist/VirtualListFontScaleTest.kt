package com.rawsmusic.core.ui.widget.virtuallist

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.rawsmusic.core.ui.widget.player.resolveImmersivePlayerLayoutMetrics
import com.rawsmusic.core.ui.widget.text.resolveNativeTextLaneHeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualListFontScaleTest {
    @Test
    fun canvasTextSizeUsesTheFullComposeFontDensity() {
        val density = Density(density = 2f, fontScale = 1.5f)

        assertEquals(42f, density.virtualListSpToPx(14f), 0.001f)
    }

    @Test
    fun transitionTextAndSettledTextUseTheSameFontConversion() {
        val density = Density(density = 2.75f, fontScale = 1.4f)
        val settledTextPx = density.virtualListSpToPx(18f)
        val transitionTextPx = density.virtualListSpToPx(18f)

        assertEquals(settledTextPx, transitionTextPx, 0.001f)
        assertEquals(69.3f, transitionTextPx, 0.001f)
    }

    @Test
    fun gridCellHeightExpandsWhenItsResolvedTextLanesNeedMoreRoom() {
        val params = paramsFor(ListZoomIndex.NORMAL)
        val normal = computeVirtualListMetrics(
            widthPx = 900,
            density = 2f,
            fontDensity = Density(density = 2f, fontScale = 1f),
            mode = ComposeVirtualListDisplayMode.GRID_3,
            params = params,
        )
        val enlarged = computeVirtualListMetrics(
            widthPx = 900,
            density = 2f,
            fontDensity = Density(density = 2f, fontScale = 2f),
            mode = ComposeVirtualListDisplayMode.GRID_3,
            params = params,
        )

        assertTrue(enlarged.cellHeightPx > normal.cellHeightPx)
    }

    @Test
    fun fixedNativeTextLaneExpandsWhenTheResolvedFontOutgrowsItsBaseline() {
        val lane = resolveNativeTextLaneHeight(
            fontDensity = Density(density = 1f, fontScale = 2f),
            fontSizeSp = 14f,
            minimumHeight = 20.dp,
        )

        assertTrue(lane > 20.dp)
    }

    @Test
    fun immersiveLyricRowCountUsesResolvedSpHeightWithoutAnUpperScaleCap() {
        fun resolveRows(rowHeight: androidx.compose.ui.unit.Dp) =
            resolveImmersivePlayerLayoutMetrics(
                viewportHeight = 1_000.dp,
                clearArtworkBottom = 100.dp,
                titleInfoHeight = 58.dp,
                progressPanelHeight = 64.dp,
                transportControlsHeight = 68.dp,
                estimatedPrimaryRowHeight = rowHeight,
                hasSecondaryText = false,
            ).lyricPreviewRows

        assertTrue(resolveRows(62.dp) < resolveRows(31.dp))
    }
}
