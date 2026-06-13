package com.rawsmusic.ui.settings.powerlist

/**
 * 1:1 equivalent to Poweramp's d2.java (com.maxmpz.widget.list.d2).
 *
 * Visible-range ring buffer. The pool owns a contiguous logical position range;
 * reads/writes through m2970()/m2972() are valid only inside that range.
 * Range growth/shrink is performed by A(first,last), matching Poweramp's u.y()
 * -> d2.A(...) flow. Holders that leave the visible range are marked out-of-view
 * instead of being removed from the parent.
 *
 * Poweramp field mapping:
 *   B     → slots       (ViewHolder[]) - backing array
 *   A     → headIndex   (Int) - ring buffer head index
 *   X     → count       (Int) - number of visible slots
 *   f4201 → firstPos    (Int) - logical position of head slot
 *   f4202 → initSentinel (Int) - initialization sentinel (-1 = not init)
 *   f4200 → sentinel    (ViewHolder) - placeholder for out-of-range positions
 *   f4199 → CAPACITY_HINT = 4 (constant)
 *   x     → isActive    (Boolean)
 *   y     → EMPTY_SLOTS (static empty array)
 */
class ViewPool {
    companion object {
        private const val CAPACITY_HINT = 4
        private val EMPTY_SLOTS = arrayOfNulls<ViewHolder>(0)
    }

    /** Backing array of ViewHolder slots (Poweramp's B) */
    private var slots: Array<ViewHolder?> = EMPTY_SLOTS

    /** Head index in the ring buffer (Poweramp's A) */
    private var headIndex: Int = 0

    /** Number of visible slots in the ring (Poweramp's X) */
    var count: Int = 0
        private set

    /** Logical position of the head slot (Poweramp's f4201) */
    private var firstPos: Int = 0

    /** Initialization sentinel (Poweramp's f4202, -1 = not initialized) */
    private var initSentinel: Int = -1

    /** Sentinel slot for out-of-range positions (Poweramp's f4200 = new v0(true)) */
    private val sentinel = ViewHolder(initialOutOfView = true)

    /** Whether the pool is actively transitioning (Poweramp's x) */
    var isActive: Boolean = false

    private val lastPos: Int get() = firstPos + count - 1

    /** Convert logical position to ring index (Poweramp's m2970/m2972 index math). */
    private fun posToIndex(logicalPos: Int): Int {
        var idx = logicalPos - (firstPos - headIndex)
        if (idx >= slots.size) idx -= slots.size
        return idx
    }

    /**
     * Poweramp d2.A(first,last): set/resize the visible logical range.
     * The range is inclusive, matching u.y()'s d.f4195..d.A bounds.
     */
    fun A(first: Int, last: Int) {
        if (last < first) {
            B()
            slots = EMPTY_SLOTS
            headIndex = 0
            count = 0
            firstPos = 0
            initSentinel = -1
            return
        }

        val newCount = last - first + 1
        val preserved = HashMap<Int, ViewHolder>(count)
        val reusable = ArrayDeque<ViewHolder>()
        if (initSentinel >= 0 && count > 0 && slots.isNotEmpty()) {
            for (i in 0 until count) {
                val idx = (headIndex + i) % slots.size
                val holder = slots[idx] ?: continue
                val pos = firstPos + i
                if (pos in first..last) {
                    preserved[pos] = holder
                } else {
                    holder.m3023(true)
                    reusable.addLast(holder)
                }
            }
        }

        val newSize = maxOf(newCount, CAPACITY_HINT)
        val newSlots = arrayOfNulls<ViewHolder?>(newSize)
        for ((pos, holder) in preserved) {
            val idx = pos - first
            newSlots[idx] = holder
            holder.viewPoolPos = pos
        }
        for (pos in first..last) {
            val idx = pos - first
            if (newSlots[idx] == null && reusable.isNotEmpty()) {
                val holder = reusable.removeFirst()
                holder.viewPoolPos = pos
                newSlots[idx] = holder
            }
        }

        slots = newSlots
        headIndex = 0
        count = newCount
        firstPos = first
        initSentinel = 0
    }

    /** Poweramp d2.B(): mark the current ring range out-of-view. */
    fun B() {
        if (count <= 0 || slots.isEmpty()) return
        val end = (headIndex + count - 1) % slots.size
        m2969(headIndex, end)
    }

