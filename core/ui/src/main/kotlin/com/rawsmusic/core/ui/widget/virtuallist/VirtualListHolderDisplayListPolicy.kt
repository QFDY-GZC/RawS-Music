package com.rawsmusic.core.ui.widget.virtuallist

/**
 * Retained holder display lists mirror Android View/RenderNode ownership on modern Android.
 * API 24-28 keep the existing direct Canvas path because android.graphics.RenderNode is public
 * starting at API 29. Software canvases must never receive RenderNode draw commands.
 */
internal object VirtualListHolderDisplayListPolicy {
    const val MIN_RENDER_NODE_API = 29

    fun shouldUseRenderNode(apiLevel: Int, hardwareAccelerated: Boolean): Boolean =
        apiLevel >= MIN_RENDER_NODE_API && hardwareAccelerated
}

internal enum class VirtualListHolderFrameCommit {
    DIRECT_BOUNDS,
    MEASURE_AND_LAYOUT,
}

/**
 * The retained scene layout changes an already-prepared dual-LayoutRes View with
 * setLeftTopRightBottom(); it does not re-enter the ViewGroup layout traversal for every progress
 * tick. Settled/single-layout publication keeps the normal measure/layout path.
 */
internal fun resolveVirtualListHolderFrameCommit(dualLayout: Boolean): VirtualListHolderFrameCommit =
    if (dualLayout) VirtualListHolderFrameCommit.DIRECT_BOUNDS
    else VirtualListHolderFrameCommit.MEASURE_AND_LAYOUT
