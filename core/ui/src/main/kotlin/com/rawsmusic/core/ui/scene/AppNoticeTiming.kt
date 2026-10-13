package com.rawsmusic.core.ui.scene

internal fun noticeVisibleDurationMs(animationDurationMs: Long?): Long =
    if (animationDurationMs != null && animationDurationMs > 3500L) animationDurationMs + 1000L
    else 3500L
