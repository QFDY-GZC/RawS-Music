package com.rawsmusic.core.ui.widget.player

/** Source-timed sustained words must not finish their color sweep at an overlapping next word. */
internal fun lyricWordSweepEnd(
    beginMs: Long,
    sourceEndMs: Long,
    nextBeginMs: Long?,
    lineEndMs: Long,
    sustained: Boolean,
): Long {
    val fallback = nextBeginMs?.takeIf { it > beginMs }
        ?: lineEndMs.takeIf { it > beginMs } ?: (beginMs + 240L)
    val candidate = sourceEndMs.takeIf { it > beginMs } ?: fallback
    if (sustained && sourceEndMs > beginMs) return sourceEndMs
    return minOf(candidate, nextBeginMs?.takeIf { it > beginMs } ?: lineEndMs)
        .coerceAtLeast(beginMs + 1L)
}
