package com.rawsmusic.core.ui.widget.player

// Motion transforms the holder, not its text clock. Only parking/visibility disables text motion.
@Suppress("UNUSED_PARAMETER")
internal fun playerTitleMarqueeVisible(holderVisible: Boolean, transitionActive: Boolean): Boolean =
    holderVisible

internal fun playerTitleLineHeightPx(minimumHeight: Float, fontTop: Float, fontBottom: Float): Float =
    maxOf(minimumHeight, fontBottom - fontTop + 2f)
