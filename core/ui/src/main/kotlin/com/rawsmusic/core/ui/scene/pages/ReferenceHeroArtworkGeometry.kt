package com.rawsmusic.core.ui.scene.pages

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rawsmusic.core.ui.scene.COLLECTION_HEADER_RADIUS_DP

/**
 * Reference retained-view implementation collection/header artwork geometry.
 *
 * This is deliberately separate from the player `scene_aa` geometry. The retained Reference
 * collection-detail recording (1080x2344) shows the header artwork itself occupying the exact
 * 1080x1080 window-width square from y=0, with the system/navigation chrome drawn above it.
 * The player-only 8dp inset + 0.975 ArtworkImageNode scale must therefore never be applied here.
 */
internal object ReferenceHeroArtworkGeometry {
    /** retained-view implementation normal rounded-header scene: all four retained artwork view corners use 16dp. */
    val cornerRadius: Dp = COLLECTION_HEADER_RADIUS_DP.dp

    fun visibleSidePx(outerSidePx: Float): Float = outerSidePx.coerceAtLeast(1f)
    fun visibleInsetPx(): Float = 0f
}
