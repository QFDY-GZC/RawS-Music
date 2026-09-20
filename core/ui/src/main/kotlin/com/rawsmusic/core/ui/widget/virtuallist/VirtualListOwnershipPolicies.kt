package com.rawsmusic.core.ui.widget.virtuallist

/**
 * VirtualList-style parent-layout barrier.
 *
 * Child Views are allowed to discover that they need layout while one physical population commit
 * is in progress, but that request must not escape to the Activity/ViewRoot until the atomic
 * holder/LayoutRes transaction has finished. Nested commits are supported because structure bind,
 * content bind and bounded holder relayout may call each other.
 */
internal class VirtualListLayoutRequestBarrier {
    private var depth = 0
    private var pending = false

    val isCommitActive: Boolean
        get() = depth > 0

    fun beginCommit() {
        depth += 1
    }

    /** @return true when the caller should forward the request to the real parent immediately. */
    fun requestParentLayoutNow(): Boolean {
        if (depth <= 0) return true
        pending = true
        return false
    }

    /**
     * @return true exactly once when the outermost commit closes with at least one deferred request.
     */
    fun endCommit(): Boolean {
        check(depth > 0) { "VirtualList layout commit underflow" }
        depth -= 1
        if (depth != 0 || !pending) return false
        pending = false
        return true
    }

    fun reset() {
        depth = 0
        pending = false
    }
}

/** Structural revision of one long-lived provider. Behavior/callback rebinding is intentionally free. */
internal class VirtualListPublicationRevision {
    var value: Int = 0
        private set

    fun markStructureChanged() {
        value += 1
    }

    fun markBehaviorRebound() = Unit
}

/**
 * VirtualList-style content publication gate.
 *
 * GenericPivot progress is motion on an already-bound holder population. Recomposition may still
 * revisit the publication call site for unrelated snapshot state, but an endpoint whose immutable
 * content/layout signature is unchanged must not re-run provider/holder binding work.
 */
internal class VirtualListContentPublicationGate<T : Any> {
    private var published: T? = null

    /** Pure query. Deferred publication must never consume a signature before bind succeeds. */
    fun differsFromPublished(signature: T): Boolean = published != signature

    /** Commit only after the holder/layout population has actually been published. */
    fun commit(signature: T) {
        published = signature
    }

    fun invalidate() {
        published = null
    }
}

internal enum class VirtualListOuterPublicationAction {
    FULL_CONTENT_BIND,
    LAYOUT_ONLY_REBIND,
    SHELL_ONLY_REUSE,
    DEFER_UNTIL_PREPARED,
}

/**
 * VirtualList keeps holder/content ownership separate from CURRENT/RETAINED
 * LayoutRes. A new transition can therefore reuse resident content while rebinding only the two
 * geometry roles; only a real content identity change needs provider/content binding again.
 */
internal fun resolveVirtualListOuterPublicationAction(
    outerAlreadyBound: Boolean,
    contentChanged: Boolean,
    residentContentReady: Boolean,
    motionActive: Boolean = false,
): VirtualListOuterPublicationAction = when {
    // Acquire CURRENT/NEXT and bind the first concrete item-holder population before
    // the transition progress step is allowed to advance. Raw's transition owner is already structurally active for
    // that acquisition frame, so `motionActive` alone cannot mean "content binding is forbidden".
    // Only an *already-bound* OUTER transaction is prohibited from rebinding cold content while
    // progress is live. Otherwise the first transaction can never become ready and the page falls
    // back to a no-motion scene, which is exactly the outer_publication_deferred(... outerBound=false)
    // signature seen in the 120 Hz traces.
    motionActive && outerAlreadyBound && !residentContentReady ->
        VirtualListOuterPublicationAction.DEFER_UNTIL_PREPARED
    !residentContentReady -> VirtualListOuterPublicationAction.FULL_CONTENT_BIND
    outerAlreadyBound && !contentChanged -> VirtualListOuterPublicationAction.SHELL_ONLY_REUSE
    else -> VirtualListOuterPublicationAction.LAYOUT_ONLY_REBIND
}

/**
 * Generation latch for one VirtualList zoom LayoutRes pair.
 *
 * A chained pinch may acquire the next source/target pair while the fingers are still down. The
 * new pair is not allowed to consume progress until its physical holder population has been
 * published once. This is the Raw endpoint-preflight/layout-engine bind boundary
 * before the first the transition progress step tick for the newly acquired transition owner.
 */
internal class VirtualListTransitionPreparationGate {
    private var generation = 0
    private var preparedGeneration = -1

    fun beginPair(): Int {
        generation += 1
        return generation
    }

    fun markPrepared(pairGeneration: Int) {
        if (pairGeneration == generation) preparedGeneration = pairGeneration
    }

    fun isPrepared(pairGeneration: Int): Boolean =
        pairGeneration == generation && preparedGeneration == pairGeneration
}

