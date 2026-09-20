package com.rawsmusic.core.ui.widget.virtuallist

/**
 * Keep persistent-header details on their real content-derived scroll range. Reference's header is an
 * attached VirtualList layout item; an under-filled body does not gain a synthetic hero-height runway.
 */
internal fun maxScrollForPersistentHeaderContent(naturalMaxScrollPx: Int): Int =
    naturalMaxScrollPx.coerceAtLeast(0)

/**
 * True when a collection detail runs out of real content before the hero could be translated by its
 * full height. This is the case where the former Raw policy was visually most obvious: with only a few
 * rows it invented up to one hero-height of extra scroll, so the large non-list region travelled
 * upward even though the body itself had already run out of content.
 */
internal fun usesUnderfilledPersistentHeaderLane(
    naturalMaxScrollPx: Int,
    persistentHeaderHeightPx: Int,
    itemCount: Int,
): Boolean =
    persistentHeaderHeightPx > 0 &&
        itemCount > 0 &&
        naturalMaxScrollPx.coerceAtLeast(0) < persistentHeaderHeightPx

/**
 * Returns the amount a custom [ScrollableState] should report as consumed for a movable-header
 * detail page.
 *
 * This legacy helper is retained for the non-elastic path. Under-filled details are handled by the
 * dedicated boundary owner in ComposeVirtualList before this function is reached.
 */
internal fun consumedScrollDeltaWithTerminalPersistentHeader(
    deltaPx: Float,
    oldScrollPx: Float,
    newScrollPx: Float,
    maxScrollPx: Float,
    persistentHeaderHeightPx: Int,
    itemCount: Int,
    columns: Int = 1,
): Float {
    val translated = oldScrollPx - newScrollPx
    if (deltaPx >= 0f || persistentHeaderHeightPx <= 0 || itemCount <= 0) return translated

    val safeColumns = columns.coerceAtLeast(1)
    if (itemCount > safeColumns) return translated

    val maxScroll = maxScrollPx.coerceAtLeast(0f)
    val attemptedScroll = oldScrollPx - deltaPx
    val reachedTerminalEnd = newScrollPx >= maxScroll - TERMINAL_SCROLL_EPSILON_PX
    val hasUpwardRemainder = attemptedScroll > maxScroll + TERMINAL_SCROLL_EPSILON_PX
    return if (reachedTerminalEnd && hasUpwardRemainder) deltaPx else translated
}

/**
 * Consume a residual upward fling only for the legacy one-physical-row terminal lane. Collection
 * details now use the separate VirtualList edge owner; this helper remains only for non-edge fallback
 * geometry where no signed overshoot was produced.
 */
internal fun shouldConsumeTerminalPersistentHeaderFling(
    initialVelocityPxPerSecond: Float,
    currentScrollPx: Float,
    maxScrollPx: Float,
    persistentHeaderHeightPx: Int,
    itemCount: Int,
    columns: Int = 1,
): Boolean {
    if (initialVelocityPxPerSecond >= 0f || persistentHeaderHeightPx <= 0 || itemCount <= 0) {
        return false
    }
    if (itemCount > columns.coerceAtLeast(1)) return false
    val maxScroll = maxScrollPx.coerceAtLeast(0f)
    return currentScrollPx >= maxScroll - TERMINAL_SCROLL_EPSILON_PX
}

/**
 * Under-filled detail pages are cheap enough to keep header and rows on one exact pixel lane. This is
 * deliberately based on real content range rather than a hard-coded item/column count: list mode may
 * have two or three rows and still be under-filled, while a dense grid may fit several items in one
 * physical row.
 */
internal fun usesExactPersistentHeaderScrollLane(
    persistentHeaderHeightPx: Int,
    itemCount: Int,
    naturalMaxScrollPx: Int,
): Boolean = usesUnderfilledPersistentHeaderLane(
    naturalMaxScrollPx = naturalMaxScrollPx,
    persistentHeaderHeightPx = persistentHeaderHeightPx,
    itemCount = itemCount,
)


/**
 * Resolve the physical holder-layout scroll for a persistent-header detail. Large lists keep the
 * retained whole-row bucket and move the fractional remainder at the parent layer. Under-filled
 * details intentionally bypass that optimization: header and visible body holders must consume the
 * same exact pixel scroll, otherwise the header can move while the few rows look pinned.
 */
internal fun persistentHeaderBodyLayoutScrollYPx(
    scrollYPx: Int,
    rowStridePx: Int,
    exactPixelLane: Boolean,
): Int {
    val safeScroll = scrollYPx.coerceAtLeast(0)
    val safeStride = rowStridePx.coerceAtLeast(1)
    return if (exactPixelLane) safeScroll else (safeScroll / safeStride) * safeStride
}

private const val TERMINAL_SCROLL_EPSILON_PX = 0.5f
