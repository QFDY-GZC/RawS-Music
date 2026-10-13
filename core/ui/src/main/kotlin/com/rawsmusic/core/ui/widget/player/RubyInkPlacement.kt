package com.rawsmusic.core.ui.widget.player

internal data class RubyInkPlacement(val annotationTop: Int, val baseTop: Int,
    val height: Int, val firstBaseline: Int, val lastBaseline: Int)

internal fun rubyInkPlacement(annotationFirstBaseline: Int, annotationLastBaseline: Int,
    annotationInkTop: Int, annotationInkBottom: Int,
    baseFirstBaseline: Int, baseLastBaseline: Int, baseInkTop: Int, baseInkBottom: Int,
    gap: Int, rowBaseInkTop: Int = baseInkTop): RubyInkPlacement {
    val annotationTop = -(annotationFirstBaseline + annotationInkTop)
    val annotationHeight = (annotationLastBaseline + annotationInkBottom + annotationTop).coerceAtLeast(0)
    val baseTop = annotationHeight + gap.coerceAtLeast(0) - baseFirstBaseline - rowBaseInkTop
    return RubyInkPlacement(annotationTop, baseTop,
        (baseTop + baseLastBaseline + baseInkBottom).coerceAtLeast(0),
        baseTop + baseFirstBaseline, baseTop + baseLastBaseline)
}
