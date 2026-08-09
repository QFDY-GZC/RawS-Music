package com.rawsmusic.core.ui.widget

/** Complete-scene geometry for the frozen player <-> lyrics route. */
internal data class PlayerLyricsSceneMotion(
    val playerAlpha: Float,
    val playerScale: Float,
    val playerTranslationX: Float,
    val playerTranslationY: Float,
    val lyricAlpha: Float,
    val lyricScale: Float,
    val lyricTranslationX: Float,
    val lyricTranslationY: Float,
    val useTopLeftTransformOrigin: Boolean
)

internal fun resolvePlayerLyricsSceneMotion(
    session: PlayerLyricsTransitionSession?,
    lyricsFraction: Float
): PlayerLyricsSceneMotion {
    val f = lyricsFraction.coerceIn(0f, 1f)
    val active = session ?: return settledSceneMotion(f)
    return when (active.route) {
        PlayerLyricsTransitionRoute.VisibleArtworkAligned -> {
            val alignment = active.artworkAlignment
            if (alignment == null) {
                offscreenSceneZoom(f)
            } else {
                visibleArtworkAligned(f, alignment)
            }
        }
        PlayerLyricsTransitionRoute.OffscreenSceneZoom -> offscreenSceneZoom(f)
    }
}

/**
 * Real visible shared item: both complete scenes are transformed around the frozen artwork
 * geometry.  At f=0 the lyric scene's artwork maps exactly onto the player artwork; at f=1 the
 * player scene's artwork maps exactly onto the lyric artwork.  The overlay uses the same absolute
 * fraction, so cancel is the exact reverse path.
 */
private fun visibleArtworkAligned(
    lyricsFraction: Float,
    alignment: PlayerLyricsArtworkAlignment
): PlayerLyricsSceneMotion {
    val f = lyricsFraction.coerceIn(0f, 1f)
    return PlayerLyricsSceneMotion(
        playerAlpha = 1f - smoothStep(0.16f, 0.88f, f),
        playerScale = lerp(1f, alignment.playerToLyricScale, f),
        playerTranslationX = lerp(0f, alignment.playerToLyricTranslationX, f),
        playerTranslationY = lerp(0f, alignment.playerToLyricTranslationY, f),
        lyricAlpha = smoothStep(0.10f, 0.82f, f),
        lyricScale = lerp(alignment.lyricToPlayerScale, 1f, f),
        lyricTranslationX = lerp(alignment.lyricToPlayerTranslationX, 0f, f),
        lyricTranslationY = lerp(alignment.lyricToPlayerTranslationY, 0f, f),
        useTopLeftTransformOrigin = true
    )
}

/**
 * No real artwork candidate: do not synthesize a rectangle or add an
 * off-screen-distance translation.  Both complete scenes zoom about their center and crossfade;
 * X/Y translation are deliberately and permanently zero.
 */
private fun offscreenSceneZoom(lyricsFraction: Float): PlayerLyricsSceneMotion {
    val f = lyricsFraction.coerceIn(0f, 1f)
    return PlayerLyricsSceneMotion(
        playerAlpha = 1f - smoothStep(0.16f, 0.84f, f),
        playerScale = lerp(1f, OFFSCREEN_PLAYER_HIDDEN_SCALE, f),
        playerTranslationX = 0f,
        playerTranslationY = 0f,
        lyricAlpha = smoothStep(0.12f, 0.82f, f),
        lyricScale = lerp(OFFSCREEN_LYRIC_HIDDEN_SCALE, 1f, f),
        lyricTranslationX = 0f,
        lyricTranslationY = 0f,
        useTopLeftTransformOrigin = false
    )
}

private fun settledSceneMotion(lyricsFraction: Float): PlayerLyricsSceneMotion {
    val lyric = lyricsFraction >= 0.5f
    return PlayerLyricsSceneMotion(
        playerAlpha = if (lyric) 0f else 1f,
        playerScale = 1f,
        playerTranslationX = 0f,
        playerTranslationY = 0f,
        lyricAlpha = if (lyric) 1f else 0f,
        lyricScale = 1f,
        lyricTranslationX = 0f,
        lyricTranslationY = 0f,
        useTopLeftTransformOrigin = false
    )
}

private fun smoothStep(edge0: Float, edge1: Float, value: Float): Float {
    if (edge1 <= edge0) return if (value >= edge1) 1f else 0f
    val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun lerp(start: Float, stop: Float, fraction: Float): Float {
    val f = fraction.coerceIn(0f, 1f)
    return start + (stop - start) * f
}

private const val OFFSCREEN_PLAYER_HIDDEN_SCALE = 0.94f
private const val OFFSCREEN_LYRIC_HIDDEN_SCALE = 1.04f
