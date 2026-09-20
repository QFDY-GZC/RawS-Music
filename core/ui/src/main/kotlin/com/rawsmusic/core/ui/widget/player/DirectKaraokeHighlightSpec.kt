package com.rawsmusic.core.ui.widget.player

internal object DirectKaraokeHighlightSpec {
    fun progress(positionMs: Float, beginMs: Long, endMs: Long): Float {
        val duration = (endMs - beginMs).coerceAtLeast(1L).toFloat()
        return ((positionMs - beginMs.toFloat()) / duration).coerceIn(0f, 1f)
    }

    fun maskStops(progress: Float, featherFraction: Float): FloatArray {
        val p = progress.coerceIn(0f, 1f)
        val feather = featherFraction.coerceIn(0f, 1f)
        return floatArrayOf(0f, (p - feather).coerceIn(0f, 1f), p, 1f)
    }
}
