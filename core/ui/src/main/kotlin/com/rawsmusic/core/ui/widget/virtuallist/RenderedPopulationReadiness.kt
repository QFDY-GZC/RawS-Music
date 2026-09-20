package com.rawsmusic.core.ui.widget.virtuallist

/** Readiness is a draw acknowledgement, not a provider publication or elapsed-frame counter. */
internal class RenderedPopulationReadiness<K> {
    private val drawn = HashSet<K>()
    fun isReady(key: K): Boolean = key in drawn
    fun acknowledgeDraw(key: K) { drawn.add(key) }
    fun invalidate() { drawn.clear() }
}

internal fun shouldReconcilePresentationStructure(
    previousGeneration: Long,
    nextGeneration: Long,
    previousCount: Int,
    nextCount: Int,
): Boolean = previousGeneration != nextGeneration || previousCount != nextCount
