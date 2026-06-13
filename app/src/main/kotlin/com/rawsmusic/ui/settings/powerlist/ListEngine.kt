package com.rawsmusic.ui.settings.powerlist

import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import com.rawsmusic.core.ui.R
import com.rawsmusic.core.ui.widget.scene.AAItemView

/**
 * Equivalent to Poweramp's b2.java - single-column list layout engine.
 * 
 * This engine handles single-column list layouts. It's a special case of
 * GridListEngine with cols=1. Items occupy the full available width.
 * 
 * From Poweramp's b2 constructor:
 * b2(int cols=1, null, boolean, float density)
 */
class ListEngine(
    rowHeight: Int = 0,
    density: Float = 1.0f
) : GridListEngine(1, rowHeight, density) {
    companion object {
        private const val ENABLE_SCROLL_EDGE_OFFSET = false
    }
    
    /**
     * Positions an item in the list.
     * For single-column, items take the full width.
     */
    override fun positionItem(holder: ViewHolder, view: View, position: ItemPosition, sceneId: Int) {
        val row = positionToRow(holder.position)

        val zoomParams = activeZoomParams
        val slotLayoutView = if (view is AAItemView && zoomParams != null && cols == 1) view else null
        if (slotLayoutView != null && zoomParams != null) {
            slotLayoutView.beginSlotLayoutZoomParams(zoomParams)
        }

        try {
            if (holder.alreadyMeasuredForTarget) {
                val measuredH = view.measuredHeight.coerceAtLeast(1)
                if (!preserveRowHeightForZoom) {
                    actualRowHeight = maxOf(actualRowHeight, measuredH)
                }
                holder.alreadyMeasuredForTarget = false

                val left = padding.left - scrollOffset.x
                val top = padding.top + row * (actualRowHeight + rowSpacing) - scrollOffset.y
                position.set(left, top, left + colWidth, top + measuredH)
            } else {
                measureCell(view, position)
                val left = padding.left - scrollOffset.x
                val top = padding.top + row * (actualRowHeight + rowSpacing) - scrollOffset.y
                position.set(left, top, left + colWidth, top + actualRowHeight)
            }
        } finally {
            slotLayoutView?.endSlotLayoutZoomParams()
        }

        // W() — Poweramp: sVar.y(1.0f, this.C, scene)
        // alpha=1.0, scale=C (transitionScaleFactor), scene
        // During zoom transition: both engines write base values.
        // The zoom effect comes from different POSITIONS (different zoom params),
        // not from j() scale/alpha transforms.
        val effectiveSceneId = if (cols > 1) {
            PowerListSceneItem.SCENE_GRID
        } else {
            activeZoomParams?.let { sceneIdForZoomParams(it) } ?: PowerListSceneItem.SCENE_NORMAL
        }
        val effectiveScale = if (inDualEngineTransition) transitionScaleFactor else 1.0f
        position.setTransform(1.0f, effectiveScale, effectiveSceneId)
    }
    
    /**
     * Measures and positions a single item view.
     * Overrides base to implement list-specific logic.
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
     * Core layout method - positions all visible items.
     * Implements the fill pattern for list layout.
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

        // Set transition state for j() — Poweramp's r.t(f, boolean) equivalent.
        // Main engine: progress, Backup engine: 1-progress
        if (stateManager.transitionState == LayoutState.STATE_DUAL_ENGINE_TRANSITION) {
            currentTransitionProgress = if (isBackupEngine) (1f - stateManager.transitionProgress) else stateManager.transitionProgress
            inDualEngineTransition = true
        } else {
            currentTransitionProgress = 0f
            inDualEngineTransition = false
        }

        // Save previous row height for initial visible row calculation.
        // Reset actualRowHeight so it gets re-measured from actual views.
        // This is critical when switching layouts (e.g., grid→list) because
        // measureCell() uses maxOf() which would keep the old (larger) height.
        val prevRowHeight = actualRowHeight
        if (!preserveRowHeightForZoom) {
            actualRowHeight = 0
        }
        // Don't reset preserveRowHeightForZoom here — defer to after layout loop
        // so measureCell()/b0() knows not to override the zoom row height.
        // (Matches GridListEngine.performLayout() behavior.)

        // Calculate column width (full width for list)
        calculateColumnWidth()
        
        // Total rows = item count for single column
        totalRows = itemCount
        
        // Update scroll position and compute delta
        val newScrollY = scrollOffset.y
        scrollDelta = newScrollY - prevScrollY
        prevScrollY = newScrollY
        scrollY = newScrollY
        
        // Calculate visible rows using previous row height for scroll position.
        // During dual-engine zoom, keep one row above the viewport alive too: Poweramp
        // lets the top edge row go through the one-sided j() fade instead of appearing
        // only after the holder scrolls out and gets rebound.
        val viewportHeight = getAvailableHeight()
        val layoutTopBufferRows = if (stateManager.transitionState == LayoutState.STATE_DUAL_ENGINE_TRANSITION) 1 else 0
        val baseFirstVisibleRow = if (prevRowHeight > 0) {
            Math.max(0, scrollY / (prevRowHeight + rowSpacing))
        } else {
            0
        }
        firstVisibleRow = (baseFirstVisibleRow - layoutTopBufferRows).coerceAtLeast(0)

        visibleRows = if (prevRowHeight > 0) {
            (viewportHeight + prevRowHeight + rowSpacing - 1) / (prevRowHeight + rowSpacing) + 4 + layoutTopBufferRows
        } else {
            10 + layoutTopBufferRows // fallback
        }

        lastVisibleRow = Math.min(totalRows - 1, firstVisibleRow + visibleRows - 1)

        // Poweramp stores the active slot on the container. During zoom
        // transitions, a2.B() swaps containers, so main/backup is not enough
        // to infer the slot id.
        val slotIndex = positionSlot
        
        // Layout visible items
        for (row in firstVisibleRow..lastVisibleRow) {
            val position = row // For single column, position = row
            if (position >= itemCount) break
            
                // Poweramp u.y(): both engines use the SAME shared ViewPool.
                // Each engine writes to its container slot.
                val holder = if (isBackupEngine) {
                    stateManager.getBackupViewHolder(position)
                } else {
                    stateManager.getViewHolder(position)
                } ?: continue
                val view = holder.view ?: continue
                
                val itemPos = holder.getPositionData(slotIndex) ?: ItemPosition().also {
                    holder.setPositionData(slotIndex, it)
                }
                
                // For target engine, pre-measure with target slot params only. Do not change
                // AAItemView.currentScene here: Poweramp leaves the visible item scene owned by
                // C.G0()/mo2931() during B.m1209(), while engines only write slot data.
                var switchedScene = false
                var savedScene = -1
                if (isTargetEngine) {
                    val targetScene = if (cols > 1) {
                        PowerListSceneItem.SCENE_GRID
                    } else {
                        activeZoomParams?.let { sceneIdForZoomParams(it) } ?: PowerListSceneItem.SCENE_NORMAL
                    }
                    if (view is AAItemView) {
                        val targetParams = activeZoomParams
                        if (targetParams != null) {
                            val w = when {
                                view.width > 0 -> view.width
                                colWidth > 0 -> colWidth
                                else -> (view.parent as? View)?.width ?: 1080
                            }
                            view.beginSlotLayoutZoomParams(targetParams)
                            try {
                                view.measure(
                                    View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                                )
                                holder.alreadyMeasuredForTarget = true
                            } finally {
                                view.endSlotLayoutZoomParams()
                            }
                        }
                    } else {
                        val root = view as? ConstraintLayout
                        if (root != null) {
                            if (holder.currentScene != targetScene) {
                                savedScene = holder.currentScene
                                SceneHelper.applyScene(root, cols, activeZoomParams)
                                val w = root.width
                                val h = root.height
                                if (w > 0 && h > 0) {
                                    root.measure(
                                        View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                                        View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
                                    )
                                }
                                holder.currentScene = targetScene
                                switchedScene = true
                            }
                        }
                    }
                }

                // Compute base position
                positionItem(holder, view, itemPos, slotIndex)

                // Revert to the original scene if we switched it
                if (switchedScene && savedScene >= 0) {
                    if (view !is AAItemView) {
                        val root = view as? ConstraintLayout
                        if (root != null) {
                            val sourceCols = (stateManager.getBackupEngine() as? GridListEngine)?.cols ?: cols
                            SceneHelper.applyScene(root, sourceCols, activeZoomParams)
                            val w = root.width
                            val h = root.height
                            if (w > 0 && h > 0) {
                                root.measure(
                                    View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                                    View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
                                )
                            }
                            holder.currentScene = savedScene
                        }
                    }
                }
                
                // NOTE: We do NOT call j()() here during transitions.
                // Poweramp's m1209() computes transforms in updateVisibleItems() by
                // interpolating between source and target base positions using
                // translationX/Y and scaleX/Y. The base bounds (left/top/right/bottom)
                // set by b0() above are the only values used from ItemPosition.
        }

        // Edge fade — Poweramp h() lines 2838-2986
        // Applies position-dependent alpha and vertical translation to items
        // based on their position in the visible range.
        // Poweramp's v1 = scroll delta (per-frame), not absolute scrollY.
        // Poweramp's р = row height threshold, i = isScrolled flag.
        // Runs during ALL layouts (normal scroll, pinch, AND transitions).
        if (ENABLE_SCROLL_EDGE_OFFSET && !inDualEngineTransition && scrollDelta != 0 && actualRowHeight > 0) {
            val scrollRatio = scrollDelta.toFloat() / actualRowHeight.toFloat()
            val edgeFactor = Math.abs(scrollRatio) * 0.04f
            val visibleRowCount = lastVisibleRow - firstVisibleRow + 1
            if (visibleRowCount > 0) {
                val rowFractionStep = 1.0f / visibleRowCount
                for (row in firstVisibleRow..lastVisibleRow) {
                    val rowOffset = row - firstVisibleRow
                    val fraction = (rowOffset + 1) * rowFractionStep
                    val alpha: Float
                    val translationY: Int
                    if (scrollDelta <= 0) {
                        alpha = 1.0f + fraction * edgeFactor
                        translationY = Math.round(fraction * scrollRatio * actualRowHeight.toFloat())
                    } else {
                        translationY = Math.round(fraction * scrollRatio * actualRowHeight.toFloat())
                        alpha = 1.0f - fraction * edgeFactor
                    }
                    for (col in 0 until cols) {
                        val pos = row * cols + col
                        if (pos >= itemCount) break
                        val holder = stateManager.getViewHolder(pos) ?: continue
                        val slot = holder.getPositionData(slotIndex) ?: continue
                        // v() method: alpha = scaleY * alpha, translate(0, translationY)
                        slot.alpha = slot.scaleY * alpha
                        slot.offset(0, translationY)
                    }
                }
            }
        }

        // Reset AFTER layout loop so measureCell()/b0() respects the flag during measurement.
        preserveRowHeightForZoom = false
    }
    
}
