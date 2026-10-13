package com.rawsmusic.core.ui.theme

/** Keeps font diagnostics in the exported app log sparse while retaining each distinct layout. */
class FontScaleLayoutReporter(
    private val surface: String,
    private val emit: (String) -> Unit,
) {
    private data class Sample(
        val preferencePercent: Int,
        val fontName: String,
        val effectiveFontScale: Float,
        val font22spPx: Float,
        val widthPx: Int,
        val heightPx: Int,
    )

    private var lastSample: Sample? = null

    fun report(
        preferencePercent: Int,
        fontName: String,
        effectiveFontScale: Float,
        font22spPx: Float,
        widthPx: Int,
        heightPx: Int,
    ) {
        val sample = Sample(
            preferencePercent,
            fontName,
            effectiveFontScale,
            font22spPx,
            widthPx,
            heightPx,
        )
        if (sample == lastSample) return
        lastSample = sample
        emit(
            "render surface=$surface pref=$preferencePercent% font=$fontName effective=$effectiveFontScale " +
                "font22spPx=$font22spPx layout=${widthPx}x$heightPx",
        )
    }
}
