package com.rawsmusic.ui.settings.powerlist

import android.view.View

/**
 * Equivalent to Poweramp's c2.java - multi-column grid layout engine.
 * 
 * This engine handles grid layouts with 2+ columns. It calculates:
 * - Column width: colWidth = (availableWidth - padding.left - padding.right) / cols
 * - Item positioning: x = (col * colWidth) + padding.left + offset
 * - Row height: either fixed or measured from view
 * 
 * Example from Poweramp's SelectAlbumArtActivity:
 * Portrait: new c2(density, 2, -1) // 2 columns, auto row height
 * Landscape: new c2(density, 4, -1) // 4 columns, auto row height
 */
class GridEngine(
    cols: Int,
    rowHeight: Int = 0,
    density: Float = 1.0f,
    /** Poweramp c2.u: extra vertical row spacing added to row stride O. */
    rowSpacingPx: Int = 0,
    /** Poweramp c2.v: C0874/-10 holder shrink mode. */
    powerampHeaderShrinkMode: Int = 2,
    /** Poweramp c2.F: base alpha for the C0874/-10 holder. */
    powerampHeaderBaseAlpha: Float = 1.0f,
    /** Poweramp c2.z: bottom offset for the C0874/-10 holder. */
    powerampHeaderBottomOffset: Int = 0,
    /** Whether Poweramp's C0874 special holder is attached. */
    powerampEnableGridHeaderHolder: Boolean = true
) : GridListEngine(cols, rowHeight, density) {

    // Validation from Poweramp's c2 constructor
    init {
        if (cols < 2) throw IllegalArgumentException("GridEngine requires cols >= 2, got $cols")
        rowSpacing = rowSpacingPx
    }
    
    /**
     * Positions an item in the grid.
     * From Poweramp's c2.java line 96:
     * i10 = (i2 * i9) + i8 + i7 + this.X.left
     * where i2 = column index, i9 = column width, i8 = offset, i7 = padding
     */
    override fun positionItem(holder: ViewHolder, view: View, position: ItemPosition, sceneId: Int) {
        val row = positionToRow(holder.position)
        val col = positionToCol(holder.position)

        holder.alreadyMeasuredForTarget = false
        val left = padding.left + col * colWidth - scrollOffset.x
        val top = padding.top + row * rowStridePx() - scrollOffset.y
        val itemHeight = actualRowHeight + gridLabelHeightPx()
        position.set(left, top, left + colWidth, top + itemHeight)

        // W(): alpha=1.0, scaleX=scaleY=C (transitionScaleFactor)
        val effectiveSceneId = if (cols > 1) {
            PowerListSceneItem.SCENE_GRID
        } else {
            activeZoomParams?.let { sceneIdForZoomParams(it) } ?: PowerListSceneItem.SCENE_NORMAL
        }
        position.setTransform(1.0f, transitionScaleFactor, effectiveSceneId)
    }
    
    /**
     * Measures and positions a single item view.
     * Overrides base to implement grid-specific logic.
     */
    override fun measureAndPosition(
        holder: ViewHolder,
        view: View,
        position: ItemPosition,
        sceneId: Int
    ) {
        positionItem(holder, view, position, sceneId)
    }

    /**
     * Grid-specific transform override — 1:1 with Poweramp's c2.j().
     *
     * c2.j() calls super.j() first (r.j → AbstractC0875.j), which handles regular items.
     * Then for header (pos == -10): overrides alpha and scale.
     *
     * Poweramp c2.j():
     *   super.j(sVar, v0Var, f, z);           // base transform
     *   if (v0Var.f4258 == -10) {              // header
     *     if (mode == 2 || 3 || 4) {
     *       sVar.x(Utils.X(f, D, 0));          // alpha = D * f
     *     }
     *     sVar.m2996(C * J);                   // scaleX = scaleY = C * J
     *   }
     */
    override fun computeTransitionTransform(posData: ItemPosition, holder: ViewHolder?, progress: Float, isTarget: Boolean) {
        // First: apply base transform (r.j → AbstractC0875.j)
        // This sets alpha = f, scale based on mode for regular items.
        // For headers, we override below.
        if (holder?.position != -10) {
            // Regular item: base transform handles it
            super.computeTransitionTransform(posData, holder, progress, isTarget)
            return
        }

        // Header item (pos == -10): c2.j() calls super first, then overrides
        // alpha only for modes 2/3/4 and always overrides scale to C * J.
        super.computeTransitionTransform(posData, holder, progress, isTarget)
        val mode = transitionMode
        if (mode == MODE_RECT_SCALE || mode == MODE_SCALE || mode == MODE_ZOOM) {
            posData.setAlpha(headerAlpha * (1.0f - progress))
        }

        // scaleX = scaleY = C * J (Poweramp's m2996(C * J))
        val headerScaleVal = transitionScaleFactor * headerScale
        posData.setScaleX(headerScaleVal)
        posData.setScaleY(headerScaleVal)
    }

    /** Whether to materialize Poweramp's C0874 special album/header holder (position -10). */
    var enableGridHeaderHolder: Boolean = powerampEnableGridHeaderHolder

    /** Header background alpha (Poweramp c2.D). */
    var headerAlpha: Float = 1.0f

    /** Header scale factor (Poweramp c2.J). */
    var headerScale: Float = 1.0f

    /** Header top (Poweramp c2.G). */
    var headerTop: Int = 0
        private set

    /** Whether header slot is valid (Poweramp c2.I). */
    var hasHeaderSlot: Boolean = false
        private set

    /** Header shrink mode (Poweramp c2.v). */
    var headerShrinkMode: Int = powerampHeaderShrinkMode

    /** Header base alpha (Poweramp c2.F). */
    var headerBaseAlpha: Float = powerampHeaderBaseAlpha

    /** Header bottom offset (Poweramp c2.z). */
    var headerBottomOffset: Int = powerampHeaderBottomOffset

    /** Poweramp C0874.A: special -10 holder height, separate from grid row height. */
    var headerHolderHeightPx: Int = 0

    /** Equivalent to C0874.K != null. RawSMusic uses a viewport anchor by default. */
    var hasPowerampHeaderAnchor: Boolean = true

    /** Equivalent to C0874.K.Н(), added to the bottom-anchored top. */
    var headerAnchorOffsetPx: Int = 0

    /** Equivalent to Poweramp helper offset х.B(this), subtracted in c2.v == 2 formula. */
    var headerShrinkHelperOffsetPx: Int = 0

    private val effectiveHeaderHolderHeight: Int
        get() = (if (headerHolderHeightPx > 0) headerHolderHeightPx else actualRowHeight).coerceAtLeast(1)

    private fun layoutGridHeaderHolder(stateManager: LayoutState, slotIndex: Int) {
        if (!enableGridHeaderHolder || !hasPowerampHeaderAnchor) {
            stateManager.hideGridHeaderHolder()
            hasHeaderSlot = false
            return
        }
        val holder = stateManager.getGridHeaderViewHolder() ?: return
        val view = holder.view ?: return
        val itemPos = holder.getPositionData(slotIndex) ?: ItemPosition().also {
            holder.setPositionData(slotIndex, it)
        }
        updateGridHeaderState()
        val holderHeight = effectiveHeaderHolderHeight
        val left = padding.left
        val top = headerTop
        val right = bounds.right - padding.right
        itemPos.set(left, top, right, top + holderHeight)
        itemPos.setTransform(headerAlpha, transitionScaleFactor * headerScale, PowerListSceneItem.SCENE_GRID)
        view.visibility = View.VISIBLE
    }

    /** Poweramp c2.f0(): compute D alpha, J scale and G top for special -10 holder. */
    private fun updateGridHeaderState() {
        val holderHeight = effectiveHeaderHolderHeight
        val layoutBottom = bounds.bottom
        val insetBottom = padding.bottom
        var top = layoutBottom - headerBottomOffset - holderHeight + headerAnchorOffsetPx
        val maxBottom = layoutBottom - insetBottom
        if (top + holderHeight > maxBottom) {
            top = layoutBottom - holderHeight - insetBottom
        }
        headerTop = top

        val rowStride = rowStridePx().coerceAtLeast(1)
        val shrinkDistance = (density * 104.0f).coerceAtLeast(1.0f)
        val t = when (headerShrinkMode) {
            1 -> {
                if (firstVisibleRow == 0) {
                    val headerOffset = rowStride.toFloat()
                    if (headerOffset == 0f) 0f else ((-scrollY).coerceAtLeast(0)).toFloat() / headerOffset
                } else {
                    1f
                }
            }
            2 -> {
                if (firstVisibleRow < 3) {
                    val raw = ((-scrollY) - headerShrinkHelperOffsetPx + firstVisibleRow * rowStride).coerceAtLeast(0)
                    raw.toFloat() / shrinkDistance
                } else {
                    1f
                }
            }
            else -> 1f
        }.coerceIn(0f, 1f)

        headerAlpha = headerBaseAlpha * t
        headerScale = 0.85f + 0.15f * t
        hasHeaderSlot = true
    }

    /**
     * Core layout method - positions all visible items.
     * Implements the fill pattern for grid layout.
     * During transitions, uses correct slot index and applies j()().
     */
    override fun performLayout(
        layoutStatus: Int,
        firstVisible: Int,
        lastVisible: Int,
        itemCount: Int,
        stateManager: LayoutState
    ) {
        if (itemCount <= 0) return

        if (stateManager.transitionState == LayoutState.STATE_DUAL_ENGINE_TRANSITION) {
            currentTransitionProgress = if (isBackupEngine) (1f - stateManager.transitionProgress) else stateManager.transitionProgress
            inDualEngineTransition = true
        } else {
            currentTransitionProgress = 0f
            inDualEngineTransition = false
        }

        // Calculate column width first. Poweramp c2.s(): w=available/cols, O=(fixedHeight>0 ? fixedHeight : w)+spacing.
        calculateColumnWidth()
        if (!preserveRowHeightForZoom) {
            actualRowHeight = if (rowHeight > 0) rowHeight else colWidth
        }
        val rowStrideHeight = rowStridePx().coerceAtLeast(1)
        preserveRowHeightForZoom = false

        // Calculate total rows
        totalRows = (itemCount + cols - 1) / cols

        
        // Update scroll position
        scrollY = scrollOffset.y
        
        // Calculate visible rows from Poweramp c2 row stride O.
        val viewportHeight = getAvailableHeight()
        firstVisibleRow = Math.max(0, scrollY / rowStrideHeight)

        visibleRows = (viewportHeight + rowStrideHeight - 1) / rowStrideHeight + 2
        
        lastVisibleRow = Math.min(totalRows - 1, firstVisibleRow + visibleRows - 1)

        val slotIndex = positionSlot
        
        layoutGridHeaderHolder(stateManager, slotIndex)

        // Layout visible items
        for (row in firstVisibleRow..lastVisibleRow) {
            for (col in 0 until cols) {
                val position = row * cols + col
                if (position >= itemCount) break
                
                // Both engines create/recycle holders as needed.
                // Target engine also creates holders so target-only items (visible in target
                // but not in source) can fade in during the transition.
                val holder = stateManager.getViewHolder(position) ?: continue
                val view = holder.view ?: continue
                
                // Get or create position data for the correct slot
                val itemPos = holder.getPositionData(slotIndex) ?: ItemPosition().also { 
                    holder.setPositionData(slotIndex, it) 
                }
                
                // Compute base position
                positionItem(holder, view, itemPos, slotIndex)
                
                // NOTE: We do NOT call j()() here during transitions.
                // Poweramp's m1209() computes transforms in updateVisibleItems() by
                // interpolating between source and target base positions using
                // translationX/Y and scaleX/Y. The base bounds (left/top/right/bottom)
                // set by b0() above are the only values used from ItemPosition.
            }
        }
    }
}
