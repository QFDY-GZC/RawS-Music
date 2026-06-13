package com.rawsmusic.ui.settings.powerlist

import android.graphics.Point
import android.graphics.Rect
import android.util.Log
import android.view.View

/**
 * Equivalent to Poweramp's u.java - layout state management.
 *
 * Shared-ViewPool architecture (matching Poweramp's u.A = d2):
 * - u.B → mainContainer (target engine during transition)
 * - u.f4251 → backupContainer (source engine during transition)
 * - u.A → SHARED ViewPool used by both containers
 * - Each ViewHolder has TWO ItemPosition slots, matching Poweramp's container slots
 * - Each engine writes to the slot assigned to its current container
 * - Rendering (m1209) reads both slots for interpolation
 */
class LayoutState(
    private val powerList: PowerListView
) {
    companion object {
        private const val TAG = "PowerList.LayoutState"
        const val STATE_IDLE = 0
        const val STATE_STARTED = 1
        const val STATE_IN_PROGRESS = 2
        const val STATE_DUAL_ENGINE_TRANSITION = 3
    }

    // Main engine (target during transition) — Poweramp's u.B engine
    private var mainEngine: LayoutEngine? = null
    // Backup engine (source during transition) — Poweramp's u.f4251 engine
    private var backupEngine: LayoutEngine? = null

    /**
     * Shared ViewPool — 1:1 with Poweramp's u.A = d2.
     * Both engines reference this SAME pool. LayoutState assigns each engine
     * the slot belonging to its current container.
     */
    private val _viewPool = ViewPool()

    /** Poweramp C0874 special album/header holder, separate from d2 visible item pool. */
    private var gridHeaderHolder: ViewHolder? = null

    var mainSlot: Int = 0
        private set

    var backupSlot: Int = 1
        private set

    var transitionState: Int = STATE_IDLE
        private set

    val viewPool: ViewPool get() = _viewPool
    var state: Int = STATE_IDLE
        private set

    /** Transition progress — Poweramp's u.f4256 */
    var transitionProgress: Float = 0f
        set(value) {
            field = if (value.isNaN()) 0f else value.coerceIn(0f, 1f)
        }

    private var backupScrollY: Int = 0

    fun setSourceEngine(engine: LayoutEngine, reset: Boolean = true) {
        (engine as? GridListEngine)?.positionSlot = mainSlot
        mainEngine = engine
        if (reset) engine.reset()
    }

    fun setMainEngine(engine: LayoutEngine) {
        (engine as? GridListEngine)?.positionSlot = mainSlot
        mainEngine = engine
    }

    fun getActiveEngine(): LayoutEngine? = mainEngine ?: backupEngine
    fun getBackupEngine(): LayoutEngine? = backupEngine
    fun getBackupViewPool(): ViewPool = _viewPool

    private fun setState(newState: Int) {
        state = newState
        _viewPool.isActive = (newState == STATE_STARTED)
    }

    fun setTransitionState(newState: Int) {
        transitionState = newState
        // Poweramp u.m3016(i): d2.x is true only for state == 1, not for state 3 transitions.
        _viewPool.isActive = (newState == STATE_STARTED)
    }

    /**
     * Refreshes the layout — 1:1 with Poweramp's u.m3017().
     *
     * Phase 1: Layout main engine → writes to slot 0 (main engine's own range)
     * Phase 2: Layout backup engine → writes to slot 1 (backup engine's own range, no new views)
     * Phase 3: Render — updateVisibleItems reads both slots, applies m1209()
     */
    fun refreshLayout(force: Boolean, notifyScroll: Boolean) {
        val itemCount = powerList.itemCount
        if (itemCount <= 0) {
            _viewPool.B()
            hideGridHeaderHolder()
            powerList.recalculateMaxScroll()
            return
        }

        val engine = getActiveEngine() ?: return

        // Phase 1: Layout main engine → writes to slot 0
        val mainGLE = engine as? GridListEngine
        mainGLE?.positionSlot = mainSlot
        mainGLE?.isBackupEngine = false
        mainGLE?.isTargetEngine = transitionState == STATE_DUAL_ENGINE_TRANSITION
        val mainFirst: Int
        val mainLast: Int
        if (mainGLE != null && mainGLE.firstVisibleRow >= 0 && mainGLE.lastVisibleRow >= 0 && mainGLE.actualRowHeight > 0) {
            val range = mainGLE.getVisibleItemRange(itemCount)
            mainFirst = range.first
            mainLast = range.second
        } else {
            mainFirst = powerList.firstVisiblePosition
            mainLast = powerList.lastVisiblePosition
        }
        if (mainFirst <= mainLast) {
            _viewPool.ensureRange(mainFirst, mainLast)
        }
        engine.performLayout(state, mainFirst, mainLast, itemCount, this)
        val mainRangeAfterLayout = (engine as? GridListEngine)?.getVisibleItemRange(itemCount)
            ?: Pair(mainFirst, mainLast)
        if (transitionState != STATE_DUAL_ENGINE_TRANSITION && mainRangeAfterLayout.first <= mainRangeAfterLayout.second) {
            _viewPool.setExactRange(mainRangeAfterLayout.first, mainRangeAfterLayout.second)
        }

        // Phase 2: Layout backup engine → writes to slot 1 (Poweramp: secondaryEngine.h())
        // Backup engine uses its OWN scroll offset and computes its OWN visible range.
        // getBackupViewHolder() does NOT create new views — only returns existing holders.
        var backupRangeAfterLayout: Pair<Int, Int>? = null
        if (transitionState == STATE_DUAL_ENGINE_TRANSITION && backupEngine != null && backupEngine != engine) {
            // Save and restore main engine scroll offset
            val mainScrollY = engine.getScrollOffset().y

            // Set backup engine to its saved scroll offset
            backupEngine?.setScrollOffset(0.0, backupScrollY.toDouble())

            // Backup engine computes its own visible range from its scroll position
            val backupGLE = backupEngine as? GridListEngine
            backupGLE?.positionSlot = backupSlot
            backupGLE?.isBackupEngine = true
            backupGLE?.isTargetEngine = false
            val (backupFirst, backupLast) = backupGLE?.getVisibleItemRange(itemCount)
                ?: Pair(0, 0)
            if (backupFirst <= backupLast) {
                _viewPool.ensureRange(backupFirst, backupLast)
            }
            backupEngine?.performLayout(state, backupFirst, backupLast, itemCount, this)
            backupRangeAfterLayout = backupGLE?.getVisibleItemRange(itemCount)
            backupGLE?.isBackupEngine = false

            // Restore main engine scroll offset
            engine.setScrollOffset(0.0, mainScrollY.toDouble())
        }

        // Phase 3: Render — layout ranges stay buffered so both engines can keep
        // slots alive, while active ranges are viewport-clipped. Poweramp's m1209()
        // only dual-interpolates rows present in both active containers; rows that
        // are only active in one container must go through j() for fade/scale.
        if (transitionState == STATE_DUAL_ENGINE_TRANSITION) {
            val backupRange = backupRangeAfterLayout ?: mainRangeAfterLayout
            val mainActiveRange = mainGLE?.getViewportItemRange(itemCount, extraRowsBefore = 1, extraRowsAfter = 0) ?: mainRangeAfterLayout
            val backupActiveRange = (backupEngine as? GridListEngine)?.getViewportItemRange(itemCount, extraRowsBefore = 1, extraRowsAfter = 0) ?: backupRange
            powerList.updateTransitionVisibleItems(
                mainRangeAfterLayout.first,
                mainRangeAfterLayout.second,
                backupRange.first,
                backupRange.second,
                mainActiveRange.first,
                mainActiveRange.second,
                backupActiveRange.first,
                backupActiveRange.second,
                mainRangeAfterLayout.first,
                mainRangeAfterLayout.second,
                backupRange.first,
                backupRange.second
            )
        } else {
            powerList.updateVisibleItems(mainRangeAfterLayout.first, mainRangeAfterLayout.second)
        }
        powerList.recalculateMaxScroll()
    }

    /**
     * Gets a ViewHolder from the SHARED pool.
     * Both engines use this. If holder exists, returns it. Otherwise creates new.
     */
    fun getViewHolder(position: Int): ViewHolder? {
        _viewPool.ensurePosition(position)
        val existing = _viewPool.m2970(position)
        if (existing != null && !existing.isOutOfView) {
            return existing
        }
        if (existing != null && existing.isOutOfView) {
            existing.setOutOfView(false)
            existing.position = position
            existing.viewPoolPos = position
            existing.view?.visibility = View.VISIBLE
            val recycledView = existing.view
            if (recycledView != null && recycledView.parent == null) {
                powerList.addView(recycledView)
            }
            if (recycledView != null) {
                bindHolderView(position, recycledView)
            }
            return existing
        }
        val view = powerList.createViewForPosition(position) ?: return null
        val holder = ViewHolder()
        holder.view = view
        holder.position = position
        _viewPool.putAt(position, holder)
        bindHolderView(position, view)
        return holder
    }

    private fun bindHolderView(position: Int, view: View) {
        if (position == ViewHolder.POSITION_GRID_HEADER) {
            powerList.bindGridHeaderView(view)
        } else {
            powerList.getDataProvider()?.bindView(position, view)
        }
    }

    /**
     * Gets backup ViewHolder from the SHARED pool.
     * Same as getViewHolder() — both engines share the same pool.
     * Backup engine writes to slot 1 on the same ViewHolder.
     */
    fun getBackupViewHolder(position: Int): ViewHolder? {
        return getViewHolder(position)
    }

    /** Create/get Poweramp C0874 special grid album/header holder (position -10). */
    fun getGridHeaderViewHolder(): ViewHolder? {
        val existing = gridHeaderHolder
        if (existing != null && existing.view != null) {
            if (existing.isOutOfView) existing.setOutOfView(false)
            existing.position = ViewHolder.POSITION_GRID_HEADER
            existing.viewPoolPos = ViewHolder.POSITION_GRID_HEADER
            return existing
        }
        val view = powerList.createGridHeaderView() ?: return null
        val holder = ViewHolder(initialOutOfView = false)
        holder.view = view
        holder.position = ViewHolder.POSITION_GRID_HEADER
        holder.viewPoolPos = ViewHolder.POSITION_GRID_HEADER
        gridHeaderHolder = holder
        bindHolderView(ViewHolder.POSITION_GRID_HEADER, view)
        return holder
    }

    fun getGridHeaderHolderIfPresent(): ViewHolder? = gridHeaderHolder?.takeIf { !it.isOutOfView }

    fun hideGridHeaderHolder() {
        gridHeaderHolder?.let { holder ->
            holder.setOutOfView(true)
            holder.invalidatePositions()
            holder.view?.visibility = View.INVISIBLE
        }
    }

    /** Resolve the current holder for a clicked view, matching Poweramp's view→holder lookup. */
    fun findViewHolderForView(clickedView: View): ViewHolder? {
        var current: View? = clickedView
        while (current != null) {
            val header = gridHeaderHolder
            if (header != null && !header.isOutOfView && header.view === current) {
                return header
            }
            for (holder in allActiveHolders()) {
                if (!holder.isOutOfView && holder.view === current) {
                    return holder
                }
            }
            current = current.parent as? View
        }
        return null
    }

    private fun allActiveHolders(): List<ViewHolder> {
        val holders = _viewPool.getAll().toMutableList()
        gridHeaderHolder?.takeIf { !it.isOutOfView }?.let { holders.add(it) }
        return holders
    }

    fun recycleViewHolder(holder: ViewHolder) {
        holder.setOutOfView(true)
    }

    fun recycleBackupViewHolder(holder: ViewHolder) {
        holder.setOutOfView(true)
    }

    fun updateDimensions(width: Int, height: Int, padding: Rect) {
        mainEngine?.setDimensions(width, height, padding.left, padding.top, padding.right, padding.bottom)
        backupEngine?.setDimensions(width, height, padding.left, padding.top, padding.right, padding.bottom)
    }

    fun scrollBy(dx: Double, dy: Double): Boolean {
        val mainChanged = mainEngine?.scrollBy(dx, dy) ?: false
        backupEngine?.scrollBy(dx, dy)
        return mainChanged
    }

    fun setScrollOffset(x: Double, y: Double) {
        mainEngine?.setScrollOffset(x, y)
        if (transitionState != STATE_DUAL_ENGINE_TRANSITION) {
            backupEngine?.setScrollOffset(x, y)
        }
    }

    fun getScrollOffset(): Point = mainEngine?.getScrollOffset() ?: Point(0, 0)

    /**
     * Swap containers — Poweramp's a2.B().
     * Old main → backup, main = null (caller sets new target).
     * Shared pool stays.
     */
    fun swapContainers() {
        val tempEngine = mainEngine
        copySlotForContainerSwap(fromSlot = mainSlot, toSlot = backupSlot)
        backupEngine = tempEngine
        backupScrollY = tempEngine?.getScrollOffset()?.y ?: 0
        val oldMainSlot = mainSlot
        mainSlot = backupSlot
        backupSlot = oldMainSlot
        (backupEngine as? GridListEngine)?.positionSlot = backupSlot
        mainEngine = null
    }

    private fun copySlotForContainerSwap(fromSlot: Int, toSlot: Int) {
        if (fromSlot == toSlot) return
        for (holder in allActiveHolders()) {
            val source = holder.getPositionData(fromSlot)
            val target = holder.getOrCreatePosition(toSlot)
            if (source == null || source.isTranslationZero()) {
                target.invalidate()
            } else {
                target.copyFrom(source)
            }
        }
    }

    fun confirmTransition() {
        copySlotForContainerSwap(fromSlot = mainSlot, toSlot = 0)
        // Poweramp m2952(z=true): swap engines, clean up old state
        // Slot 1 (source) data no longer needed — invalidate it
        for (holder in allActiveHolders()) {
            holder.getPositionData(1)?.invalidate()
        }
        mainSlot = 0
        backupSlot = 1
        (mainEngine as? GridListEngine)?.positionSlot = mainSlot
        backupEngine = null
        transitionProgress = 0f
        setTransitionState(STATE_IDLE)
    }

    fun cancelTransition() {
        // Poweramp m2952(z=false): revert to source engine
        mainEngine = backupEngine
        backupEngine = null
        copySlotForContainerSwap(fromSlot = backupSlot, toSlot = 0)
        for (holder in allActiveHolders()) {
            holder.getPositionData(1)?.invalidate()
        }
        mainSlot = 0
        backupSlot = 1
        (mainEngine as? GridListEngine)?.positionSlot = mainSlot
        transitionProgress = 0f
        setTransitionState(STATE_IDLE)
    }

    fun reset() {
        setState(STATE_IDLE)
        setTransitionState(STATE_IDLE)
        transitionProgress = 0f
        mainSlot = 0
        backupSlot = 1
        (mainEngine as? GridListEngine)?.positionSlot = mainSlot
        (backupEngine as? GridListEngine)?.positionSlot = backupSlot
        mainEngine?.reset()
        backupEngine?.reset()
        hideGridHeaderHolder()
    }
}
