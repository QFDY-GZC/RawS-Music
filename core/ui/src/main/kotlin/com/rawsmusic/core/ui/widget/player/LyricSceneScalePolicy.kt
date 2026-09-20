package com.rawsmusic.core.ui.widget.player

/**
 * Reference lyric holders keep the active-scene text measurement stable and express regular-vs-
 * active size through the scene/holder transform. There is no second generic 0.92 scale factor.
 */
internal object LyricSceneScalePolicy {
    fun inactiveHolderScale(regularTextScale: Float, activeTextScale: Float): Float {
        if (!regularTextScale.isFinite() || !activeTextScale.isFinite() || activeTextScale <= 0f) return 1f
        return (regularTextScale / activeTextScale).coerceIn(0.01f, 1f)
    }
}
