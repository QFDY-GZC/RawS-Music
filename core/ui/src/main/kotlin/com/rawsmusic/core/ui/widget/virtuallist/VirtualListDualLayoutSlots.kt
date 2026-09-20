package com.rawsmusic.core.ui.widget.virtuallist

/**
 * Step 4 canonical current/retained layout ownership for one logical VirtualList holder identity.
 *
 * This is intentionally plain Kotlin state. A stable item key owns at most one CURRENT layout and
 * one RETAINED layout in the active frame. Scene names are not part of this identity: scene/provider
 * ownership chooses which layout slot is populated, while the physical item remains the same holder.
 *
 * v35 is the migration bridge only: legacy Compose render slots may still be scene-prefixed until
 * Step 4B consumes these dual slots directly and draws one physical holder once.
 */
internal enum class VirtualListPhysicalLayoutRole {
    CURRENT,
    RETAINED,
}

internal class VirtualListDualLayoutSlots<K : Any, L : Any> {
    internal data class Record<L : Any>(
        var current: L? = null,
        var retained: L? = null,
        var currentSeenFrame: Int = Int.MIN_VALUE,
        var retainedSeenFrame: Int = Int.MIN_VALUE,
    )

    internal data class Snapshot<K : Any, L : Any>(
        val key: K,
        val current: L?,
        val retained: L?,
    )

    private val records = LinkedHashMap<K, Record<L>>()
    private var frame = 0
    private var cachedSnapshots: List<Snapshot<K, L>>? = null

    /** Starts one current/retained publication frame and returns its immutable generation id. */
    fun beginFrame(): Int {
        frame += 1
        return frame
    }

    fun publish(
        frameId: Int,
        key: K,
        role: VirtualListPhysicalLayoutRole,
        layout: L,
    ) {
        if (frameId != frame) return
        val record = records.getOrPut(key) { Record() }
        when (role) {
            VirtualListPhysicalLayoutRole.CURRENT -> {
                if (record.current != layout) cachedSnapshots = null
                record.current = layout
                record.currentSeenFrame = frameId
            }
            VirtualListPhysicalLayoutRole.RETAINED -> {
                if (record.retained != layout) cachedSnapshots = null
                record.retained = layout
                record.retainedSeenFrame = frameId
            }
        }
    }

    /**
     * Closes a publication frame. A layout role which was not attached in this frame is cleared;
     * the logical holder record disappears only after neither current nor retained layout remains.
     */
    fun endFrame(frameId: Int) {
        if (frameId != frame) return
        val iterator = records.entries.iterator()
        while (iterator.hasNext()) {
            val record = iterator.next().value
            if (record.currentSeenFrame != frameId && record.current != null) {
                record.current = null
                cachedSnapshots = null
            }
            if (record.retainedSeenFrame != frameId && record.retained != null) {
                record.retained = null
                cachedSnapshots = null
            }
            if (record.current == null && record.retained == null) {
                iterator.remove()
                cachedSnapshots = null
            }
        }
    }

    fun currentFor(key: K): L? = records[key]?.current

    fun retainedFor(key: K): L? = records[key]?.retained

    fun hasBothLayouts(key: K): Boolean {
        val record = records[key] ?: return false
        return record.current != null && record.retained != null
    }

    val activeKeyCount: Int
        get() = records.size

    val dualLayoutKeyCount: Int
        get() = records.values.count { it.current != null && it.retained != null }

    /** Stable snapshot for the single physical renderer after CURRENT/RETAINED publication closes. */
    fun snapshots(): List<Snapshot<K, L>> = cachedSnapshots ?: records.map { (key, record) ->
        Snapshot(key = key, current = record.current, retained = record.retained)
    }.also { cachedSnapshots = it }

    fun clear() {
        records.clear()
        cachedSnapshots = null
    }
}
