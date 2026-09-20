package com.rawsmusic.core.ui.widget.virtuallist

/**
 * Eligibility for the one-Node HOME/category GenericPivot renderer.
 *
 * One-sided ordinary holders only need the already-derived mode=4 page-space transform and are
 * representable without a child layout pass. Heterogeneous Compose holders stay on their retained
 * physical holder so HOME hero/cards/carousel keep the same composition identity through the outer
 * transition. A shared ordinary holder is eligible only when CURRENT and RETAINED use the same
 * visual topology; LIST<->GRID child-layout morphs remain on the compatibility renderer until their
 * endpoint child RenderNodes are modeled directly.
 */
internal object VirtualListOuterTransitionNodePolicy {
    fun eligible(
        kind: VirtualListOuterDualLayoutKind?,
        sameVisualTopology: Boolean,
        selectionActive: Boolean,
        customHolder: Boolean,
    ): Boolean {
        if (selectionActive || customHolder) return false
        return when (kind) {
            VirtualListOuterDualLayoutKind.SHARED -> sameVisualTopology
            VirtualListOuterDualLayoutKind.CURRENT_ONLY,
            VirtualListOuterDualLayoutKind.RETAINED_ONLY -> true
            null -> false
        }
    }

    /**
     * Advance the item holder's internal retained scene only when the same physical holder
     * owns both endpoint scenes. One-sided CURRENT/NEXT holders keep their already-bound local
     * scene and only receive the outer VirtualList LayoutRes transform.
     */
    fun shouldUpdateInternalScene(kind: VirtualListOuterTransitionKind?): Boolean =
        kind == VirtualListOuterTransitionKind.SHARED
}
