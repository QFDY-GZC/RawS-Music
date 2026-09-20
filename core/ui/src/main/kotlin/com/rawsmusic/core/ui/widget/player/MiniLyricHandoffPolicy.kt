package com.rawsmusic.core.ui.widget.player

/**
 * Pure geometry used by the compact/mini lyric handoff.
 *
 * The compact viewport centers the active row, so a logical anchor change moves by the distance
 * between row centres. Keeping this policy outside Compose makes the first-frame compensation
 * deterministic and testable for mixed one-line/two-line lyric heights.
 */
internal fun compactLyricCenterTransitionDistancePx(
    previousAnchor: Int,
    newAnchor: Int,
    orderedRenderableIndices: List<Int>,
    rowHeightsPx: Map<Int, Int>,
    fallbackHeightPx: Float,
    spacingPx: Float,
): Float {
    val previousPosition = orderedRenderableIndices.indexOf(previousAnchor)
    val newPosition = orderedRenderableIndices.indexOf(newAnchor)
    if (previousPosition < 0 || newPosition < 0 || previousPosition == newPosition) return 0f

    fun heightAt(position: Int): Float {
        val index = orderedRenderableIndices[position]
        return rowHeightsPx[index]?.takeIf { it > 0 }?.toFloat()
            ?: fallbackHeightPx.coerceAtLeast(1f)
    }

    val first = minOf(previousPosition, newPosition)
    val last = maxOf(previousPosition, newPosition)
    var distance = 0f
    for (position in first until last) {
        distance += heightAt(position) * 0.5f + spacingPx + heightAt(position + 1) * 0.5f
    }
    return if (newPosition > previousPosition) distance else -distance
}

/**
 * Retains a small offscreen holder buffer around the logical mini-lyric window. Those rows remain
 * clipped, but an outgoing edge row is not disposed on the same frame the anchor changes.
 */
internal fun bufferedCompactLyricWindowIndices(
    availableIndices: List<Int>,
    visibleIndices: List<Int>,
    extraRows: Int = 2,
): List<Int> {
    if (availableIndices.isEmpty() || visibleIndices.isEmpty()) return visibleIndices
    val firstVisiblePosition = availableIndices.indexOf(visibleIndices.first())
    val lastVisiblePosition = availableIndices.indexOf(visibleIndices.last())
    if (firstVisiblePosition < 0 || lastVisiblePosition < firstVisiblePosition) return visibleIndices
    val extra = extraRows.coerceAtLeast(0)
    val start = (firstVisiblePosition - extra).coerceAtLeast(0)
    val endExclusive = (lastVisiblePosition + 1 + extra).coerceAtMost(availableIndices.size)
    return availableIndices.subList(start, endExclusive)
}
