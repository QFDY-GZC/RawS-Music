package com.rawsmusic.core.ui.widget.virtuallist

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.IntRect

private object VirtualListUnscopedPhysicalPoolIdentity

/**
 * Reference-aligned LayoutRes stored by the scene-independent logical physical-holder identity.
 * [position] keeps the existing row-bucket geometry, while [renderRemainderPx] carries the exact
 * viewport translation owned by the settled renderer. Outer GenericPivot resolves both together so
 * transition endpoints are pixel-identical to the corresponding settled frames.
 */
internal data class VirtualListPhysicalLayoutRes(
    val index: Int,
    val mode: ComposeVirtualListDisplayMode,
    val params: ListZoomParams,
    val position: ComposeItemPosition,
    val drawContentEnabled: Boolean,
    /** Exact viewport artwork admission owned by this role's LayoutRes publication. */
    val artworkVisible: Boolean,
    /**
     * Fractional/exact scroll translation which the settled renderer applies outside [position].
     * GenericPivot consumes LayoutRes without that parent translation, so the role must carry the
     * same remainder to make both motion endpoints pixel-identical to their settled frames.
     */
    val renderRemainderPx: Int,
)

internal fun VirtualListPhysicalLayoutRes.exactViewportPosition(): ComposeItemPosition {
    if (renderRemainderPx == 0) return position
    val b = position.bounds
    return position.copy(
        bounds = IntRect(
            left = b.left,
            top = b.top - renderRemainderPx,
            right = b.right,
            bottom = b.bottom - renderRemainderPx,
        )
    )
}

/**
 * Physical holder allocator shared by settled, internal-zoom and outer GenericPivot layouts.
 *
 * Reference's VirtualList does not recreate an ArtworkItemNode when the same provider item moves from the
 * current layout (`r`) to the retained layout or back.  The View object is the owner.  This keyed
 * allocator mirrors that contract: provider/layout code only asks which physical slot owns a stable
 * item key. Slots are recycled only after the key leaves every attached layout population.
 */
