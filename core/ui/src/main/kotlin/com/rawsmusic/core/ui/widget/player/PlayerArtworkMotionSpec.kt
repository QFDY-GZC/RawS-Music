package com.rawsmusic.core.ui.widget.player

/**
 * Shared artwork shape/inner-view endpoints derived from Reference's player artwork scene.
 *
 * Reference ArtworkPager transforms the outer ArtworkItemNode. The actual ArtworkImageNode remains 8dp inside that
 * item and scene_aa applies a local 0.975 scale to the image. Keeping those two layers separate is
 * important: page stride/rotation use the outer item width, while the visible bitmap is smaller.
 */
internal const val STANDARD_PLAYER_ARTWORK_CONTENT_INSET_DP = 8f
internal const val STANDARD_PLAYER_ARTWORK_CONTENT_SCALE = 0.975f
internal const val STANDARD_PLAYER_ARTWORK_CORNER_RADIUS_DP = 12f
internal const val STANDARD_PLAYER_ARTWORK_ELEVATION_DP = 4f

// reference implementation routes play/pause artwork resizing through compact artwork transition -> outer-holder motion routine: the outer
// holder changes layout state while the child artwork image view keeps its 8dp / 0.975 / 12dp / 4dp
// scene parameters. The exact alternate size is skin/resource dependent; 0.94 is a conservative
// Raw endpoint chosen to reproduce the supplied portrait recording while keeping ownership source-exact.
internal const val STANDARD_PLAYER_ARTWORK_PAUSED_HOLDER_SCALE = 0.94f
internal const val STANDARD_PLAYER_ARTWORK_PLAY_STATE_TRANSITION_MS = 300
internal const val LYRIC_HEADER_ARTWORK_CORNER_RADIUS_DP = 12f
