package com.rawsmusic.core.ui.widget.virtuallist

/**
 * Single-renderer invalidation lease for the persistent VirtualList holder runtime.
 *
 * Compose may attach the incoming settled/transition Modifier.Node before detaching the outgoing
 * shell. A bare nullable callback lets the old shell's onDetach clear the new shell's callback,
 * leaving provider/artwork completions with no draw owner. Bind the callback to an identity token so
 * only the shell which currently owns invalidation can detach itself.
 */
internal enum class VirtualListInvalidationKind {
    CONTENT,
    MOTION,
    PARENT_TRANSFORM,
    ARTWORK,
}

internal data class VirtualListRenderInvalidation(
    val kind: VirtualListInvalidationKind,
    val slotId: Int = -1,
) {
    companion object {
        fun content(): VirtualListRenderInvalidation =
            VirtualListRenderInvalidation(VirtualListInvalidationKind.CONTENT)

        fun motion(): VirtualListRenderInvalidation =
            VirtualListRenderInvalidation(VirtualListInvalidationKind.MOTION)

        fun parentTransform(): VirtualListRenderInvalidation =
            VirtualListRenderInvalidation(VirtualListInvalidationKind.PARENT_TRANSFORM)

        fun artwork(slotId: Int): VirtualListRenderInvalidation =
            VirtualListRenderInvalidation(VirtualListInvalidationKind.ARTWORK, slotId)
    }
}

internal class VirtualListRenderInvalidationOwner {
    private var owner: Any? = null
    private var invalidator: ((VirtualListRenderInvalidation) -> Unit)? = null

    fun attach(owner: Any, invalidator: (VirtualListRenderInvalidation) -> Unit) {
        this.owner = owner
        this.invalidator = invalidator
    }

    fun detach(owner: Any) {
        if (this.owner !== owner) return
        this.owner = null
        invalidator = null
    }

    fun invalidate(signal: VirtualListRenderInvalidation = VirtualListRenderInvalidation.content()) {
        invalidator?.invoke(signal)
    }

    fun clear() {
        owner = null
        invalidator = null
    }

    internal fun isOwnedBy(owner: Any): Boolean = this.owner === owner
}
