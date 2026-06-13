package com.rawsmusic.ui.settings.powerlist

import android.view.View

/**
 * 1:1 equivalent to Poweramp's v0.java (com.maxmpz.widget.list.v0).
 *
 * Represents a list item holder with its view, position data, and visibility state.
 *
 * Field mapping to Poweramp v0.java:
 *   A     → view          (View)
 *   f4257 → viewType      (Int) - layout type / view pool key
 *   f4258 → position      (Int) - data source position
 *   B     → viewPoolPos   (Int) - position in view pool
 *   x     → isOutOfView   (Boolean) - true when recycled/offscreen
 *   f4259 → slot0         (ItemPosition?) - source position data
 *   X     → slot1         (ItemPosition?) - target position data
 *   f4260 → animState      (MutableList<Any>) - animation state list, cleared on out-of-view
 */
class ViewHolder(
    /** Initial out-of-view state (Poweramp's v0(boolean z)) */
    initialOutOfView: Boolean = false
) {
    /** The actual Android View for this item (Poweramp's A) */
    var view: View? = null

    /** Item index in the data source (Poweramp's f4258) */
    var position: Int = -1

    /** Layout type / view pool key (Poweramp's f4257) */
    var viewType: Int = -1

    /** Position in view pool (Poweramp's B) */
    var viewPoolPos: Int = -1

    /**
     * Whether this holder is out of view / recycled (Poweramp's x).
     * When true, view is INVISIBLE and position data is cleared.
     */
    var isOutOfView: Boolean = initialOutOfView

    /**
     * Whether this holder is currently recycled (not bound to a view).
     * Convenience for checking view == null. Used by ViewPool and PowerListView.
     */
    var isRecycled: Boolean = false

    /**
     * Current scene applied to this view's ConstraintLayout.
     * Used during transitions to track scene switching (apply target scene for measurement,
     * then revert to source scene). Not in Poweramp's v0.java — tracked per-holder for efficiency.
     */
    var currentScene: Int = -1

    /** Whether the target engine already measured this view (skip measureCell in b0) */
    var alreadyMeasuredForTarget: Boolean = false

    /**
     * Animation state list (Poweramp's f4260 = C0848(1, true)).
     * Growable list of animation state objects. Cleared when holder goes out of view.
     */
    val animState = mutableListOf<Any>()

    // --- Position data slots (Poweramp's f4259 = slot 0, X = slot 1) ---

    /** Slot 0: source position (Poweramp's f4259) */
    private var slot0: ItemPosition? = null

    /** Slot 1: target position (Poweramp's X) */
    private var slot1: ItemPosition? = null

    companion object {
        const val TYPE_NORMAL = 0
        const val TYPE_HEADER = -1
        const val TYPE_FOOTER = -2
        const val TYPE_RECYCLED = -4
        /** Poweramp C0874 special album/header holder position (v0.f4258 = -10). */
        const val POSITION_GRID_HEADER = -10
    }

    /**
     * Get or create position data for the given slot (Poweramp's B(int)).
     * Slot 0 = source position, Slot 1 = target position during transitions.
     */
    fun getOrCreatePosition(slot: Int): ItemPosition {
        return when (slot) {
            0 -> slot0 ?: ItemPosition().also { slot0 = it }
            1 -> slot1 ?: ItemPosition().also { slot1 = it }
            else -> ItemPosition()
        }
    }

    /**
     * Get existing position data without creating (Poweramp's m3021(int)).
     * Returns null if the slot has not been initialized.
     */
    fun getPositionData(slot: Int): ItemPosition? = when (slot) {
        0 -> slot0
        1 -> slot1
        else -> null
    }
    /**
     * Set position data for a slot.
     */
    fun setPositionData(slot: Int, data: ItemPosition) {
        when (slot) {
            0 -> slot0 = data
            1 -> slot1 = data
        }
    }

    /**
     * Invalidate both position slots (Poweramp's m3020()).
     * Called when holder is recycled or goes offscreen.
     */
    fun invalidatePositions() {
        slot0?.invalidate()
        slot1?.invalidate()
    }
    /**
     * Clear both position slots.
     * Called when fully recycling the holder.
     */
    fun clearPositionData() {
        slot0 = null
        slot1 = null
    }

    /**
     * Bind a view to this holder (Poweramp's A(view, type)).
     * Position is set separately by ViewPool (m2970/m2972).
     * Sets the view visible (unless isOutOfView).
     */
    fun bind(view: View, viewType: Int) {
        m3022() // Clean up previous view first (Poweramp calls m3022 first)
        this.view = view
        this.viewType = viewType
        if (!isOutOfView) {
            view.visibility = View.VISIBLE
        }
    }
    /**
     * Unbind and recycle the current view (Poweramp's m3022()).
     * Removes view from parent, clears position data, returns to view cache.
     */
    fun m3022() {
        val v = view ?: return
        val type = viewType
        invalidatePositions()
        val parent = v.parent as? android.view.ViewGroup
        parent?.removeViewInLayout(v)
        viewType = -1
        view = null
    }

    /**
     * Recycle this holder. Convenience method combining unbind() + isRecycled flag.
     * Used by LayoutState.recycleViewHolder() and ViewPool.put().
     */
    fun recycle() {
        m3022()
        isRecycled = true
        currentScene = -1
        alreadyMeasuredForTarget = false
    }

    /**
     * Set out-of-view state (Poweramp's m3023(boolean)).
     * When out of view: visibility = INVISIBLE, positions cleared, animation state cleared.
     */
    @JvmName("setOutOfViewFlag")
    fun setOutOfView(outOfView: Boolean) {
        m3023(outOfView)
    }
    /** Alias matching Poweramp's v0.m3023(boolean) */
    fun m3023(outOfView: Boolean) {
        if (isOutOfView == outOfView) return
        isOutOfView = outOfView
        val v = view
        if (v != null) {
            v.alpha = 1f
            v.scaleX = 1f
            v.scaleY = 1f
            v.rotation = 0f
            v.rotationX = 0f
            v.rotationY = 0f
            if (outOfView) {
                v.visibility = View.INVISIBLE
            } else {
                v.visibility = View.VISIBLE
            }
        }
        if (outOfView) {
            invalidatePositions()
            animState.clear() // Poweramp: f4260.clear()
            currentScene = -1
            alreadyMeasuredForTarget = false
            if (position >= 0) {
                position = Int.MIN_VALUE
            }
        }
    }

    override fun toString(): String {
        val hex = Integer.toHexString(hashCode())
        val typeStr = view?.let { "${it.javaClass.simpleName}@${Integer.toHexString(it.hashCode())}" } ?: "null"
        return "PowerListItem@$hex pos=$position poolPos=$viewPoolPos type=$typeStr isOutOfView=$isOutOfView slot0=$slot0 slot1=$slot1 view=$view"
    }
}
