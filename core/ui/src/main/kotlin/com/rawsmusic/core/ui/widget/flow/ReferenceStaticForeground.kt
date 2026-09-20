package com.rawsmusic.core.ui.widget.flow

import androidx.compose.ui.graphics.Color

/**
 * Reference's static-artwork/artwork player skin keeps foreground glyphs in one white family regardless of the
 * system light/dark theme. RawSMusic's STATIC background is the equivalent surface, so any text
 * or icon painted directly over that backdrop must not fall back to theme black/accent colours.
 */
internal fun usesReferenceStaticForeground(): Boolean =
    RawFlowTuningState.style == RawBackgroundStyle.STATIC

/**
 * Preserve the existing non-STATIC colour byte-for-byte. [staticAlpha] only describes the white
 * foreground tone used on the STATIC artwork surface; it must not accidentally re-alpha theme colours
 * in FLOW mode.
 */
internal fun referenceStaticForeground(
    fallback: Color,
    staticAlpha: Float = 1f,
): Color = if (usesReferenceStaticForeground()) {
    Color.White.copy(alpha = staticAlpha.coerceIn(0f, 1f))
} else {
    fallback
}