@Stable
internal class VirtualListPhysicalSlotPool(
    initialCapacity: Int = 4,
) {
    private data class Slot(
        var key: Any? = null,
        var seenFrame: Int = Int.MIN_VALUE,
        /**
         * reference player owns a separate `holder bank` holder pool for a different `provider identity` provider. Keep the bank
         * identity after a key leaves the viewport so another provider cannot steal the resident
         * View slot and force ordinary<->custom type replacement during the next transition.
         */
        var poolIdentity: Any? = null,
    )

    private val slots = ArrayList<Slot>(initialCapacity.coerceAtLeast(1)).apply {
        repeat(initialCapacity.coerceAtLeast(1)) { add(Slot()) }
    }
    private val keyToSlot = LinkedHashMap<Any, Int>()
    // Preflight destination holders must survive the still-visible source provider's cleanup frame.
    // Reference binds/prepares target holder Views before GenericPivot starts; it does not allocate
    // them on the first drag vsync. Reservations are removed as soon as a real layout frame attaches
    // the key, or explicitly when a cancelled preflight leaves composition.
    private val reservedKeys = LinkedHashSet<Any>()
    private var frame = 0

    fun beginFrame(attachedKeys: List<Any>, keepOpen: Boolean = false) {
        frame += 1
        markAttached(attachedKeys)
        if (!keepOpen) cleanupUnseen()
    }

    /** Add a second retained/current layout population to the same physical frame. */
    fun appendFrame(attachedKeys: List<Any>, closeFrame: Boolean = true) {
        markAttached(attachedKeys)
        if (closeFrame) cleanupUnseen()
    }

    private fun markAttached(attachedKeys: List<Any>) {
        val unique = LinkedHashSet<Any>(attachedKeys.size)
        attachedKeys.forEach(unique::add)

        // reference player's holder bank keeps overlapping active v0s in their existing ring slots. Claim every
        // still-resident mapping before any newly-entered key is allowed to recycle an inactive
        // slot. The two-pass order matters when the visible window moves toward smaller positions:
        // new leading keys otherwise appear before the overlapping keys in adapter order.
        unique.forEach { key ->
            val slotId = validMappedSlot(key) ?: return@forEach
            slots[slotId].seenFrame = frame
            reservedKeys.remove(key)
        }

        // Only genuinely new keys may consume an inactive slot from their own provider bank.
        unique.forEach { key ->
            if (validMappedSlot(key) != null) return@forEach
            val slotId = ensureSlot(key)
            slots[slotId].seenFrame = frame
            reservedKeys.remove(key)
        }
    }

    private fun validMappedSlot(key: Any): Int? {
        val slotId = keyToSlot[key] ?: return null
        val slot = slots.getOrNull(slotId)
        if (slot == null || slot.key != key) {
            keyToSlot.remove(key)
            return null
        }
        return slotId
    }

    private fun ensureSlot(key: Any): Int {
        validMappedSlot(key)?.let { return it }
        val desiredPoolIdentity = poolIdentityForKey(key)

        // First reuse a genuinely empty holder already owned by this provider bank.
        var slotId = slots.indexOfFirst { slot ->
            slot.key == null && slot.poolIdentity === desiredPoolIdentity
        }

        // Mark a leaving holder inactive instead of removing it from the ring. A new
        // position entering the same provider may recycle one of those inactive v0s; evict the old
        // item mapping only at that moment. Active and preflight-reserved slots are never candidates.
        if (slotId < 0 && key is VirtualListProviderItemKey) {
            slotId = slots.indexOfFirst { slot ->
                slot.poolIdentity === desiredPoolIdentity &&
                    slot.key != null &&
                    slot.seenFrame != frame &&
                    slot.key !in reservedKeys
            }
            if (slotId >= 0) {
                slots[slotId].key?.let(keyToSlot::remove)
            }
        }

        // Then claim an unowned high-water slot before growing the physical holder ring. A holder
        // banked to another provider is deliberately unavailable, matching separate reference player d2s.
        if (slotId < 0) {
            slotId = slots.indexOfFirst { slot ->
                slot.key == null && slot.poolIdentity == null
            }
        }
        if (slotId < 0) {
            slotId = slots.size
            slots += Slot(poolIdentity = desiredPoolIdentity)
        }
        slots[slotId].apply {
            if (poolIdentity == null) poolIdentity = desiredPoolIdentity
            this.key = key
            seenFrame = Int.MIN_VALUE
        }
        keyToSlot[key] = slotId
        return slotId
    }

    private fun poolIdentityForKey(key: Any): Any =
        (key as? VirtualListProviderItemKey)?.physicalPoolIdentity
            ?: VirtualListUnscopedPhysicalPoolIdentity

    /** Reserve cold destination holders without starting/closing the visible provider frame. */
    fun reserveKeys(keys: List<Any>) {
        val unique = LinkedHashSet<Any>(keys.size)
        keys.forEach(unique::add)

        // Protect every existing destination mapping first so a missing prewarm key cannot recycle
        // another destination holder slot merely because it appeared earlier in the reservation list.
        unique.forEach { key ->
            if (validMappedSlot(key) != null) {
                reservedKeys.add(key)
            } else {
                reservedKeys.remove(key)
            }
        }
        unique.forEach { key ->
            if (validMappedSlot(key) == null) ensureSlot(key)
            reservedKeys.add(key)
        }
    }

    /** Release only reservations still owned by a preparation composition. */
    fun releaseReservedKeys(keys: List<Any>) {
        keys.forEach(reservedKeys::remove)
    }

    private fun cleanupUnseen() {
        for (slotId in slots.indices) {
            val slot = slots[slotId]
            val key = slot.key ?: continue
            if (slot.seenFrame != frame && key !in reservedKeys) {
                if (key is VirtualListProviderItemKey) {
                    // The holder pool retains the physical object and its inactive ring position.
                    // Preserve the scoped key->slot mapping so an overlapping/revisited
                    // provider item can reactivate the exact same View/RenderNode. It remains
                    // logically unbound until this frame sees or reserves it again.
                    slot.seenFrame = Int.MIN_VALUE
                    continue
                }
                keyToSlot.remove(key)
                slot.key = null
                slot.seenFrame = Int.MIN_VALUE
            }
        }
    }

    fun activeSlotIds(): Set<Int> = buildSet {
        for (slotId in slots.indices) {
            val slot = slots[slotId]
            val key = slot.key ?: continue
            if (slot.seenFrame == frame || key in reservedKeys) add(slotId)
        }
    }

    fun slotIdForKey(key: Any): Int = keyToSlot[key] ?: -1

    fun keyForSlot(slotId: Int): Any? = slots.getOrNull(slotId)?.key

    /**
     * Physical residency is high-water capacity, not current provider attachment. VirtualList keeps
     * the View allocated when its holder slot slot becomes temporarily empty and reuses that same View when
     * a later key claims the slot.
     */
    fun isResidentSlot(slotId: Int): Boolean = slotId in slots.indices

    fun isSlotBound(slotId: Int): Boolean {
        val slot = slots.getOrNull(slotId) ?: return false
        val key = slot.key ?: return false
        return slot.seenFrame == frame || key in reservedKeys
    }

    val physicalCapacity: Int
        get() = slots.size

    fun clear() {
        keyToSlot.clear()
        reservedKeys.clear()
        slots.forEach {
            it.key = null
            it.seenFrame = Int.MIN_VALUE
            it.poolIdentity = null
        }
    }
}

@Composable
internal fun rememberVirtualListPhysicalSlotPool(): VirtualListPhysicalSlotPool =
    remember { VirtualListPhysicalSlotPool() }
