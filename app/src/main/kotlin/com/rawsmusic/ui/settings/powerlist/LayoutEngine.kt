package com.rawsmusic.ui.settings.powerlist

import android.graphics.Point
import android.graphics.Rect

/**
 * Equivalent to Poweramp's r.java - abstract base class for layout engines.
 * 
 * A LayoutEngine is responsible for:
 * - Calculating item positions based on scroll offset
 * - Managing item view layout (measure + position)
 * - Handling scroll offset changes
 * - Providing scroll position information
 * 
 * Poweramp uses two layout engines simultaneously during zoom transitions:
 * - B (source engine) - the current layout
 * - f4251 (target engine) - the layout being transitioned to
 */
abstract class LayoutEngine {
    /** Current layout status */
    var layoutStatus: Int = 0

    /** Attach state - tracks whether engine is active */
    var attachState: AttachState = AttachState.DETACHED

    /** Sentinel/recycled ViewHolder (Poweramp's r.P = v0) */
    @JvmField val P: ViewHolder = ViewHolder()

    /** Bounds rectangle (0, 0, width, height) */
    val bounds = Rect()

    /** Padding rectangle */
    val padding = Rect()

    /** Temp rect for calculations */
    protected val tempRect = Rect()
    
    /** Floating-point scroll offset (for sub-pixel precision) */
    @JvmField val scrollOffsetF = PointF(0.0, 0.0)
    
    /** Integer scroll offset (derived from scrollOffsetF) */
    @JvmField val scrollOffset = Point(0, 0)
    
    /**
     * Core layout method - positions all visible items.
     * Called when layout needs to be recalculated.
     * 
     * @param layoutStatus Current layout status flags
     * @param firstVisible First visible item index
     * @param lastVisible Last visible item index  
     * @param itemCount Total item count
     * @param stateManager The LayoutState manager (for accessing view pool, etc.)
     */
    abstract fun performLayout(
        layoutStatus: Int,
        firstVisible: Int,
        lastVisible: Int,
        itemCount: Int,
        stateManager: LayoutState
    )
    
    /**
     * Measures and positions a single item view.
     *
     * @param holder The ViewHolder to measure/position
     * @param view The actual View
     * @param position The item position data to update
     * @param sceneId Scene ID for this position
     */
    open fun measureAndPosition(
        holder: ViewHolder,
        view: android.view.View,
        position: ItemPosition,
        sceneId: Int
    ) {
        tempRect.set(bounds)
        applyPadding(tempRect)
        val widthSpec = android.view.View.MeasureSpec.makeMeasureSpec(tempRect.width(), android.view.View.MeasureSpec.EXACTLY)
        val heightSpec = android.view.View.MeasureSpec.makeMeasureSpec(tempRect.height(), android.view.View.MeasureSpec.EXACTLY)
        view.measure(widthSpec, heightSpec)
        val measuredWidth = view.measuredWidth
        val measuredHeight = view.measuredHeight
        position.setTransform(1.0f, 1.0f, sceneId)
        position.set(tempRect.left, tempRect.top, tempRect.left + measuredWidth, tempRect.top + measuredHeight)
    }
    
    /**
     * Sets the dimensions and padding for this engine.
     * Called when the PowerList is laid out or resized.
     */
    open fun setDimensions(width: Int, height: Int, left: Int, top: Int, right: Int, bottom: Int) {
        padding.set(left, top, right, bottom)
        bounds.set(0, 0, width, height)
    }
    
    /**
     * Applies scroll offset change.
     * @return true if the scroll offset actually changed (integer part)
     */
    open fun scrollBy(dx: Double, dy: Double): Boolean {
        if (dx.isNaN() || dx.isInfinite()) return false
        if (dy.isNaN() || dy.isInfinite()) return false
        
        val oldX = scrollOffset.x
        val oldY = scrollOffset.y
        
        scrollOffsetF.x += dx
        scrollOffsetF.y += dy
        scrollOffset.set(
            Math.round(scrollOffsetF.x).toInt(),
            Math.round(scrollOffsetF.y).toInt()
        )
        
        return oldX != scrollOffset.x || oldY != scrollOffset.y
    }
    
    /**
     * Sets absolute scroll offset.
     */
    open fun setScrollOffset(x: Double, y: Double) {
        if (x.isNaN() || x.isInfinite()) return
        if (y.isNaN() || y.isInfinite()) return
        
        scrollOffsetF.x = x
        scrollOffsetF.y = y
        scrollOffset.set(
            Math.round(x).toInt(),
            Math.round(y).toInt()
        )
    }
    
    /**
     * Resets the engine to initial state.
     */
    open fun reset() {
        layoutStatus = 0
        attachState = AttachState.DETACHED
        padding.setEmpty()
        bounds.setEmpty()
        scrollOffsetF.x = 0.0
        scrollOffsetF.y = 0.0
        scrollOffset.set(0, 0)
    }
    
    /**
     * Gets the available width (bounds minus padding).
     */
    fun getAvailableWidth(): Int {
        return (bounds.right - bounds.left) - padding.left - padding.right
    }

    /**
     * Gets the available height (bounds minus padding).
     */
    fun getAvailableHeight(): Int {
        return (bounds.bottom - bounds.top) - padding.top - padding.bottom
    }
    
    /**
     * Applies padding to a rect (shrinks it).
     */
    protected fun applyPadding(rect: Rect) {
        rect.left += padding.left
        rect.top += padding.top
        rect.right -= padding.right
        rect.bottom -= padding.bottom
    }
    
    /**
     * Gets the current scroll offset as a Point (integer values).
     */
    fun getScrollOffset(): Point = scrollOffset
    
    /**
     * Point with double precision for sub-pixel scroll tracking.
     */
    data class PointF(var x: Double = 0.0, var y: Double = 0.0)
    
    /**
     * Attach state enum matching Poweramp's B.java.
     */
    enum class AttachState {
        DETACHED,
        ATTACHED,
        RECYCLING
    }
}
