package com.rawsmusic.ui.settings.powerlist

import android.graphics.Rect
import android.view.View

/**
 * Equivalent to Poweramp's AbstractC0875.java - abstract grid/list layout engine base.
 * 
 * This class provides the common functionality for both single-column (list) and
 * multi-column (grid) layout engines. It manages:
 * - Column count and column width calculations
 * - Row height calculations and item-to-row mapping
 * - Vertical scrolling with fill-top/fill-bottom patterns
 * - View measurement and positioning for grid cells
 * 
 * Key field from Poweramp: f4264 = column count (cols)
 */
abstract class GridListEngine(
    /** Number of columns (1 for list, 2+ for grid) */
    val cols: Int = 1,
    /** Row height in pixels (0 = measure from view) */
    val rowHeight: Int = 0,
    /** Density scale factor */
    val density: Float = 1.0f
) : LayoutEngine() {
    
    // Validation matching Poweramp's constructor
    init {
        if (cols < 1) throw IllegalArgumentException("Bad cols=$cols")
    }
    
    /** Column width in pixels (calculated during layout) */
    var colWidth: Int = 0
        protected set
    
    /** Actual row height (may be measured from view if rowHeight == 0) */
    var actualRowHeight: Int = 0
        protected set

    /** When true, performLayout() won't reset actualRowHeight to 0. Used during zoom animation. */
    var preserveRowHeightForZoom: Boolean = false

    /** Extra label area below square grid cover. Raw grid mode places title/line2 below artwork. */
    protected fun gridLabelHeightPx(): Int {
        return if (cols > 1) (density * 46f).toInt() else 0
    }

    protected fun rowStridePx(): Int {
        return actualRowHeight + rowSpacing + gridLabelHeightPx()
    }

    /** Current zoom params set by PowerListView before performLayout().
     *  Used by ListEngine.positionItem() to apply zoom params to views BEFORE measureCell(),
     *  ensuring recycled views are measured with correct cover size/margins. */
    var activeZoomParams: ListZoomParams? = null

    /** Directly set actualRowHeight for zoom transitions. */
    fun setZoomRowHeight(height: Int) {
        actualRowHeight = height
    }
    
    /** Number of rows visible in the viewport */
    var visibleRows: Int = 0
        protected set
    
    /** First visible row index */
    var firstVisibleRow: Int = 0
        protected set
    
    /** Last visible row index */
    var lastVisibleRow: Int = 0
        protected set
    
    /** Total number of rows in the dataset */
    var totalRows: Int = 0
        protected set
    
    /** Vertical spacing between rows */
    var rowSpacing: Int = 0
    
    /** Horizontal spacing between columns */
    var colSpacing: Int = 0
    
    /** Scroll position tracking for fill methods */
    protected var scrollY: Int = 0

    /** Previous scrollY for computing scroll delta (Poweramp's v1 in h() edge fade) */
    protected var prevScrollY: Int = 0

    /** Scroll delta: change in scrollY since last layout (Poweramp's v1) */
    protected var scrollDelta: Int = 0
    
    /** Temp rect for calculations */
    protected val cellRect = Rect()
    
    /** Current zoom scale factor applied during zoom transitions */
    var zoomScale: Float = 1.0f
        protected set
    
    /** Current zoom progress (0.0-1.0) stored for use in performLayout */
    var currentZoomProgress: Float = 0f
        protected set
    
    // ==================== Transition Fields ====================
    
    /**
     * Transition scale factor.
     * During snap transition, set to 1.0f - progress.
     * Both engines get the same value. Used in computeTransitionTransform() as: finalScale = interpolatedScale * transitionScaleFactor.
     */
    var transitionScaleFactor: Float = 1.0f
    
    /**
     * Alpha start value for transition fade.
     * For zoom-out source: 0.55f, for zoom-in source: 1.0f.
     * Used in computeTransitionTransform(): alpha = alphaStartValue * (1 - progress)
     */
    var alphaStartValue: Float = 1.0f
    
    /**
     * Y-axis scale base value.
     * Always 1.0f for list engine. Used in computeTransitionTransform(): scaleY = transitionScaleFactor * scaleBaseY.
     */
    var scaleBaseY: Float = 1.0f
    
    /**
     * Transition mode.
     * MODE_SLIDE = 1, MODE_RECT_SCALE = 2, MODE_SCALE = 3, MODE_ZOOM = 4, MODE_TRANSLATE = 5
     */
    var transitionMode: Int = MODE_SLIDE
    
    /**
     * Zoom center X for MODE_ZOOM.
     * In content coordinates (with padding, no scroll offset).
     */
    var zoomCenterX: Float = 0f
    
    /**
     * Zoom center Y for MODE_ZOOM.
     * In content coordinates (with padding, no scroll offset).
     */
    var zoomCenterY: Float = 0f
    
    /**
     * Zoom scale base for MODE_ZOOM.
     * zoom-in source: 1.5, zoom-out source: 0.5
     * zoom-in target: 0.5, zoom-out target: 1.5
     */
    var zoomScaleBase: Float = 1.5f
    
    /**
     * Zoom level counter. Reset to 0 in resetTransition().
     */
    var zoomLevelCounter: Int = 0
    
    /**
     * Container slot used by this engine. Poweramp stores the slot id on the
     * container (androidx.recyclerview.widget.O.B), so the engine writes to the
     * slot assigned by LayoutState instead of assuming main=0/backup=1.
     */
    var positionSlot: Int = 0

    /**
     * Whether this engine is the backup (source) engine in a dual-engine transition.
     */
    var isBackupEngine: Boolean = false
    
    /**
     * Set by performLayout() when in dual-engine transition state.
     * Used by positionItem() to call j() and update alpha/scaleY in ItemPosition.
     */
    var inDualEngineTransition: Boolean = false
    
    /**
     * Current transition progress (0.0-1.0) for j() in positionItem().
     */
    var currentTransitionProgress: Float = 0f
    
    /**
     * Whether this engine is the target (main) engine in the dual-engine transition.
     */
    var isTargetEngine: Boolean = false
    
    companion object {
        const val MODE_SLIDE = 1
        const val MODE_RECT_SCALE = 2
        const val MODE_SCALE = 3
        const val MODE_ZOOM = 4
        const val MODE_TRANSLATE = 5
    }
    
    /**
     * Converts an item index to its row position.
     * @param position Item index
     * @return Row index (0-based)
     */
    fun positionToRow(position: Int): Int {
        return if (cols <= 1) position else position / cols
    }
    
    /**
     * Converts a row index to the first item index in that row.
     * @param row Row index (0-based)
     * @return First item index in the row
     */
    fun rowToPosition(row: Int): Int {
        return row * cols
    }
    
    /**
     * Gets the column index for a given item position.
     * @param position Item index
     * @return Column index (0-based)
     */
    fun positionToCol(position: Int): Int {
        return if (cols <= 1) 0 else position % cols
    }
    
    /**
     * Calculates column width based on available width and column count.
     * This is the core calculation from Poweramp's c2.java line 186:
     * int i7 = ((iWidth - rect.left) - rect.right) / this.f4264
     */
    protected fun calculateColumnWidth() {
        val availableWidth = getAvailableWidth()
        colWidth = if (cols <= 1) availableWidth else availableWidth / cols
    }
    
    /**
     * Calculates the total height of all content.
     * @param itemCount Total number of items
     * @return Total content height in pixels
     */
    fun calculateTotalHeight(itemCount: Int): Int {
        if (itemCount <= 0) return 0
        val rows = if (cols <= 1) itemCount else (itemCount + cols - 1) / cols
        val itemStride = rowStridePx()
        return rows * itemStride - rowSpacing
    }
    
    /**
     * Gets the cell bounds for a specific item position.
     * @param position Item index
     * @param outRect Rect to receive the bounds
     * @param includeScrollOffset Whether to subtract scroll offset
     */
    fun getCellBounds(position: Int, outRect: Rect, includeScrollOffset: Boolean = true) {
        val row = positionToRow(position)
        val col = positionToCol(position)
        
        val cellLeft = padding.left + col * colWidth
        val cellTop = padding.top + row * rowStridePx()
        
        outRect.set(
            cellLeft,
            cellTop,
            cellLeft + colWidth,
            cellTop + actualRowHeight + gridLabelHeightPx()
        )
        
        if (includeScrollOffset) {
            outRect.offset(-scrollOffset.x, -scrollOffset.y)
        }
    }
    
    /**
     * Measures a view for grid cell placement.
     * @param view The view to measure
     * @param position Item position data to update
     */
    protected fun measureCell(view: View, position: ItemPosition) {
        val widthSpec = View.MeasureSpec.makeMeasureSpec(colWidth, View.MeasureSpec.EXACTLY)
        
        // Use UNSPECIFIED to let wrap_content views determine their own height
        // This ensures item_song.xml's minHeight is respected
        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        view.measure(widthSpec, heightSpec)
        
        // Update actual row height to match the measured height.
        // During zoom animation (preserveRowHeightForZoom), skip this update
        // to prevent measured height variance from overriding the pre-set zoom row height.
        if (view.measuredHeight > 0 && !preserveRowHeightForZoom) {
            actualRowHeight = maxOf(actualRowHeight, view.measuredHeight)
        }
    }
    
    /**
     * Positions a measured view in its grid cell.
     * @param view The view to position
     * @param position Item position data to update
     * @param col Column index
     * @param row Row index
     * @param mode Layout mode (0=source, 1=target)
     */
    protected fun positionCell(view: View, position: ItemPosition, col: Int, row: Int, sceneId: Int = 0) {
        val left = padding.left + col * colWidth
        val top = padding.top + row * rowStridePx()

        position.set(left, top, left + colWidth, top + actualRowHeight + gridLabelHeightPx())
        position.setTransform(1.0f, 1.0f, sceneId)
    }
    
    /**
     * Abstract method for item positioning - subclasses implement specific logic.
     * @param holder ViewHolder to position
     * @param view The actual View
     * @param position Item position data
     * @param sceneId Scene ID for this position
     */
    abstract fun positionItem(holder: ViewHolder, view: View, position: ItemPosition, sceneId: Int)
    
    /**
     * Core layout method - positions all visible items.
     * This implements the fill-top/fill-bottom pattern from Poweramp.
     * 
     * During transitions, this computes interpolated positions via j().
     */
    override fun performLayout(
        layoutStatus: Int,
        firstVisible: Int,
        lastVisible: Int,
        itemCount: Int,
        stateManager: LayoutState
    ) {
        if (itemCount <= 0) return

        // Set transition progress for j() — Poweramp's r.t(f, boolean) equivalent.
        if (stateManager.transitionState == LayoutState.STATE_DUAL_ENGINE_TRANSITION) {
            currentTransitionProgress = if (isBackupEngine) (1f - stateManager.transitionProgress) else stateManager.transitionProgress
        } else {
            currentTransitionProgress = 0f
        }

        // Save previous row height for initial visible row calculation.
        // Reset actualRowHeight so it gets re-measured from actual views.
        // This is critical when switching layouts (e.g., grid→list) because
        // measureCell() uses maxOf() which would keep the old (larger) height.
        // During zoom animation, skip reset to avoid jitter from re-measurement variance.
        val prevRowHeight = actualRowHeight
        if (!preserveRowHeightForZoom) {
            actualRowHeight = 0
        }
        // Don't reset preserveRowHeightForZoom here — defer to after layout loop
        // so measureCell() knows not to override the zoom row height.
        
        // Calculate dimensions
        calculateColumnWidth()
        totalRows = if (cols <= 1) itemCount else (itemCount + cols - 1) / cols
        
        // Update scroll position
        scrollY = scrollOffset.y
        
        // Calculate visible rows using previous row height for scroll position
        val viewportHeight = getAvailableHeight()
        firstVisibleRow = if (prevRowHeight > 0) {
            Math.max(0, scrollY / (prevRowHeight + rowSpacing))
        } else {
            0
        }
        
        visibleRows = if (prevRowHeight > 0) {
            (viewportHeight + prevRowHeight + rowSpacing - 1) / (prevRowHeight + rowSpacing) + 4
        } else {
            10 // fallback
        }
        
        lastVisibleRow = Math.min(totalRows - 1, firstVisibleRow + visibleRows - 1)
        
        // Layout visible items
        for (row in firstVisibleRow..lastVisibleRow) {
            for (col in 0 until cols) {
                val position = row * cols + col
                if (position >= itemCount) break
                
                val holder = if (isBackupEngine) {
                    stateManager.getBackupViewHolder(position) ?: continue
                } else {
                    stateManager.getViewHolder(position) ?: continue
                }
                val view = holder.view ?: continue
                
                val slotIndex = positionSlot
                
                val itemPos = holder.getPositionData(slotIndex) ?: ItemPosition().also { 
                    holder.setPositionData(slotIndex, it) 
                }
                
                // Compute base position
                positionItem(holder, view, itemPos, slotIndex)
                

            }
        }
        
        // Reset AFTER layout loop so measureCell() respects the flag during b0().
        preserveRowHeightForZoom = false
    }
    
    /**
     * Sets scroll offset and triggers layout update.
     * This is the main scroll method called by the PowerList.
     */
    fun scrollTo(y: Int) {
        val oldY = scrollOffset.y
        scrollOffset.set(scrollOffset.x, y)
        scrollOffsetF.y = y.toDouble()
        scrollY = y
        
        if (oldY != y) {
            // Notify scroll change
            onScrollChanged(y - oldY)
        }
    }
    
    /**
     * Called when scroll position changes.
     * Subclasses can override to update visible items.
     */
    protected open fun onScrollChanged(dy: Int) {
        // Default implementation does nothing
    }
    
    /**
     * Gets the maximum scroll position.
     * @param itemCount Total number of items
     * @return Maximum scroll Y
     */
    fun getMaxScrollY(itemCount: Int): Int {
        val totalHeight = calculateTotalHeight(itemCount)
        val viewportHeight = getAvailableHeight()
        return Math.max(0, totalHeight - viewportHeight)
    }
    
    /**
     * Gets the scroll offset for a specific item.
     * @param position Item index
     * @return Scroll Y position for the top of that item's row
     */
    fun getItemScrollOffset(position: Int): Int {
        val row = positionToRow(position)
        return getRowScrollOffset(row)
    }
    
    /**
     * Gets the actual visible item range after performLayout().
     * @param itemCount Total number of items
     * @return Pair of (firstVisiblePosition, lastVisiblePosition)
     */
    fun getVisibleItemRange(itemCount: Int): Pair<Int, Int> {
        if (itemCount <= 0) return Pair(0, 0)
        val first = rowToPosition(firstVisibleRow).coerceIn(0, itemCount - 1)
        val last = (rowToPosition(lastVisibleRow) + cols - 1).coerceIn(0, itemCount - 1)
        return Pair(first, last)
    }

    /**
     * Visible range clipped to the viewport, without the extra layout buffer rows.
     * Layout still uses buffered rows for stability; transition rendering uses this
     * stricter range so source-only/target-only edge items can fade like Poweramp.
     */
    fun getViewportItemRange(
        itemCount: Int,
        extraRows: Int = 0,
        extraRowsBefore: Int = extraRows,
        extraRowsAfter: Int = extraRows
    ): Pair<Int, Int> {
        if (itemCount <= 0) return Pair(0, 0)
        val rowStep = rowStridePx()
        if (rowStep <= 0) return getVisibleItemRange(itemCount)

        val viewportHeight = getAvailableHeight().coerceAtLeast(0)
        val baseFirstRow = (scrollY / rowStep).coerceAtLeast(0)
        val firstRow = (baseFirstRow - extraRowsBefore).coerceAtLeast(0)
        val viewportBottom = (scrollY + viewportHeight - 1).coerceAtLeast(scrollY)
        val baseLastRow = (viewportBottom / rowStep).coerceAtLeast(baseFirstRow)
        val lastRow = (baseLastRow + extraRowsAfter).coerceAtMost(totalRows - 1)
        val first = rowToPosition(firstRow).coerceIn(0, itemCount - 1)
        val last = (rowToPosition(lastRow) + cols - 1).coerceIn(0, itemCount - 1)
        return Pair(first, last)
    }
    
    /**
     * Checks if a row is visible in the viewport.
     * @param row Row index
     * @return true if the row is visible
     */
    fun isRowVisible(row: Int): Boolean {
        return row in firstVisibleRow..lastVisibleRow
    }
    
    /**
     * Gets the scroll offset for a specific row.
     * @param row Row index
     * @return Scroll Y position for the top of that row
     */
    fun getRowScrollOffset(row: Int): Int {
        return row * rowStridePx()
    }

    /**
     * Poweramp AbstractC0875.a(position, zoomIn, itemCount).
     *
     * Column zooms do not preserve the source top-row offset. The target engine
     * chooses a scroll offset from its own row geometry so the destination range
     * settles in the same place it animated toward.
     */
    fun computeZoomAnchorScrollY(position: Int, zoomIn: Boolean, itemCount: Int): Int {
        if (itemCount <= 0) return 0

        val clampedPosition = position.coerceIn(0, itemCount - 1)
        if (clampedPosition < cols) return 0

        val viewportHeight = getAvailableHeight().coerceAtLeast(0)
        val viewportCenter = viewportHeight / 2
        val lastPosition = itemCount - 1
        val rowStride = rowStridePx().coerceAtLeast(1)
        val itemHeight = (actualRowHeight + gridLabelHeightPx()).coerceAtLeast(1)

        val anchorTop = getRowScrollOffset(positionToRow(clampedPosition))
        val lastTop = getRowScrollOffset(positionToRow(lastPosition))
        val nextRowTop = getRowScrollOffset(positionToRow((clampedPosition + cols).coerceAtMost(lastPosition)))
        val rowDistance = nextRowTop - anchorTop
        val contentBottom = lastTop + itemHeight
        val visibleSpan = contentBottom - anchorTop

        val desiredAnchorViewportY = if (zoomIn) {
            val centeredBottom = visibleSpan - rowDistance / 2
            if (centeredBottom < viewportCenter) {
                val viewportBottom = viewportHeight
                if (contentBottom <= viewportBottom - itemHeight) {
                    lastTop
                } else {
                    viewportBottom - visibleSpan
                }
            } else {
                viewportCenter - rowDistance / 2
            }
        } else {
            if (clampedPosition > 0 && visibleSpan <= viewportHeight) {
                viewportHeight - visibleSpan
            } else {
                0
            }
        }

        return (anchorTop - desiredAnchorViewportY).coerceIn(0, getMaxScrollY(itemCount))
    }
    
    /**
     * Sets up the transition mode for a pinch gesture.
     *
     * @param isZoomIn Whether this is a zoom-in gesture
     * @param scrollX Current scroll X offset (unused in list mode)
     * @param scrollY Current scroll Y offset (unused in list mode)
     */
    fun setupPinchTransition(isZoomIn: Boolean, scrollX: Int = 0, scrollY: Int = 0) {
        val scale = if (isZoomIn) 0.5f else 1.5f
        val cx = padding.left + (getAvailableWidth() / 2.0f)
        val cy = padding.top + (getAvailableHeight() / 2.0f)
        setZoomModeParams(cx, cy, scale)
    }
    
    /**
     * Set MODE_ZOOM transition parameters.
     * Sets transitionMode=MODE_ZOOM, zoomCenterX, zoomCenterY, zoomScaleBase.
     *
     * @param cx Center X in content coordinates
     * @param cy Center Y in content coordinates
     * @param scaleBase Scale base (0.5 for zoom-in, 1.5 for zoom-out)
     */
    fun setZoomModeParams(cx: Float, cy: Float, scaleBase: Float) {
        transitionMode = MODE_ZOOM
        zoomCenterX = cx
        zoomCenterY = cy
        zoomScaleBase = scaleBase
    }
    
    /**
     * Set transition mode to MODE_SCALE.
     */
    fun setModeScale() {
        transitionMode = MODE_SCALE
    }
    
    /**
     * Set up transition for zoom snap.
     * This is the entry point called from startSnapTransition() to set up each engine's transition mode.
     *
     * For source engine: setupTransitionMode(0, !isZoomIn, 0) → zoomScaleBase = isZoomIn ? 1.5 : 0.5
     * For target engine: setupTransitionMode(0, isZoomIn, 0) → zoomScaleBase = isZoomIn ? 0.5 : 1.5
     *
     * @param offset Offset (always 0 for our use)
     * @param isZoomIn Whether the target level is zooming in
     * @param offset2 Offset (always 0 for our use)
     */
    fun setupTransitionMode(offset: Int, isZoomIn: Boolean, offset2: Int) {
        val scale = if (isZoomIn) 0.5f else 1.5f
        val cx = bounds.width() / 2.0f + offset
        val cy = bounds.height() / 2.0f + offset2
        setZoomModeParams(cx, cy, scale)
        // Sync scaleBaseY so computeTransitionTransform() uses correct zoom scale
        // (Poweramp's c2.j() reads s = scaleBase which comes from the same c() call)
        scaleBaseY = scale
    }
    
    /**
     * Reset transition state.
     * Resets transitionScaleFactor=1.0, transitionMode=MODE_SLIDE, clears zoom params.
     */
    open fun resetTransition() {
        transitionScaleFactor = 1.0f
        transitionMode = MODE_SLIDE
        zoomLevelCounter = 0
        scaleBaseY = 1.0f
    }
    
    /**
     * Copies layout metrics (row height, spacing, column width) from another engine.
     * Used during transition setup so the target engine can calculate correct positions
     * before performing its own layout.
     * @param source The source engine to copy from
     */
    fun copyLayoutMetricsFrom(source: GridListEngine) {
        // Don't copy actualRowHeight - it will be re-measured by measureCell()
        // during performLayout(). Copying it would keep the old (potentially wrong)
        // height when switching layouts (e.g., grid→list).
        rowSpacing = source.rowSpacing
        colSpacing = source.colSpacing
        // Calculate column width for the new column count
        calculateColumnWidth()
    }
    
    /**
     * Calculates total rows for the given item count.
     * Normally set during performLayout(), but needed earlier for transition position capture.
     * @param itemCount Total number of items
     */
    fun calculateTotalRows(itemCount: Int) {
        totalRows = if (cols <= 1) itemCount else (itemCount + cols - 1) / cols
    }
    
    /**
     * Computes interpolated transform for an item based on zoom progress.
     *
     * Handles transitionMode modes:
     *   MODE_SLIDE(1): horizontal slide
     *   MODE_RECT_SCALE(2): rect-based scale (not used for list)
     *   MODE_SCALE(3): simple scale + alpha
     *   MODE_ZOOM(4): center-scale + alpha
     *   MODE_TRANSLATE(5): translate by zoomCenterX/zoomCenterY
     *
     * After base transform, applies:
     *   - alpha: alphaStartValue * (1 - progress) for modes {2,3,4}
     *   - scaleY: transitionScaleFactor * scaleBaseY for modes {2,3,4}
     *
     * @param posData The ItemPosition to transform (will be modified in place)
     * @param holder The ViewHolder (used to check item type for header handling)
     * @param progress Zoom progress (0.0-1.0) — already adjusted per engine
     * @param isTarget Direction flag (true = forward/target, false = backward/source)
     */
    open fun computeTransitionTransform(posData: ItemPosition, holder: ViewHolder?, progress: Float, isTarget: Boolean) {
        // 1:1 with Poweramp's r.j() / AbstractC0875.j() / w():
        //
        // r.j() base: alpha = Utils.X(f, 1.0, 0.0) = 1 - f
        // AbstractC0875.j() modes:
        //   MODE_SLIDE(1): translateX = f * width (not used for zoom)
        //   MODE_SCALE(3): alpha = w(f) = 1 - f; scaleX = scaleY = C
        //   MODE_ZOOM(4):  fA = lerp(1, s, f) = 1 + (s-1)*f
        //                   scaleX = scaleY = fA * C; alpha = w(f) = 1 - f
        //   MODE_TRANSLATE(5): moveTo(offset)
        //
        // Where: f = progress (passed from m1209)
        //        C = transitionScaleFactor (set by B() via q1.mo2962)
        //        s = zoomScaleBase (set by c() / setupTransitionMode)
        //
        // Header handling (pos == -10) is in c2.j() override (GridEngine).

        // Poweramp w(f): alpha = Utils.X(f, 1.0, 0.0) = 1 - f.
        // Source-only rows are called with f=global progress and fade out;
        // target-only rows are called with f=1-global progress and fade in.
        val alpha = 1.0f - progress

        // Compute scale based on mode
        val scaleY: Float
        val scaleX: Float
        when (transitionMode) {
            MODE_SCALE -> {
                // MODE_SCALE(3): scaleX = scaleY = C
                scaleY = transitionScaleFactor
                scaleX = transitionScaleFactor
            }
            MODE_ZOOM -> {
                // MODE_ZOOM(4): fA = A.A(s, 1, f, 1) = 1 + (s - 1) * f
                //               scaleX = scaleY = fA * C
                val fA = 1f + (scaleBaseY - 1f) * progress
                val rectScaleDelta = fA - 1f
                val centerX = (posData.left + posData.right) / 2f
                val centerY = (posData.top + posData.bottom) / 2f
                val newLeft = (posData.left + (centerX - zoomCenterX) * rectScaleDelta).toInt()
                val newTop = (posData.top + (centerY - zoomCenterY) * rectScaleDelta).toInt()
                posData.moveTo(newLeft, newTop)
                scaleY = fA * transitionScaleFactor
                scaleX = scaleY
            }
            else -> {
                // MODE_SLIDE, MODE_TRANSLATE: no scale change
                scaleY = 1.0f
                scaleX = 1.0f
            }
        }

        posData.setAlpha(alpha)
        posData.setScaleY(scaleY)
        posData.setScaleX(scaleX)
    }
    
    /**
     * Apply fade-out alpha: alpha = 1 - progress.
     */
    private fun applyFadeOut(posData: ItemPosition, progress: Float) {
        posData.setAlpha(1.0f - progress)
    }
    
    /**
     * Linear interpolation helper.
     */
    private fun lerp(start: Float, end: Float, fraction: Float): Float {
        return start + (end - start) * fraction
    }
    
    /**
     * Resets the engine state.
     */
    override fun reset() {
        super.reset()
        colWidth = 0
        actualRowHeight = rowHeight
        visibleRows = 0
        firstVisibleRow = 0
        lastVisibleRow = 0
        totalRows = 0
        scrollY = 0
        zoomScale = 1.0f
        currentZoomProgress = 0f
        // Reset transition fields
        transitionScaleFactor = 1.0f
        alphaStartValue = 1.0f
        scaleBaseY = 1.0f
        transitionMode = MODE_SLIDE
        zoomCenterX = 0f
        zoomCenterY = 0f
        zoomScaleBase = 1.5f
    }
}
