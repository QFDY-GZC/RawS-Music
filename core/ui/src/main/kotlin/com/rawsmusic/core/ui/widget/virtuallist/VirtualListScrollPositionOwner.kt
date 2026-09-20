package com.rawsmusic.core.ui.widget.virtuallist

/** Translation relative to the child layout actually submitted, including across bucket wraps. */
internal fun virtualListLayoutScrollTranslation(exactScrollPx: Float, layoutScrollPx: Int): Float =
    layoutScrollPx - exactScrollPx

/**
 * Step 3 authoritative scroll owner for one VirtualList state.
 *
 * Exact scroll and the settled row remainder are plain Kotlin state. Drag/fling/programmatic owners
 * can update them for every pixel without publishing one broad Compose snapshot. Consumers which
 * genuinely need exact motion attach a narrow listener and own their own local projection.
 */
internal class VirtualListScrollPositionOwner(initialPx: Float = 0f) {
    private var exactPx: Float = initialPx
    private var renderRemainderPx: Int = 0
    private val exactPositionListeners = LinkedHashSet<() -> Unit>()
    private val renderInvalidationListeners = LinkedHashSet<() -> Unit>()

    val currentPx: Float
        get() = exactPx

    val currentRenderRemainderPx: Int
        get() = renderRemainderPx

    fun updateExact(valuePx: Float): Boolean {
        if (valuePx == exactPx) return false
        exactPx = valuePx
        if (exactPositionListeners.isNotEmpty()) {
            exactPositionListeners.toList().forEach { it() }
        }
        return true
    }

    fun updateRenderRemainder(valuePx: Int): Boolean {
        if (valuePx == renderRemainderPx) return false
        renderRemainderPx = valuePx
        invalidateRender()
        return true
    }

    fun invalidateRender() {
        if (renderInvalidationListeners.isNotEmpty()) {
            renderInvalidationListeners.toList().forEach { it() }
        }
    }

    fun addExactPositionListener(listener: () -> Unit) {
        exactPositionListeners += listener
    }

    fun removeExactPositionListener(listener: () -> Unit) {
        exactPositionListeners -= listener
    }

    fun addRenderInvalidationListener(listener: () -> Unit) {
        renderInvalidationListeners += listener
    }

    fun removeRenderInvalidationListener(listener: () -> Unit) {
        renderInvalidationListeners -= listener
    }
}
