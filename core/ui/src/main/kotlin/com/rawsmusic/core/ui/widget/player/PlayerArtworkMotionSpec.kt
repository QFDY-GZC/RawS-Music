package com.rawsmusic.core.ui.widget.player

/**
 * Shared artwork shape/inner-view endpoints recovered from Reference's player AA scene.
 *
 * Reference C0889 transforms the outer AAItemView. The actual AAImageView remains 8dp inside that
 * item and scene_aa applies a local 0.975 scale to the image. Keeping those two layers separate is
 * important: page stride/rotation use the outer item width, while the visible bitmap is smaller.
 */
internal const val STANDARD_PLAYER_ARTWORK_CONTENT_INSET_DP = 8f
internal const val STANDARD_PLAYER_ARTWORK_CONTENT_SCALE = 0.975f
internal const val STANDARD_PLAYER_ARTWORK_CORNER_RADIUS_DP = 12f
internal const val STANDARD_PLAYER_ARTWORK_ELEVATION_DP = 4f
internal const val LYRIC_HEADER_ARTWORK_CORNER_RADIUS_DP = 12f
