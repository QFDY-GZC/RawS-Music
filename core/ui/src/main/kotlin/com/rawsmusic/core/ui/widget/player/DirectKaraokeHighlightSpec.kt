package com.rawsmusic.core.ui.widget.player

internal object DirectKaraokeHighlightSpec {
    // The transparent edge must travel past the glyphs before removing the mask. Otherwise the
    // final feather strip jumps from partial opacity to solid at exactly 100% media progress.
    fun sweepDistance(width: Float, featherPx: Float): Float =
        width.coerceAtLeast(0f) + featherPx.coerceIn(0f, width.coerceAtLeast(0f))

    fun featherStart(width: Float, progress: Float, featherPx: Float, rtl: Boolean): Float {
        val p = progress.coerceIn(0f, 1f)
        val feather = featherPx.coerceIn(0f, width.coerceAtLeast(0f))
        val distance = sweepDistance(width, feather) * p
        return if (rtl) width - distance else distance - feather
    }
    fun progress(positionMs: Float, beginMs: Long, endMs: Long): Float {
        val duration = (endMs - beginMs).coerceAtLeast(1L).toFloat()
        return ((positionMs - beginMs.toFloat()) / duration).coerceIn(0f, 1f)
    }

    fun maskStops(progress: Float, featherFraction: Float): FloatArray {
        val p = progress.coerceIn(0f, 1f)
        val feather = featherFraction.coerceIn(0f, 1f)
        val edge = p * (1f + feather)
        return floatArrayOf(0f, (edge - feather).coerceIn(0f, 1f), edge.coerceIn(0f, 1f), 1f)
    }
}
