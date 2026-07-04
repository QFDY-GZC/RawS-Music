package com.rawsmusic.core.ui.widget.powerlist

import androidx.compose.runtime.Stable

internal const val COMPOSE_SLOT_SOURCE = 0
internal const val COMPOSE_SLOT_TARGET = 1

@Stable
internal class ComposeSlotPool(
    capacity: Int = 4
) {
    private val window = ComposeSlotWindow(capacity.coerceAtLeast(1))

    fun beginFrame(firstVisibleIndex: Int, visibleCount: Int) {
        window.beginFrame(firstVisibleIndex, visibleCount)
    }

    fun setPosition(index: Int, key: Any, slot: Int, position: ComposeItemPosition) {
        window.setPosition(index, key, slot, position)
    }

    fun getPosition(index: Int, slot: Int): ComposeItemPosition? {
        return window.getPosition(index, slot)
    }

    fun getEntry(index: Int): ComposeSlotEntry? {
        return window.getEntry(index)
    }

    fun slotIdFor(index: Int): Int {
        return window.slotIdFor(index)
    }

    fun clear() {
        window.clear()
    }
}

@Stable
internal class ComposeSlotWindow(
    capacity: Int
) {
    private var firstVisibleIndex: Int = 0
    private var visibleCount: Int = 0
    private var ringStart: Int = 0
    private val entries = ArrayList<ComposeSlotEntry>(capacity).apply {
        repeat(capacity) { add(ComposeSlotEntry()) }
    }
    private var initialized = false

    fun beginFrame(firstVisibleIndex: Int, visibleCount: Int) {
        val oldFirst = this.firstVisibleIndex
        val newFirst = firstVisibleIndex.coerceAtLeast(0)
        val newCount = visibleCount.coerceAtLeast(0)
        ensureCapacity(newCount)
        this.firstVisibleIndex = newFirst
        this.visibleCount = newCount
        if (!initialized) {
            ringStart = 0
            initialized = true
        } else {
            val shift = newFirst - oldFirst
            if (shift != 0) {
                ringStart = floorMod(ringStart + shift, entries.size)
            }
        }
        entries.forEach { it.clearSlots() }
    }

    fun setPosition(index: Int, key: Any, slot: Int, position: ComposeItemPosition) {
        val entry = entryFor(index)
        entry.index = index
        entry.key = key
        entry.set(slot, position)
    }

    fun getPosition(index: Int, slot: Int): ComposeItemPosition? {
        return getEntry(index)?.get(slot)
    }

    fun getEntry(index: Int): ComposeSlotEntry? {
        if (visibleCount <= 0 || index < firstVisibleIndex || index >= firstVisibleIndex + visibleCount) return null
        return entries[entrySlot(index)]
    }

    fun slotIdFor(index: Int): Int {
        if (visibleCount <= 0 || index < firstVisibleIndex || index >= firstVisibleIndex + visibleCount) return -1
        return entrySlot(index)
    }

    fun clear() {
        entries.forEach {
            it.index = Int.MIN_VALUE
            it.key = null
            it.clearSlots()
        }
    }

    private fun entryFor(index: Int): ComposeSlotEntry {
        val slot = entrySlot(index)
        return entries[slot]
    }

    private fun entrySlot(index: Int): Int {
        if (visibleCount <= 0) return 0
        val offset = index - firstVisibleIndex
        return floorMod(ringStart + offset, entries.size)
    }

    private fun ensureCapacity(visibleCount: Int) {
        if (visibleCount <= entries.size) return
        repeat(visibleCount - entries.size) {
            entries += ComposeSlotEntry()
        }
    }
}

@Stable
internal class ComposeSlotEntry {
    var index: Int = Int.MIN_VALUE
        internal set

    var key: Any? = null
        internal set

    private var source: ComposeItemPosition? = null
    private var target: ComposeItemPosition? = null

    fun set(slot: Int, position: ComposeItemPosition) {
        when (slot) {
            COMPOSE_SLOT_SOURCE -> source = position
            COMPOSE_SLOT_TARGET -> target = position
        }
    }

    fun get(slot: Int): ComposeItemPosition? {
        return when (slot) {
            COMPOSE_SLOT_SOURCE -> source
            COMPOSE_SLOT_TARGET -> target
            else -> null
        }
    }

    fun clearSlots() {
        source = null
        target = null
    }
}

private fun floorMod(value: Int, mod: Int): Int {
    if (mod <= 0) return 0
    val r = value % mod
    return if (r < 0) r + mod else r
}