internal fun virtualListTransitionSessionMatches(
    existingSource: ComposeVirtualListDisplayMode,
    existingTarget: ComposeVirtualListDisplayMode,
    existingPairGeneration: Int,
    requestedSource: ComposeVirtualListDisplayMode,
    requestedTarget: ComposeVirtualListDisplayMode,
    requestedPairGeneration: Int,
): Boolean =
    existingSource == requestedSource &&
        existingTarget == requestedTarget &&
        existingPairGeneration == requestedPairGeneration

/**
 * Physical item identity scoped to one concrete provider instance.
 *
 * reference player shares one holder bank/holder slot pool only when both layouts point at the exact same provider identity provider object.
 * Two different provider objects which happen to compare equal must therefore not collapse onto the
 * same physical holder during GenericPivot.
 */
internal class VirtualListProviderItemKey(
    private val providerIdentity: Any,
    private val itemIdentity: Any,
) {
    /**
     * reference player's `holder bank` pool is shared by multiple LayoutRes only when they point at the exact same
     * `provider identity` provider object. Expose that identity to the physical allocator without making provider
     * equality part of any public model contract.
     */
    internal val physicalPoolIdentity: Any
        get() = providerIdentity

    internal fun sharesPhysicalPoolWith(other: Any): Boolean =
        other is VirtualListProviderItemKey && providerIdentity === other.providerIdentity

    override fun equals(other: Any?): Boolean =
        other is VirtualListProviderItemKey &&
            providerIdentity === other.providerIdentity &&
            itemIdentity == other.itemIdentity

    override fun hashCode(): Int =
        31 * System.identityHashCode(providerIdentity) + itemIdentity.hashCode()

    override fun toString(): String =
        "provider@${System.identityHashCode(providerIdentity)}:$itemIdentity"
}

/** Immutable identity of one prepared endpoint population. */
internal data class VirtualListPrewarmKey(
    val structuralRevision: Int,
    val rangeStart: Int,
    val rangeEnd: Int,
    val modeOrdinal: Int,
    val viewportWidthPx: Int,
    val viewportHeightPx: Int,
    val scrollYPx: Int,
    val custom: Boolean,
)

/**
 * Readiness is valid only while the exact physical slot -> content identity mapping is still live.
 * A high-water holder ring can stay resident while slots are rebound; checking only slot ids would
 * incorrectly treat that rebound population as the previously prepared endpoint.
 */
internal class VirtualListPrewarmReadiness {
    private var preparedKey: VirtualListPrewarmKey? = null
    private var preparedOwnership: Map<Int, String> = emptyMap()
    private val invalidatedSlots = LinkedHashSet<Int>()

    /** @return true when this call changed the prepared endpoint. */
    fun markPrepared(key: VirtualListPrewarmKey, ownership: Map<Int, String>): Boolean {
        val stableOwnership = LinkedHashMap(ownership)
        if (preparedKey == key && preparedOwnership == stableOwnership) return false
        preparedKey = key
        preparedOwnership = stableOwnership
        invalidatedSlots.clear()
        return true
    }

    fun isReady(key: VirtualListPrewarmKey, residentOwnership: Map<Int, String>): Boolean {
        if (preparedKey != key) return false
        if (invalidatedSlots.isNotEmpty()) return false
        if (preparedOwnership.isEmpty()) return residentOwnership.isEmpty()
        for ((slotId, identity) in preparedOwnership) {
            if (residentOwnership[slotId] != identity) return false
        }
        return true
    }

    /**
     * Return only the physical holder slots whose previously prepared content is still resident for
     * the exact same endpoint. VirtualList's ring does not rebind every holder slot merely because a few edge
     * positions rotate to different items; those entering positions are updated independently.
     *
     * A different endpoint key deliberately returns no reusable slots: viewport/scroll/mode or a
     * structural revision can change local content geometry even when song identities happen to
     * match.
     */
    fun readySlotIds(
        key: VirtualListPrewarmKey,
        residentOwnership: Map<Int, String>,
    ): Set<Int> {
        if (preparedKey != key || preparedOwnership.isEmpty()) return emptySet()
        return buildSet(preparedOwnership.size) {
            for ((slotId, identity) in preparedOwnership) {
                if (slotId !in invalidatedSlots && residentOwnership[slotId] == identity) add(slotId)
            }
        }
    }

    /** Invalidate one physical holder without throwing away readiness for the rest of the ring. */
    fun invalidateSlot(slotId: Int) {
        if (slotId in preparedOwnership) invalidatedSlots.add(slotId)
    }

    /** Diagnostics only: expose the last prepared endpoint identity without mutating readiness. */
    fun debugPreparedKey(): VirtualListPrewarmKey? = preparedKey

    fun invalidate() {
        preparedKey = null
        preparedOwnership = emptyMap()
        invalidatedSlots.clear()
    }
}
