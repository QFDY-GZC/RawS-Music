package com.rawsmusic.core.ui.scene.pages

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rawsmusic.core.ui.scene.COLLECTION_HEADER_RADIUS_DP

/**
 * Reference retained-view implementation collection/header artwork geometry.
 *
 * Collection artwork geometry is separate from player artwork geometry. Its header height
 * includes the status-bar inset; player-only artwork padding and scale do not apply here.
 */
internal object ReferenceHeroArtworkGeometry {
    /** Corner radius shared by collection headers and their retained artwork. */
    val cornerRadius: Dp = COLLECTION_HEADER_RADIUS_DP.dp
}