    /** Poweramp d2.m2969(startIndex,endIndex): mark a ring index range out-of-view. */
    fun m2969(startIndex: Int, endIndex: Int) {
        if (slots.isEmpty()) return
        if (endIndex < startIndex) {
            if (endIndex >= 0) {
                for (i in 0..endIndex) slots[i]?.m3023(true)
            }
            for (i in startIndex until slots.size) slots[i]?.m3023(true)
            return
        }
        if (startIndex > endIndex) return
        for (i in startIndex..endIndex) slots[i]?.m3023(true)
    }

    /** Poweramp d2.m2970(position): strict get inside visible range. */
    fun m2970(position: Int): ViewHolder? {
        if (initSentinel < 0 || count == 0 || position < firstPos || position >= firstPos + count) {
            throw RuntimeException("pos=$position is out of visible posses [$firstPos-${firstPos + count})")
        }
        val idx = posToIndex(position)
        val holder = slots[idx]
        if (holder != null) holder.viewPoolPos = position
        return holder
    }

    /** Safe nullable lookup for legacy call sites that have not been converted to strict m2970(). */
    operator fun get(position: Int): ViewHolder? {
        if (initSentinel < 0 || count == 0 || position < firstPos || position >= firstPos + count) return null
        return m2970(position)
    }

    /** Poweramp d2.m2972(position, holder): strict put inside visible range. */
    fun m2972(position: Int, holder: ViewHolder?) {
        if (initSentinel < 0) throw RuntimeException("non inited ring")
        if (position < firstPos || position >= firstPos + count) {
            throw RuntimeException("pos=$position is out of visible posses")
        }
        val idx = posToIndex(position)
        if (holder != null) holder.viewPoolPos = position
        slots[idx] = holder
    }

    /** Poweramp d2.m2971(from,to): move/swap visible slots, using sentinel for out-of-range. */
    fun m2971(from: Int, to: Int) {
        val fromInRange = initSentinel >= 0 && from in firstPos until (firstPos + count)
        val toInRange = initSentinel >= 0 && to in firstPos until (firstPos + count)
        val fromHolder = if (fromInRange) m2970(from) else sentinel
        val toHolder = if (toInRange) m2970(to) else sentinel

        if (toHolder === sentinel && fromHolder !== sentinel) {
            fromHolder?.m3023(true)
        } else if (fromHolder !== sentinel) {
            m2972(from, toHolder)
        }

        if (fromHolder === sentinel && toHolder !== sentinel) {
            toHolder?.m3023(true)
        } else if (toHolder !== sentinel) {
            m2972(to, fromHolder)
        }
    }

    /** Compatibility wrapper: expand via A() first, then use strict m2972(). */
    fun putAt(position: Int, holder: ViewHolder) {
        if (initSentinel < 0 || count == 0) {
            A(position, position)
        } else if (position < firstPos || position > lastPos) {
            A(minOf(firstPos, position), maxOf(lastPos, position))
        }
        m2972(position, holder)
    }

    /** Ensure the current range contains [first,last]. */
    fun ensureRange(first: Int, last: Int) {
        if (last < first) return
        if (initSentinel < 0 || count == 0) {
            A(first, last)
        } else if (first < firstPos || last > lastPos) {
            A(minOf(firstPos, first), maxOf(lastPos, last))
        }
    }

    /** Poweramp d2.A(first,last): replace the visible logical range instead of growing forever. */
    fun setExactRange(first: Int, last: Int) {
        if (initSentinel >= 0 && count > 0 && first == firstPos && last == lastPos) return
        A(first, last)
    }

    /** Ensure the current range contains a single position. */
    fun ensurePosition(position: Int) = ensureRange(position, position)

    /** Active holders, matching call sites that render only non-out-of-view slots. */
    fun getAll(): List<ViewHolder> {
        val result = mutableListOf<ViewHolder>()
        if (count <= 0 || slots.isEmpty()) return result
        for (i in 0 until count) {
            val idx = (headIndex + i) % slots.size
            val holder = slots[idx]
            if (holder != null && !holder.isOutOfView) result.add(holder)
        }
        return result
    }

    /** Raw backing slots for code paths that need Poweramp-style B array iteration. */
    fun rawSlots(): Array<ViewHolder?> = slots

    fun clear() {
        B()
        slots = EMPTY_SLOTS
        headIndex = 0
        count = 0
        firstPos = 0
        initSentinel = -1
    }

    val size: Int get() = count
}
