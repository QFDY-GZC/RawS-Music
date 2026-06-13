package com.rawsmusic.ui.settings.powerlist

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.ViewGroup
import android.widget.OverScroller
import androidx.constraintlayout.widget.ConstraintLayout
import com.rawsmusic.core.ui.R
import com.rawsmusic.core.ui.widget.scene.AAItemView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt


/**
 * Equivalent to Poweramp's PowerList.java - custom ViewGroup for song list.
 * 
 * This replaces RecyclerView with a custom ViewGroup that:
 * - Supports multiple column layouts (list, 2-col, 3-col, 4-col grid)
 * - Uses view.layout() for base positioning
 * - Manages its own view recycling
 * 
 * Key architecture from Poweramp:
 * - LayoutState (u.java) manages layout engines
 * - ViewPool (d2.java) recycles views
 */
class PowerListView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ViewGroup(context, attrs, defStyleAttr) {
    
    companion object {
        private const val TAG = "PowerListView"

        /** Maximum number of columns allowed */
        const val MAX_COLUMNS = 4

        /** Minimum number of columns */
        const val MIN_COLUMNS = 1

        /** Default row height in dp - matches zoom NORMAL level (96dp) */
        private const val DEFAULT_ROW_HEIGHT_DP = 96

        /** Scroll friction factor */
        private const val SCROLL_FRICTION = 0.015f

        /** Fling friction factor */
        private const val FLING_FRICTION = 0.9f

        /** SharedPreferences key for column count persistence */
        private const val KEY_COLUMN_COUNT = "column_count"

        private const val RETURN_VELOCITY_THRESHOLD_DP = 500f
        private const val RETURN_PROGRESS_THRESHOLD = 0.3f
        private const val RETURN_COMMIT_DURATION_MS = 250L
        private const val RETURN_ROLLBACK_DURATION_MS = 500L
        private const val RETURN_COMMIT_VELOCITY_GATE = 0.8f
        private const val RETURN_ROLLBACK_VELOCITY_GATE = 0.2f
        private const val COLUMN_COMMIT_DURATION_MS = 250L
        private const val COLUMN_ROLLBACK_DURATION_MS = 500L

    }

    data class SceneReturnSpec(
        val targetRectInWindow: RectF,
        val swipeRight: Boolean,
        val initialTouchX: Float = 0f,
        val initialTouchY: Float = 0f
    )

    /** Poweramp AbstractC0666.N0() display sequence: list levels, then 4→3→2 grid. */
    private enum class PowerListDisplayMode(val columns: Int, val sceneId: Int, val listLevel: ListZoomIndex? = null) {
        LIST_SMALL(1, PowerListSceneItem.SCENE_SMALL, ListZoomIndex.SMALL),
        LIST_NORMAL(1, PowerListSceneItem.SCENE_NORMAL, ListZoomIndex.NORMAL),
        LIST_ZOOMED(1, PowerListSceneItem.SCENE_ZOOMED, ListZoomIndex.ZOOMED),
        GRID_4(4, PowerListSceneItem.SCENE_GRID),
        GRID_3(3, PowerListSceneItem.SCENE_GRID),
        GRID_2(2, PowerListSceneItem.SCENE_GRID);

        val isGrid: Boolean get() = columns > 1
    }

    private enum class ActivePowerListTransition { NONE, COLUMN_ZOOM, SCENE_RETURN }

    // ==================== Core Components ====================

    /** Layout state manager - coordinates dual engines */
    val layoutState = LayoutState(this)

    /** List zoom manager for pinch-to-zoom in single-column mode */
    val listZoomManager = ListZoomManager(context).apply {
        onPinchStart = { targetLevel, isZoomIn ->
            // Start dual-engine transition at the beginning of pinch gesture
            startSnapTransition(targetLevel, isZoomIn)
        }
        onPinchProgress = { progress, scaleFactor, isZoomIn ->
            // Update dual-engine crossfade during pinch gesture
            updateSnapTransition(progress, scaleFactor, isZoomIn)
        }
        onSnapStart = { targetLevel, isZoomIn ->
            // Poweramp's q1.p() → a2.B() flow: freeze source layer, create target layer
            // Only called when snap starts WITHOUT a prior pinch transition (e.g., cancelSnap)
            startSnapTransition(targetLevel, isZoomIn)
        }
        onSnapProgress = { progress, scaleFactor, isZoomIn ->
            // Poweramp's z1.H() → u.m3013() → q1.K() flow: update crossfade each frame
            updateSnapTransition(progress, scaleFactor, isZoomIn)
        }
        onZoomLevelSnapped = { level ->
            // Poweramp's a2.m2952(): transition complete, clean up source layer
            prefs.edit().putInt("list_zoom_level", level.zoomInt).apply()
            finishSnapTransition(level == transitionTargetLevel)
        }
        onGridPinchProgress = { progress ->
            if (!columnTransitionActive) {
                startColumnZoomTransition(PowerListDisplayMode.GRID_4, isZoomIn = true)
            }
            updateColumnZoomTransition(progress, 1f)
        }
        onGridPinchFinished = { confirmed ->
            finishColumnZoomTransition(confirmed)
        }
        onBoundaryElasticProgress = { scale ->
            applyBoundaryElasticScale(scale)
        }
        onBoundaryElasticFinished = { scale ->
            animateBoundaryElasticBack(scale)
        }
    }
    
    /** Scroller for fling animations */
    private val scroller = OverScroller(context)
    
    /** Velocity tracker for fling detection */
    private var velocityTracker: VelocityTracker? = null
    
    // ==================== Configuration ====================
    
    /** Maximum columns allowed */
    var maxColumns: Int = MAX_COLUMNS
    
    /** Current column count */
    var currentColumns: Int = MIN_COLUMNS
        private set
    
    /** Row height in pixels */
    var rowHeight: Int = 0
    
    /** Item spacing */
    var itemSpacing: Int = 0
    
    /** Column spacing */
    var columnSpacing: Int = 0
    
    // ==================== State ====================
    
    /** Total number of items */
    var itemCount: Int = 0
        private set
    
    /** First visible item position */
    var firstVisiblePosition: Int = 0
        private set
    
    /** Last visible item position */
    var lastVisiblePosition: Int = 0
        private set
    
    /** Current scroll Y position */
    private var scrollY: Int = 0
    
    /** Maximum scroll Y */
    private var maxScrollY: Int = 0

    /** Whether we're in a fling */
    private var isFlinging: Boolean = false

    
    /** Whether we're in a touch scroll */
    private var isTouchScrolling: Boolean = false
    
    /** Pinch state flag (mirrors Poweramp f3940 == 3 / isPinching).
     *  When true, scroll handling is blocked to prevent gesture interference. */
    private var isPinching: Boolean = false

    private var isGridPinching: Boolean = false
    private var gridPinchBaseDistance: Float = 1f
    private var gridPinchLastRatio: Float = 1f
    private var gridPinchLastEventTime: Long = 0L
    private var gridPinchVelocityDp: Float = 0f
    private var gridPinchRatioVelocity: Float = 0f
    private var gridPinchPointerId1: Int = -1
    private var gridPinchPointerId2: Int = -1
    private var gridPinchTargetMode: PowerListDisplayMode? = null
    private var gridPinchIsZoomIn: Boolean = false
    private var gridPinchProgress: Float = 0f
    private var gridBoundaryElasticActive: Boolean = false
    private var boundaryElasticAnimator: ValueAnimator? = null
    private var boundaryElasticScale: Float = 1f
    private var columnSwitchAnimator: ValueAnimator? = null
    private var columnTransitionActive: Boolean = false
    private var columnTransitionSourceMode: PowerListDisplayMode = PowerListDisplayMode.LIST_ZOOMED
    private var columnTransitionTargetMode: PowerListDisplayMode = PowerListDisplayMode.GRID_4
    private var columnTransitionSourceScrollY: Int = 0
    private var columnTransitionTargetScrollY: Int = 0
    private var columnTransitionAnchorPosition: Int = 0
    private var columnTransitionAnchorOffset: Int = 0
    private var columnTransitionAnimator: ValueAnimator? = null
    private var columnTransitionPowerampDirection: Boolean = false
    private var currentDisplayMode: PowerListDisplayMode = PowerListDisplayMode.LIST_NORMAL

    /**
     * 获取当前显示模式对应的封面圆角（dp）。
     * 列表模式使用 zoom level 的 cornerRadiusTracksDp，
     * 网格模式使用 Poweramp 的 corners_aa_tracks_grid。
     */
    val currentCoverCornerRadiusDp: Float
        get() = if (currentDisplayMode.isGrid) {
            // Poweramp corners_aa_tracks_grid (more_rounded theme):
            // 2列/3列/4列网格 = 16dp, 网格 zoomed = 24dp
            // 对照: corners_aa_albums_grid = 8dp, corners_aa_albums_grid_zoomed = 16dp
            16f
        } else {
            listZoomManager.currentParams.cornerRadiusTracksDp
        }
    private var activePowerListTransition: ActivePowerListTransition = ActivePowerListTransition.NONE
    private var activeTransitionDirection: Boolean = true
    private var activeTransitionUseStrictOverlap: Boolean = false
    private var sceneReturnAnimator: ValueAnimator? = null
    private var sceneReturnSourceScrollY: Int = 0
    private var sceneReturnSwipeRight: Boolean = true
    private var sceneReturnInitialTouchX: Float = 0f
    private var sceneReturnProgress: Float = 0f

    var onSceneReturnProgressChanged: ((Float) -> Unit)? = null

    val isSceneReturnTransitionActive: Boolean
        get() = activePowerListTransition == ActivePowerListTransition.SCENE_RETURN
    val sceneReturnTransitionProgress: Float
        get() = sceneReturnProgress

    /** Pending zoom params for frame-coalesced updates during pinch gesture.
     *  Multiple MOVE events per frame are coalesced to a single layout pass. */
    private var pendingZoomParams: ListZoomParams? = null
    private var zoomUpdateScheduled = false
    private val zoomUpdateRunnable = Runnable {
        zoomUpdateScheduled = false
        val params = pendingZoomParams
        // Only apply if still pinching — prevent stale callbacks after pinch ends
        if (params != null && listZoomManager.isPinching) {
            pendingZoomParams = null
            applyZoomImmediate(params)
        } else {
            pendingZoomParams = null
        }
    }

    /**
     * Dual-engine snap transition state (Poweramp's a2/q1 zoom transition).
     *
     * During snap animation, two engines render simultaneously:
     * - Main engine (target): renders items at the new zoom level, fading in
     * - Backup engine (source): renders items at the old zoom level, fading out
     *
     * This matches Poweramp's dual-engine architecture where u.B and u.f4251
     * each hold an O container with its own engine and ViewPool.
     * Crossfade is driven by C (scale factor = 1-progress) and j() transforms.
     */
    

    /** Last touch Y position */
    private var lastTouchY: Float = 0f
    
    /** Touch slop */
    private val touchSlop: Int = ViewConfiguration.get(context).scaledTouchSlop
    
    /** Maximum fling velocity */
    private val maximumFlingVelocity: Int = ViewConfiguration.get(context).scaledMaximumFlingVelocity
    
    /** Minimum fling velocity */
    private val minimumFlingVelocity: Int = ViewConfiguration.get(context).scaledMinimumFlingVelocity
    
    // ==================== Data ====================
    
    /** Data provider for creating views */
    private var dataProvider: PowerListDataProvider? = null
    
    /** Item click listener */
    private var itemClickListener: OnItemClickListener? = null
    
    /** Item long click listener */
    private var itemLongClickListener: OnItemLongClickListener? = null
    
    /** Column count change listener */
    private var columnCountChangeListener: OnColumnCountChangeListener? = null

    /** Poweramp h0.c0/list_bottom_toolbar mode: 0 disables C0874, 1 enables with 0.55 alpha, 2 enables with 1.0 alpha. */
    var powerampListHeaderButtonsMode: Int = 0

    /** Poweramp h0.d0/list_header_buttons flag controlling c2.v shrink mode. */
    var powerampGridHeaderShrinkEnabled: Boolean = true

    /** Poweramp c2.z bottom offset, distinct from view padding.bottom. */
    var powerampGridHeaderBottomOffsetPx: Int = 0

    // ==================== Callbacks ==================
    
    interface OnItemClickListener {
        fun onItemClick(position: Int, view: View)
    }
    
    interface OnItemLongClickListener {
        fun onItemLongClick(position: Int, view: View): Boolean
    }
    
    interface OnColumnCountChangeListener {
        fun onColumnCountChanged(newColumnCount: Int)
    }
    
    interface PowerListDataProvider {
        fun getItemCount(): Int
        fun createView(position: Int, parent: ViewGroup): View?
        fun bindView(position: Int, view: View)
        fun getItemViewType(position: Int): Int
        fun createGridHeaderView(parent: ViewGroup): View? = createView(0, parent)
        fun bindGridHeaderView(view: View) {
            if (getItemCount() > 0) bindView(0, view)
        }
    }
    
    // ==================== Column Count Persistence ====================
    
    private val prefs = context.getSharedPreferences("power_list_prefs", Context.MODE_PRIVATE)
    
    // ==================== Initialization ====================
    
    init {
        // Set default row height
        rowHeight = (DEFAULT_ROW_HEIGHT_DP * context.resources.displayMetrics.density).toInt()

        // Restore persisted zoom level
        val savedZoom = prefs.getInt("list_zoom_level", ListZoomIndex.NORMAL.zoomInt)
        val initialLevel = ListZoomIndex.fromZoomInt(savedZoom)
        listZoomManager.setInitialLevel(initialLevel)
        currentDisplayMode = displayModeForListLevel(initialLevel)

        // Enable children drawing
        setWillNotDraw(false)

        // Initialize layout engines
        initializeEngines()

        // Store initial zoom params in list engines so b0() can apply them during measurement.
        val engine = layoutState.getActiveEngine() as? GridListEngine
        if (engine is ListEngine) {
            engine.activeZoomParams = listZoomManager.currentParams
        }
    }
    
    private fun initializeEngines() {
        val savedColumns = prefs.getInt(KEY_COLUMN_COUNT, MIN_COLUMNS)
            .coerceIn(MIN_COLUMNS, maxColumns)
        currentColumns = savedColumns
        currentDisplayMode = displayModeForColumns(savedColumns)
        val initialEngine = createEngineForDisplayMode(currentDisplayMode)
        layoutState.setSourceEngine(initialEngine)
    }
    
    /**
     * Saves the current column count to SharedPreferences.
     */
    private fun saveColumnCount() {
        prefs.edit().putInt(KEY_COLUMN_COUNT, currentColumns).apply()
    }
    
    /**
     * Switches to the specified column count immediately (no animation).
     */
    private fun switchToColumnCount(columns: Int) {
        switchToColumnCount(columns, animate = true)
    }

    private fun switchToColumnCount(columns: Int, animate: Boolean) {
        val targetCols = max(MIN_COLUMNS, min(columns, maxColumns))
        if (targetCols == currentColumns) return

        if (animate) {
            animateColumnCountChange(targetCols)
            return
        }

        applyColumnCountImmediate(targetCols)
    }

    private fun applyColumnCountImmediate(targetCols: Int) {
        currentColumns = targetCols
        currentDisplayMode = displayModeForColumns(targetCols)

        // Persist the new column count
        saveColumnCount()

        // Create new engine for the target column count
        val newEngine = createEngineForDisplayMode(currentDisplayMode)

        // Copy dimensions from current engine
        val currentEngine = layoutState.getActiveEngine() ?: return
        newEngine.setDimensions(
            currentEngine.bounds.width(), currentEngine.bounds.height(),
            currentEngine.padding.left, currentEngine.padding.top,
            currentEngine.padding.right, currentEngine.padding.bottom
        )
        (currentEngine as? GridListEngine)?.let { src ->
            (newEngine as? GridListEngine)?.copyLayoutMetricsFrom(src)
        }
        if (newEngine is GridEngine) {
            applyPowerampGridParams(newEngine)
        }

        // Swap engines without resetting the dimensions copied above.
        layoutState.setSourceEngine(newEngine, reset = false)

        // Apply scene to all views
        val targetScene = sceneForDisplayMode(currentDisplayMode)
        for (holder in layoutState.viewPool.getAll()) {
            if (holder.isRecycled) continue
            val view = holder.view ?: continue
            if (view is AAItemView) {
                view.currentScene = targetScene
                view.forceLayout()
                holder.currentScene = targetScene
                holder.invalidatePositions()
            } else if (holder.currentScene != targetScene) {
                (view as? ConstraintLayout)?.let { root ->
                    SceneHelper.applyScene(root, targetCols, listZoomManager.currentParams)
                    holder.currentScene = targetScene
                }
            }
        }

        // Refresh layout
        recalculateMaxScroll()
        layoutState.refreshLayout(true, true)

        // Notify listener
        columnCountChangeListener?.onColumnCountChanged(targetCols)
    }

    private fun animateColumnCountChange(targetCols: Int) {
        val fromCols = currentColumns
        val targetMode = displayModeForColumns(targetCols)
        if (targetMode == currentDisplayMode) {
            applyColumnCountImmediate(targetCols)
            return
        }

        columnSwitchAnimator?.cancel()
        startColumnZoomTransition(targetMode, isZoomIn = targetCols > fromCols)
        if (columnTransitionActive) {
            finishColumnZoomTransition(confirmed = true)
        } else {
            applyColumnCountImmediate(targetCols)
        }
    }

    private fun createEngineForColumnCount(columns: Int): GridListEngine {
        return createEngineForDisplayMode(displayModeForColumns(columns))
    }

    private fun createEngineForDisplayMode(mode: PowerListDisplayMode): GridListEngine {
        return if (!mode.isGrid) {
            ListEngine(rowHeight, context.resources.displayMetrics.density).also { engine ->
                mode.listLevel?.let { engine.activeZoomParams = listZoomManager.paramsForLevel(it) }
            }
        } else {
            GridEngine(
                cols = mode.columns,
                density = context.resources.displayMetrics.density,
                rowSpacingPx = powerampGridRowSpacing(mode.columns),
                powerampHeaderShrinkMode = powerampGridHeaderShrinkMode(mode.columns),
                powerampHeaderBaseAlpha = powerampGridHeaderBaseAlpha(),
                powerampHeaderBottomOffset = powerampGridHeaderBottomOffset(),
                powerampEnableGridHeaderHolder = powerampGridHeaderEnabledFor(mode)
            )
        }
    }

    /** Apply Poweramp AbstractC0666.L0/c1.u0 equivalent parameters after metric copies. */
    private fun applyPowerampGridParams(engine: GridEngine) {
        engine.rowSpacing = powerampGridRowSpacing(engine.cols)
        engine.headerShrinkMode = powerampGridHeaderShrinkMode(engine.cols)
        engine.headerBaseAlpha = powerampGridHeaderBaseAlpha()
        engine.headerBottomOffset = powerampGridHeaderBottomOffset()
        engine.enableGridHeaderHolder = powerampGridHeaderEnabled()
    }

    private fun powerampGridRowSpacing(columns: Int): Int {
        // Poweramp N0(): grid modes pass h = x0.Q/S/R/T (style spacing) into c2.u.
        // RawSMusic's track grid scene uses square cells, so default to c2.u = 0 unless
        // app code explicitly sets itemSpacing to a Poweramp-style value.
        return if (columns > 1) itemSpacing.coerceAtLeast(0) else 0
    }

    private fun powerampGridHeaderShrinkMode(columns: Int): Int {
        // Poweramp AbstractC0666.z0(): c2.v = h0.d0.f3795 != 0 ? 2 : 0.
        return if (columns > 1 && powerampGridHeaderShrinkEnabled) 2 else 0
    }

    private fun powerampGridHeaderBaseAlpha(): Float {
        // Poweramp c2.F = 0.55 only when h0.c0.f3795 == 1; other enabled modes use 1.0.
        return if (powerampListHeaderButtonsMode == 1) 0.55f else 1.0f
    }

    private fun powerampGridHeaderBottomOffset(): Int {
        // Poweramp c2.z is separate from the container bottom inset/padding.
        return powerampGridHeaderBottomOffsetPx.coerceAtLeast(0)
    }

    private fun powerampGridHeaderEnabled(): Boolean {
        // Poweramp AbstractC0666.w0(): C0874 exists only when h0.c0 != 0 and data is present.
        return (currentColumns > 1 || columnTransitionActive) && powerampListHeaderButtonsMode != 0 && itemCount > 0
    }

    private fun powerampGridHeaderEnabledFor(mode: PowerListDisplayMode): Boolean {
        return mode.isGrid && powerampListHeaderButtonsMode != 0 && itemCount > 0
    }

    private fun displayModeForListLevel(level: ListZoomIndex): PowerListDisplayMode = when (level) {
        ListZoomIndex.SMALL -> PowerListDisplayMode.LIST_SMALL
        ListZoomIndex.NORMAL -> PowerListDisplayMode.LIST_NORMAL
        ListZoomIndex.ZOOMED -> PowerListDisplayMode.LIST_ZOOMED
    }

    private fun displayModeForColumns(columns: Int): PowerListDisplayMode = when {
        columns <= 1 -> displayModeForListLevel(listZoomManager.currentLevel)
        columns >= 4 -> PowerListDisplayMode.GRID_4
        columns == 3 -> PowerListDisplayMode.GRID_3
        else -> PowerListDisplayMode.GRID_2
    }

    private fun sceneForDisplayMode(mode: PowerListDisplayMode): Int {
        return mode.listLevel?.let { sceneIdForZoomIndex(it) } ?: mode.sceneId
    }

    private fun nextModeForGridPinch(isZoomIn: Boolean): PowerListDisplayMode? {
        return if (isZoomIn) {
            // Poweramp pinch-out/magnify is ZoomIn and walks WQ.g() forward:
            // 4 columns → 3 columns → 2-column zoomed grid.
            when (currentDisplayMode) {
                PowerListDisplayMode.GRID_4 -> PowerListDisplayMode.GRID_3
                PowerListDisplayMode.GRID_3 -> PowerListDisplayMode.GRID_2
                else -> null
            }
        } else {
            // Pinch-in walks WQ.x() backward.
            when (currentDisplayMode) {
                PowerListDisplayMode.GRID_2 -> PowerListDisplayMode.GRID_3
                PowerListDisplayMode.GRID_3 -> PowerListDisplayMode.GRID_4
                PowerListDisplayMode.GRID_4 -> PowerListDisplayMode.LIST_ZOOMED
                else -> null
            }
        }
    }

    // ==================== Layout ====================

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        
        // Don't call measureChildren() - PowerListView manages its own layout.
        // Children are measured in performLayout() via measureCell() with
        // UNSPECIFIED height to respect wrap_content/minHeight.
        // Calling measureChildren() here would re-measure them with the parent's
        // EXACTLY spec, overriding the correct measurements.
        
        setMeasuredDimension(width, height)
    }
    
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l
        val height = b - t

        if (width <= 0 || height <= 0) return

        // Update layout dimensions
        val padding = Rect(paddingLeft, paddingTop, paddingRight, paddingBottom)
        layoutState.updateDimensions(width, height, padding)
        (layoutState.getActiveEngine() as? GridEngine)?.let { applyPowerampGridParams(it) }
        (layoutState.getBackupEngine() as? GridEngine)?.let { applyPowerampGridParams(it) }

        // During zoom (pinch or snap animation), preserve the zoom-set row height.
        // Without this, performLayout() resets actualRowHeight to 0, causing maxScrollY = 0
        // and scroll position to jump to the top.
        if (isPinching || isGridPinching || listZoomManager.isSnapAnimating || columnTransitionActive) {
            val engine = layoutState.getActiveEngine() as? GridListEngine
            engine?.preserveRowHeightForZoom = true
        }

        // Perform layout
        layoutState.refreshLayout(true, true)
    }
    
    // Poweramp PowerList: empty onDraw — all rendering via child dispatchDraw.
    // Combined with hasOverlappingRendering=false, system skips offscreen buffer allocation.
    override fun onDraw(canvas: Canvas) {
    }

    // Poweramp PowerList + FastLayout: disable offscreen buffer for overlapping rendering.
    // Child views don't overlap during normal operation, saving one full-size Bitmap allocation.
    override fun hasOverlappingRendering(): Boolean = false

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(zoomUpdateRunnable)
        zoomUpdateScheduled = false
        pendingZoomParams = null
        listZoomManager.destroy()
    }

    // ==================== Touch Handling ====================

    /**
     * Safety net: clear pinch state on new gesture start.
     * Only reset on DOWN — UP/CANCEL cleanup is handled in onTouchEvent
     * where pinch exit transitions (requestDisallowInterceptTouchEvent, etc.) execute properly.
     */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // New gesture starts — cancel any running snap animation.
                // cancelSnap() sets currentLevel to nearest level and invokes
                // onZoomLevelSnapped → finishSnapTransition(), which properly
                // confirms the dual-engine transition and re-layouts.
                isPinching = false
                if (listZoomManager.isSnapAnimating) {
                    listZoomManager.cancelSnap()
                }
            }
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (currentColumns > 1 && handleGridPinchTouch(event)) return true

        // ── Zoom processing: sole entry point after intercept consumed POINTER_DOWN ──
        if (currentColumns == 1) {
            val consumed = listZoomManager.onTouchEvent(event)

            // Track pinch state transitions
            if (listZoomManager.isPinching && !isPinching) {
                // Entering pinch
                isPinching = true
                isTouchScrolling = false
                isFlinging = false
                scroller.forceFinished(true)
                parent?.requestDisallowInterceptTouchEvent(true)
            } else if (!listZoomManager.isPinching && isPinching) {
                // Exiting pinch
                isPinching = false
                parent?.requestDisallowInterceptTouchEvent(false)
                // Apply the last pending zoom update synchronously before the snap
                // animator starts. This prevents a 1-frame layout flash caused by
                // the gap between the cancelled MOVE update and the first snap frame.
                pendingZoomParams = null
                zoomUpdateScheduled = false
                removeCallbacks(zoomUpdateRunnable)
                // Don't start fade-in here — wait until snap animation completes
                // (onZoomLevelSnapped callback) so new items appear during snap too.
                // Reinitialize scroll tracking from current position
                if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) {
                    val remainIdx = if (event.actionIndex == 0) 1 else 0
                    lastTouchY = event.getY(remainIdx)
                } else {
                    lastTouchY = event.y
                }
                isTouchScrolling = false
            }

            if (consumed) return true
            if (isPinching) return true
        }

        if (isPinching) return true

        // Block scroll handling while snap animation is running after pinch release.
        // Without this, MOVE events during snap would trigger handleTouchMove scrolling,
        // causing the list to jitter and scroll unexpectedly.
        if (listZoomManager.isSnapAnimating) return true

        // ── Scroll handling ──
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { handleTouchDown(event) }
            MotionEvent.ACTION_MOVE -> { handleTouchMove(event) }
            MotionEvent.ACTION_UP -> { handleTouchUp(event) }
            MotionEvent.ACTION_CANCEL -> { handleTouchCancel() }
        }
        return true
    }

    private fun beginGridPinch(event: MotionEvent) {
        if (event.pointerCount < 2) return
        gridPinchPointerId1 = event.getPointerId(0)
        gridPinchPointerId2 = event.getPointerId(1)
        gridPinchBaseDistance = distance(event, 0, 1).coerceAtLeast(1f)
        gridPinchLastRatio = 1f
        gridPinchLastEventTime = event.eventTime
        gridPinchVelocityDp = 0f
        gridPinchRatioVelocity = 0f
        gridPinchTargetMode = null
        gridPinchIsZoomIn = false
        gridPinchProgress = 0f
        gridBoundaryElasticActive = false
        boundaryElasticAnimator?.cancel()
        boundaryElasticScale = 1f
        isGridPinching = true
        isTouchScrolling = false
        isFlinging = false
        columnTransitionAnimator?.cancel()
        scroller.forceFinished(true)
        parent?.requestDisallowInterceptTouchEvent(true)
    }

    private fun handleGridPinchTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 2) beginGridPinch(event)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isGridPinching || event.pointerCount < 2) return false
                return updateGridPinch(event)
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (isGridPinching) {
                    val upId = event.getPointerId(event.actionIndex)
                    if (upId == gridPinchPointerId1 || upId == gridPinchPointerId2) {
                        finishGridPinch()
                        return true
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isGridPinching) {
                    finishGridPinch()
                    return true
                }
            }
        }
        return isGridPinching
    }

    private fun updateGridPinch(event: MotionEvent): Boolean {
        val idx1 = event.findPointerIndex(gridPinchPointerId1)
        val idx2 = event.findPointerIndex(gridPinchPointerId2)
        if (idx1 < 0 || idx2 < 0) return false

        val currentDistance = distance(event, idx1, idx2).coerceAtLeast(1f)
        val ratio = currentDistance / gridPinchBaseDistance.coerceAtLeast(1f)
        val rawDelta = ratio - 1f
        val dt = (event.eventTime - gridPinchLastEventTime).coerceAtLeast(1L)
        gridPinchVelocityDp = ((ratio - gridPinchLastRatio) * gridPinchBaseDistance / context.resources.displayMetrics.density) / (dt / 1000f)
        gridPinchRatioVelocity = (ratio - gridPinchLastRatio) / (dt / 1000f)
        gridPinchLastRatio = ratio
        gridPinchLastEventTime = event.eventTime

        if (gridPinchTargetMode == null) {
            if (abs(rawDelta * gridPinchBaseDistance) < touchSlop) return true
            val isZoomIn = rawDelta > 0f
            val target = nextModeForGridPinch(isZoomIn)
            if (target == null) {
                gridBoundaryElasticActive = currentDisplayMode == PowerListDisplayMode.GRID_2 && isZoomIn
                gridPinchIsZoomIn = isZoomIn
                if (!gridBoundaryElasticActive) return true
            } else {
                gridPinchTargetMode = target
                gridPinchIsZoomIn = isZoomIn
                startColumnZoomTransition(target, isZoomIn)
            }
        }

        if (gridBoundaryElasticActive) {
            val scale = computeBoundaryElasticScale(abs(rawDelta), expands = gridPinchIsZoomIn)
            applyBoundaryElasticScale(scale)
            return true
        }

        val signedDelta = if (gridPinchIsZoomIn) rawDelta else -rawDelta
        gridPinchProgress = signedDelta.coerceIn(0f, 1f)
        updateColumnZoomTransition(gridPinchProgress, 1f)
        return true
    }

    private fun finishGridPinch() {
        if (gridBoundaryElasticActive) {
            val startScale = boundaryElasticScale
            isGridPinching = false
            gridBoundaryElasticActive = false
            gridPinchTargetMode = null
            parent?.requestDisallowInterceptTouchEvent(false)
            animateBoundaryElasticBack(startScale)
            return
        }

        val confirm = if (abs(gridPinchVelocityDp) >= 500f) {
            (gridPinchVelocityDp > 0f) == gridPinchIsZoomIn
        } else {
            gridPinchProgress > 0.3f
        }
        val velocityTowardEnd = (gridPinchVelocityDp > 0f) == gridPinchIsZoomIn
        val releaseProgressVelocity = when {
            confirm && velocityTowardEnd && gridPinchProgress > 0.8f -> abs(gridPinchRatioVelocity)
            !confirm && !velocityTowardEnd && gridPinchProgress < 0.2f -> -abs(gridPinchRatioVelocity)
            else -> 0f
        }
        isGridPinching = false
        gridPinchTargetMode = null
        parent?.requestDisallowInterceptTouchEvent(false)
        finishColumnZoomTransition(confirm, releaseProgressVelocity)
    }

    private fun applyBoundaryElasticScale(scale: Float) {
        boundaryElasticScale = scale.coerceIn(0.85f, 1.15f)
        if (layoutState.transitionState == LayoutState.STATE_DUAL_ENGINE_TRANSITION) return

        for (position in firstVisiblePosition..lastVisiblePosition) {
            val view = layoutState.viewPool[position]?.view ?: continue
            if (view.visibility != View.VISIBLE) continue
            view.scaleX = boundaryElasticScale
            view.scaleY = boundaryElasticScale
        }
        layoutState.getGridHeaderHolderIfPresent()?.view?.let { header ->
            if (header.visibility == View.VISIBLE) {
                header.scaleX = boundaryElasticScale
                header.scaleY = boundaryElasticScale
            }
        }
        invalidate()
    }

    private fun animateBoundaryElasticBack(startScale: Float) {
        val start = startScale.coerceIn(0.85f, 1.15f)
        if (abs(start - 1f) < 0.001f) {
            applyBoundaryElasticScale(1f)
            return
        }
        boundaryElasticAnimator?.cancel()
        boundaryElasticAnimator = ValueAnimator.ofFloat(start, 1f).apply {
            duration = 350L
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                applyBoundaryElasticScale(anim.animatedValue as Float)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    boundaryElasticScale = 1f
                    boundaryElasticAnimator = null
                    applyBoundaryElasticScale(1f)
                }
            })
            start()
        }
    }

    private fun computeBoundaryElasticScale(overPull: Float, expands: Boolean): Float {
        val eased = abs(powerampElasticEasing(overPull))
        return (if (expands) 1f + eased else 1f - eased).coerceIn(0.85f, 1.15f)
    }

    private fun powerampElasticEasing(d: Float): Float {
        return when {
            d > 0f -> {
                val clamped = (d.toDouble().coerceAtMost(3.0) / 3.0).coerceIn(0.0, 1.0)
                (sqrt(clamped * 0.2) * 0.2).toFloat()
            }
            d < 0f -> {
                val mapped = (1.0 - (d.toDouble() + 1.0).coerceIn(0.1, 1.0)) / 0.9
                -(sqrt(mapped.coerceIn(0.0, 1.0) * 0.2) * 0.2).toFloat()
            }
            else -> 0f
        }
    }

    private fun distance(event: MotionEvent, idx1: Int, idx2: Int): Float {
        if (event.pointerCount <= max(idx1, idx2)) return 1f
        val dx = event.getX(idx2) - event.getX(idx1)
        val dy = event.getY(idx2) - event.getY(idx1)
        return sqrt(dx * dx + dy * dy)
    }

    /**
     * Detect and intercept gestures. Does NOT process zoom — that's onTouchEvent's job.
     *
     * Key Android behavior:
     * - When we return true, the current event is used to send CANCEL to children
     *   and mFirstTouchTarget is cleared. This event does NOT reach onTouchEvent.
     * - Subsequent events skip onInterceptTouchEvent (mFirstTouchTarget==null)
     *   and go directly to onTouchEvent.
     *
     * So: we forward POINTER_DOWN to zoom manager here (since it won't reach onTouchEvent),
     * then intercept. All subsequent MOVE/POINTER_UP/UP go to onTouchEvent.
     */
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        // Already pinching: intercept everything → route to onTouchEvent
        if (isPinching) return true
        // Snap animation running: block scroll intercept to prevent jitter
        if (listZoomManager.isSnapAnimating) return true

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchY = event.y
                isTouchScrolling = false
                scroller.forceFinished(true)
                isFlinging = false
                if (velocityTracker == null) {
                    velocityTracker = VelocityTracker.obtain()
                }
                velocityTracker?.addMovement(event)
                return false // Let child handle click
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // Second finger: forward to zoom manager for pinch setup,
                // then intercept. This event won't reach onTouchEvent.
                if (currentColumns == 1 && event.pointerCount == 2) {
                    listZoomManager.onTouchEvent(event)
                    if (listZoomManager.isPinching) {
                        isPinching = true
                        isTouchScrolling = false
                        isFlinging = false
                        scroller.forceFinished(true)
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return true // Intercept: children get CANCEL
                    }
                } else if (currentColumns > 1 && event.pointerCount == 2) {
                    beginGridPinch(event)
                    return true
                }
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                // Safety net: detect 2-finger pinch if POINTER_DOWN was missed
                if (currentColumns == 1 && event.pointerCount >= 2 && !isPinching) {
                    listZoomManager.onTouchEvent(event)
                    if (listZoomManager.isPinching) {
                        isPinching = true
                        isTouchScrolling = false
                        isFlinging = false
                        scroller.forceFinished(true)
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                } else if (currentColumns > 1 && event.pointerCount >= 2 && !isGridPinching) {
                    beginGridPinch(event)
                    return true
                }

                velocityTracker?.addMovement(event)
                val deltaY = event.y - lastTouchY
                if (abs(deltaY) > touchSlop) {
                    if (scrollY <= 0 && deltaY > 0) {
                        return false // Let SwipeRefreshLayout handle refresh
                    }
                    isTouchScrolling = true
                    // Poweramp X keeps the movement beyond touchSlop by advancing the
                    // anchor by slop instead of replacing it with the current pointer.
                    lastTouchY = if (deltaY > 0f) event.y - touchSlop else event.y + touchSlop
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
                return false
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isTouchScrolling) return true
                return false
            }
        }
        return false
    }
    
    private fun handleTouchDown(event: MotionEvent) {
        // Stop any existing fling
        scroller.forceFinished(true)
        isFlinging = false
        
        // Start tracking velocity
        if (velocityTracker == null) {
            velocityTracker = VelocityTracker.obtain()
        }
        velocityTracker?.addMovement(event)
        
        lastTouchY = event.y
        isTouchScrolling = false
    }
    
    private fun handleTouchMove(event: MotionEvent) {
        velocityTracker?.addMovement(event)
        
        val deltaY = lastTouchY - event.y
        
        // Scroll
        scrollBy(0, deltaY.toInt())
        lastTouchY = event.y
    }
    
    private fun handleTouchUp(event: MotionEvent) {
        velocityTracker?.addMovement(event)
        velocityTracker?.computeCurrentVelocity(1000, maximumFlingVelocity.toFloat())

        val velocityY = velocityTracker?.yVelocity ?: 0f

        if (isTouchScrolling && abs(velocityY) > minimumFlingVelocity) {
            // Start fling
            isFlinging = true
            scroller.fling(
                0, scrollY,
                0, -velocityY.toInt(),
                0, 0,
                0, maxScrollY
            )
            invalidate()
        }

        isTouchScrolling = false
        parent?.requestDisallowInterceptTouchEvent(false)
        velocityTracker?.recycle()
        velocityTracker = null
    }

    private fun handleTouchCancel() {
        // Poweramp X clears velocity on ACTION_CANCEL and finishes with zero velocity.
        isTouchScrolling = false
        isFlinging = false
        scroller.forceFinished(true)
        parent?.requestDisallowInterceptTouchEvent(false)
        velocityTracker?.clear()
        velocityTracker?.recycle()
        velocityTracker = null
    }
    
    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            val newScrollY = scroller.currY
            scrollTo(0, newScrollY)
            invalidate()
        } else if (isFlinging) {
            isFlinging = false
        }
    }
    
    // ==================== Scrolling ====================
    
    override fun scrollTo(x: Int, y: Int) {
        val clampedY = max(0, min(y, maxScrollY))
        if (scrollY != clampedY) {
            val oldScrollY = scrollY
            scrollY = clampedY
            
            // Update layout engines scroll offset
            layoutState.setScrollOffset(0.0, scrollY.toDouble())
            
            // Always refresh layout to create/recycle views as needed
            // performLayout() will skip re-measuring views that are already positioned
            layoutState.refreshLayout(true, false)
            
            // Notify listeners
            onScrollChanged(0, clampedY, 0, clampedY - oldScrollY)
        }
    }
    
    override fun scrollBy(x: Int, y: Int) {
        scrollTo(0, scrollY + y)
    }
    
    /**
     * Tells the framework whether this view can scroll vertically.
     * SwipeRefreshLayout uses this to decide whether to intercept touch events:
     * - direction < 0 (scroll up / finger moves down): can scroll if not at top
     * - direction > 0 (scroll down / finger moves up): can scroll if not at bottom
     * 
     * Returning true means "I can still scroll, don't intercept me".
     */
    override fun shouldDelayChildPressedState(): Boolean = true

    override fun canScrollVertically(direction: Int): Boolean {
        // During zoom (pinch or snap animation), always report "can scroll" to prevent
        // SwipeRefreshLayout from intercepting and triggering a refresh.
        if (isPinching || isGridPinching || columnTransitionActive || listZoomManager.isSnapAnimating) return true

        if (direction < 0) {
            // Can scroll up (content moves down) if not at the top
            return scrollY > 0
        } else if (direction > 0) {
            // Can scroll down (content moves up) if not at the bottom
            return scrollY < maxScrollY
        }
        return false
    }
    
    // ==================== Item Management ====================
    
    /**
     * Sets the data provider for this PowerList.
     * @param provider Data provider
     */
    fun setDataProvider(provider: PowerListDataProvider?) {
        dataProvider = provider
        itemCount = provider?.getItemCount() ?: 0
        ensureSurfaceVisibleForItems()
        requestLayout()
    }
    
    /**
     * Gets the current data provider.
     * @return Current data provider, or null
     */
    fun getDataProvider(): PowerListDataProvider? {
        return dataProvider
    }
    
    /**
     * Sets the item click listener.
     * @param listener Click listener
     */
    fun setOnItemClickListener(listener: OnItemClickListener?) {
        itemClickListener = listener
    }
    
    /**
     * Sets the item long click listener.
     * @param listener Long click listener
     */
    fun setOnItemLongClickListener(listener: OnItemLongClickListener?) {
        itemLongClickListener = listener
    }
    
    /**
     * Sets the column count change listener.
     * @param listener Column count change listener
     */
    fun setOnColumnCountChangeListener(listener: OnColumnCountChangeListener?) {
        columnCountChangeListener = listener
    }
    
    /**
     * Creates a view for the given position and binds data to it.
     * @param position Item position
     * @return Created view, or null
     */
    fun createViewForPosition(position: Int): View? {
        val provider = dataProvider ?: return null
        val view = provider.createView(position, this) ?: return null
        installItemListeners(view)
        addView(view)
        return view
    }

    internal fun createGridHeaderView(): View? {
        val provider = dataProvider ?: return null
        val view = provider.createGridHeaderView(this) ?: return null
        addView(view)
        return view
    }

    internal fun bindGridHeaderView(view: View) {
        dataProvider?.bindGridHeaderView(view)
    }

    private fun installItemListeners(view: View) {
        // Resolve holder at click time like Poweramp's PowerList.i(view),
        // so recycled views report their current position instead of the creation position.
        view.setOnClickListener { clicked ->
            val holder = layoutState.findViewHolderForView(clicked) ?: return@setOnClickListener
            if (holder.isOutOfView || holder.position < 0) return@setOnClickListener
            itemClickListener?.onItemClick(holder.position, clicked)
        }
        view.setOnLongClickListener { clicked ->
            val holder = layoutState.findViewHolderForView(clicked) ?: return@setOnLongClickListener false
            if (holder.isOutOfView || holder.position < 0) return@setOnLongClickListener false
            itemLongClickListener?.onItemLongClick(holder.position, clicked) ?: false
        }
    }
    
    /**
     * Updates visible items using ViewPositioner (1:1 with Poweramp's m1209).
     *
     * Applies slot 0 directly with dirty-bit optimization.
     *
     * @param firstVisible First visible position (optional)
     * @param lastVisible Last visible position (optional)
     */
    fun updateVisibleItems(firstVisible: Int = firstVisiblePosition, lastVisible: Int = lastVisiblePosition) {
        ensureSurfaceVisibleForItems()
        firstVisiblePosition = firstVisible
        lastVisiblePosition = lastVisible

        val engine = layoutState.getActiveEngine() ?: return
        val isDualEngine = layoutState.transitionState == LayoutState.STATE_DUAL_ENGINE_TRANSITION
        val progress = layoutState.transitionProgress
        val mainSlot = layoutState.mainSlot
        val backupSlot = layoutState.backupSlot

        if (!isDualEngine) {
            if (engine is GridEngine) {
                renderNormalHeaderHolder(mainSlot)
            } else {
                layoutState.hideGridHeaderHolder()
            }
        }

        for (position in firstVisible..lastVisible) {
            val holder = layoutState.viewPool[position] ?: continue
            if (holder.isRecycled) continue
            val view = holder.view ?: continue

            val posData = holder.getPositionData(mainSlot)
            val hasSlot0 = posData != null && !posData.isTranslationZero()

            if (isDualEngine) {
                // ═══════════════════════════════════════════════════════════════
                // m1209 Path A: Dual-slot interpolation
                // Both engines wrote to slots 0 and 1 on the SAME ViewHolder.
                // Slot 0 = main engine (target zoom level)
                // Slot 1 = backup engine (source zoom level)
                // W() set scale=C, alpha=1.0 on both slots.
                // The zoom effect comes from lerping between DIFFERENT POSITIONS
                // (different zoom params → different item sizes → different positions).
                // ═══════════════════════════════════════════════════════════════
                val backupPos = holder.getPositionData(backupSlot)
                val hasSlot1 = backupPos != null && !backupPos.isTranslationZero()

                if (hasSlot0 && hasSlot1) {
                    // Both slots valid → lerp (m1209 Path A)
                    // isTargetPrimary=false: f2=1-progress
                    // At progress=0: f2=1 → shows slot 1 (source) ✓
                    // At progress=1: f2=0 → shows slot 0 (target) ✓
                    ViewPositioner.applyPowerampTransform(
                        holder,
                        f = progress,
                        z = true,
                        slot = mainSlot,
                        engine = engine as? GridListEngine,
                        z2 = true,
                        z3 = true,
                        otherSlot = backupSlot
                    )
                    view.visibility = View.VISIBLE
                } else if (hasSlot0) {
                    // Only target slot
                    ViewPositioner.applyPowerampTransform(
                        holder,
                        f = progress,
                        z = true,
                        slot = mainSlot,
                        engine = engine as? GridListEngine,
                        z2 = true,
                        z3 = false,
                        otherSlot = backupSlot
                    )
                    view.visibility = View.VISIBLE
                } else if (hasSlot1) {
                    // Only source slot
                    ViewPositioner.applyPowerampTransform(
                        holder,
                        f = progress,
                        z = true,
                        slot = backupSlot,
                        engine = layoutState.getBackupEngine() as? GridListEngine,
                        z2 = false,
                        z3 = false,
                        otherSlot = mainSlot
                    )
                    view.visibility = View.VISIBLE
                } else {
                    view.visibility = View.INVISIBLE
                }
            } else {
                // Normal (non-transition): m1209 Path B with single slot
                if (hasSlot0) {
                    ViewPositioner.applyItemTransform(
                        holder, 0f, false, mainSlot,
                        interpolateSlots = false,
                        isTargetPrimary = true
                    )
                    view.visibility = View.VISIBLE
                } else {
                    view.visibility = View.INVISIBLE
                }
            }

            // Outside dual-engine transitions, keep item scenes in sync with the
            // active layout. During transitions, ViewPositioner drives Poweramp's
            // B.m1209() -> C.G0()/mo2931() path, so forcing a scene here would
            // reset the item's internal scene interpolation.
            if (!isDualEngine) {
                val expectedScene = if (currentColumns > 1) {
                    PowerListSceneItem.SCENE_GRID
                } else {
                    resolveSceneIdForZoomParams(listZoomManager.currentParams)
                }
                if (view is AAItemView) {
                    if (view.currentScene != expectedScene) {
                        view.currentScene = expectedScene
                        holder.currentScene = expectedScene
                        dataProvider?.bindView(position, view)
                    }
                    if (currentColumns == 1) {
                        view.suppressZoomLayoutRequest = true
                        listZoomManager.applyToItem(view)
                        view.suppressZoomLayoutRequest = false
                    }
                } else {
                    if (holder.currentScene != expectedScene) {
                        (view as? ConstraintLayout)?.let { root ->
                            SceneHelper.applyScene(root, currentColumns, listZoomManager.currentParams)
                            holder.currentScene = expectedScene
                        }
                    }
                }
            }
        }

        hideNormalOutOfRangeViews(firstVisible, lastVisible)
        recycleOffscreenViews(firstVisible, lastVisible)
    }

    private fun renderNormalHeaderHolder(slot: Int) {
        val holder = layoutState.getGridHeaderHolderIfPresent() ?: return
        val view = holder.view ?: return
        val pos = holder.getPositionData(slot)
        if (pos != null && !pos.isTranslationZero()) {
            ViewPositioner.applyItemTransform(
                holder = holder,
                f = 0f,
                z = false,
                slot = slot,
                interpolateSlots = false,
                isTargetPrimary = true
            )
            view.visibility = View.VISIBLE
        } else {
            view.visibility = View.INVISIBLE
        }
    }

    private fun hideNormalOutOfRangeViews(firstVisible: Int, lastVisible: Int) {
        for (holder in layoutState.viewPool.getAll()) {
            val position = holder.position
            if (position < firstVisible || position > lastVisible) {
                holder.view?.let { view ->
                    view.alpha = 1f
                    view.scaleX = 1f
                    view.scaleY = 1f
                    view.visibility = View.INVISIBLE
                }
                holder.invalidatePositions()
            }
        }
    }

    /**
     * Transition rendering for the shared-view pool.
     *
     * Poweramp renders two physical container arrays (target pass, then source pass).
     * RawSMusic stores both container slots on the same ViewHolder, so rendering both
     * passes onto the same View reverses/overwrites the final transform. Resolve each
     * holder once: source slot -> target slot for overlap, source-only fades out,
     * target-only fades in.
     */
    fun updateTransitionVisibleItems(
        mainFirst: Int,
        mainLast: Int,
        backupFirst: Int,
        backupLast: Int,
        mainActiveFirst: Int,
        mainActiveLast: Int,
        backupActiveFirst: Int,
        backupActiveLast: Int,
        mainLayoutFirst: Int,
        mainLayoutLast: Int,
        backupLayoutFirst: Int,
        backupLayoutLast: Int
    ) {
        ensureSurfaceVisibleForItems()
        firstVisiblePosition = minOf(mainLayoutFirst, backupLayoutFirst)
        lastVisiblePosition = maxOf(mainLayoutLast, backupLayoutLast)

        val mainEngine = layoutState.getActiveEngine() as? GridListEngine
        val backupEngine = layoutState.getBackupEngine() as? GridListEngine
        val progress = layoutState.transitionProgress
        val mainSlot = layoutState.mainSlot
        val backupSlot = layoutState.backupSlot
        val transitionDirection = if (columnTransitionActive) columnTransitionPowerampDirection else activeTransitionDirection

        renderTransitionHeaderHolder(mainEngine, backupEngine, progress, mainSlot, backupSlot, transitionDirection)

        renderMergedTransitionItems(
            mainFirst = mainFirst,
            mainLast = mainLast,
            backupFirst = backupFirst,
            backupLast = backupLast,
            mainActiveFirst = mainActiveFirst,
            mainActiveLast = mainActiveLast,
            backupActiveFirst = backupActiveFirst,
            backupActiveLast = backupActiveLast,
            mainSlot = mainSlot,
            backupSlot = backupSlot,
            mainEngine = mainEngine,
            backupEngine = backupEngine,
            progress = progress,
            transitionDirection = transitionDirection
        )
        hideTransitionOutOfRangeViews(mainFirst, mainLast, backupFirst, backupLast)
    }

    private fun renderTransitionHeaderHolder(
        mainEngine: GridListEngine?,
        backupEngine: GridListEngine?,
        progress: Float,
        mainSlot: Int,
        backupSlot: Int,
        transitionDirection: Boolean
    ) {
        val holder = layoutState.getGridHeaderHolderIfPresent() ?: return
        val view = holder.view ?: return
        val hasMain = holder.getPositionData(mainSlot)?.let { !it.isTranslationZero() } == true
        val hasBackup = holder.getPositionData(backupSlot)?.let { !it.isTranslationZero() } == true
        if (!hasMain && !hasBackup) {
            view.visibility = View.INVISIBLE
            return
        }

        when {
            hasMain && hasBackup -> {
                ViewPositioner.applyPowerampTransform(
                    holder,
                    f = progress,
                    z = transitionDirection,
                    slot = backupSlot,
                    engine = backupEngine,
                    z2 = true,
                    z3 = true,
                    otherSlot = mainSlot
                )
                view.visibility = View.VISIBLE
            }
            hasBackup -> {
                ViewPositioner.applyPowerampTransform(
                    holder,
                    f = progress,
                    z = transitionDirection,
                    slot = backupSlot,
                    engine = backupEngine,
                    z2 = true,
                    z3 = false,
                    otherSlot = mainSlot
                )
                view.visibility = View.VISIBLE
            }
            hasMain -> {
                ViewPositioner.applyPowerampTransform(
                    holder,
                    f = progress,
                    z = transitionDirection,
                    slot = mainSlot,
                    engine = mainEngine,
                    z2 = false,
                    z3 = false,
                    otherSlot = backupSlot
                )
                view.visibility = View.VISIBLE
            }
        }
    }

    private fun renderMergedTransitionItems(
        mainFirst: Int,
        mainLast: Int,
        backupFirst: Int,
        backupLast: Int,
        mainActiveFirst: Int,
        mainActiveLast: Int,
        backupActiveFirst: Int,
        backupActiveLast: Int,
        mainSlot: Int,
        backupSlot: Int,
        mainEngine: GridListEngine?,
        backupEngine: GridListEngine?,
        progress: Float,
        transitionDirection: Boolean
    ) {
        val first = minOf(mainFirst, backupFirst)
        val last = maxOf(mainLast, backupLast)
        if (first > last) return

        for (position in first..last) {
            val holder = layoutState.viewPool[position] ?: continue
            if (holder.isRecycled) continue
            val view = holder.view ?: continue
            val mainPos = holder.getPositionData(mainSlot)
            val backupPos = holder.getPositionData(backupSlot)
            val hasMainLayout = mainPos != null && !mainPos.isTranslationZero() && position in mainFirst..mainLast
            val hasBackupLayout = backupPos != null && !backupPos.isTranslationZero() && position in backupFirst..backupLast

            if (!hasMainLayout && !hasBackupLayout) {
                view.alpha = 1f
                view.scaleX = 1f
                view.scaleY = 1f
                view.visibility = View.INVISIBLE
                continue
            }

            val mainRenderable = if (!hasMainLayout) {
                false
            } else if (!columnTransitionActive || !activeTransitionUseStrictOverlap) {
                true
            } else {
                position in mainActiveFirst..mainActiveLast || !hasBackupLayout
            }
            val backupRenderable = if (!hasBackupLayout) {
                false
            } else if (!columnTransitionActive || !activeTransitionUseStrictOverlap) {
                true
            } else {
                position in backupActiveFirst..backupActiveLast || !hasMainLayout
            }

            when {
                mainRenderable && backupRenderable -> {
                    ViewPositioner.applyPowerampTransform(
                        holder,
                        f = progress,
                        z = transitionDirection,
                        slot = backupSlot,
                        engine = backupEngine,
                        z2 = true,
                        z3 = true,
                        otherSlot = mainSlot
                    )
                    view.visibility = View.VISIBLE
                }
                backupRenderable -> {
                    ViewPositioner.applyPowerampTransform(
                        holder,
                        f = progress,
                        z = transitionDirection,
                        slot = backupSlot,
                        engine = backupEngine,
                        z2 = true,
                        z3 = false,
                        otherSlot = mainSlot
                    )
                    view.visibility = View.VISIBLE
                }
                mainRenderable -> {
                    ViewPositioner.applyPowerampTransform(
                        holder,
                        f = progress,
                        z = transitionDirection,
                        slot = mainSlot,
                        engine = mainEngine,
                        z2 = false,
                        z3 = false,
                        otherSlot = backupSlot
                    )
                    view.visibility = View.VISIBLE
                }
                else -> {
                    view.alpha = 1f
                    view.scaleX = 1f
                    view.scaleY = 1f
                    view.visibility = View.INVISIBLE
                }
            }
        }
    }

    private fun hideTransitionOutOfRangeViews(
        mainFirst: Int,
        mainLast: Int,
        backupFirst: Int,
        backupLast: Int
    ) {
        for (holder in layoutState.viewPool.getAll()) {
            val position = holder.position
            val inMain = position in mainFirst..mainLast
            val inBackup = position in backupFirst..backupLast
            if (!inMain && !inBackup) {
                holder.view?.visibility = View.INVISIBLE
                holder.invalidatePositions()
            }
        }
    }


    
    fun beginSceneReturnTransition(spec: SceneReturnSpec): Boolean {
        if (layoutState.transitionState == LayoutState.STATE_DUAL_ENGINE_TRANSITION) return false
        val sourceEngine = layoutState.getActiveEngine() as? GridListEngine ?: return false
        if (itemCount <= 0 || width <= 0 || height <= 0) return false
        sceneReturnAnimator?.cancel()
        removeCallbacks(sceneReturnVelocityRunnable)

        val local = RectF(spec.targetRectInWindow)
        val loc = IntArray(2)
        getLocationInWindow(loc)
        local.offset(-loc[0].toFloat(), -loc[1].toFloat())
        if (local.width() <= 1f || local.height() <= 1f) {
            val fallbackW = (96f * context.resources.displayMetrics.density).coerceAtLeast(1f)
            local.set(
                (width - fallbackW) * 0.5f,
                paddingTop.toFloat(),
                (width + fallbackW) * 0.5f,
                paddingTop + fallbackW
            )
        }

        sceneReturnSourceScrollY = scrollY
        sceneReturnSwipeRight = spec.swipeRight
        sceneReturnInitialTouchX = spec.initialTouchX
        sceneReturnProgress = 0f
        activePowerListTransition = ActivePowerListTransition.SCENE_RETURN
        activeTransitionDirection = false
        activeTransitionUseStrictOverlap = false

        val sourceRange = sourceEngine.getVisibleItemRange(itemCount)
        layoutState.swapContainers()
        val targetEngine = SceneReturnFadeEngine(
            sourceFirst = sourceRange.first,
            sourceLast = sourceRange.second,
            targetSceneId = sceneForDisplayMode(currentDisplayMode),
            densityValue = context.resources.displayMetrics.density,
            source = sourceEngine,
            sourceSlot = layoutState.backupSlot,
            exitOffsetX = if (sceneReturnSwipeRight) width / 3 else -width / 3
        )
        targetEngine.setDimensions(
            sourceEngine.bounds.width(), sourceEngine.bounds.height(),
            sourceEngine.padding.left, sourceEngine.padding.top,
            sourceEngine.padding.right, sourceEngine.padding.bottom
        )
        targetEngine.copyLayoutMetricsFrom(sourceEngine)
        targetEngine.positionSlot = layoutState.mainSlot
        targetEngine.setModeScale()
        targetEngine.transitionScaleFactor = 1f
        (layoutState.getBackupEngine() as? GridListEngine)?.let { backup ->
            backup.setModeScale()
            backup.transitionScaleFactor = 1f
        }
        layoutState.setMainEngine(targetEngine)
        layoutState.transitionProgress = 0f
        layoutState.setTransitionState(LayoutState.STATE_DUAL_ENGINE_TRANSITION)
        layoutState.refreshLayout(true, false)
        invalidate()
        return true
    }

    fun updateSceneReturnTransitionProgress(progress: Float) {
        if (!isSceneReturnTransitionActive || layoutState.transitionState != LayoutState.STATE_DUAL_ENGINE_TRANSITION) return
        sceneReturnProgress = progress.coerceIn(0f, 1f)
        layoutState.transitionProgress = sceneReturnProgress
        layoutState.refreshLayout(true, true)
        onSceneReturnProgressChanged?.invoke(sceneReturnProgress)
        invalidate()
    }

    fun updateSceneReturnTransitionDrag(currentTouchX: Float, currentTouchY: Float) {
        val widthPx = width.coerceAtLeast(1).toFloat()
        val rawDx = currentTouchX - sceneReturnInitialTouchX
        val directedDx = if (sceneReturnSwipeRight) rawDx else -rawDx
        updateSceneReturnTransitionProgress((directedDx / widthPx).coerceIn(0f, 1f))
    }

    fun finishSceneReturnTransition(
        requestedCommit: Boolean? = null,
        velocityPxPerSecond: Float = 0f,
        onFinished: (committed: Boolean) -> Unit
    ) {
        if (!isSceneReturnTransitionActive) {
            onFinished(false)
            return
        }
        val density = context.resources.displayMetrics.density.coerceAtLeast(0.001f)
        val normalizedVelocity = if (sceneReturnSwipeRight) velocityPxPerSecond else -velocityPxPerSecond
        val velocityDp = normalizedVelocity / density
        val commit = requestedCommit ?: when {
            abs(velocityDp) >= RETURN_VELOCITY_THRESHOLD_DP -> velocityDp > 0f
            sceneReturnProgress > RETURN_PROGRESS_THRESHOLD -> true
            else -> false
        }
        val canVelocityHandoff = (commit && sceneReturnProgress > RETURN_COMMIT_VELOCITY_GATE && normalizedVelocity > 0f) ||
            (!commit && sceneReturnProgress < RETURN_ROLLBACK_VELOCITY_GATE && normalizedVelocity < 0f)
        if (canVelocityHandoff) {
            startSceneReturnVelocitySettle(commit, normalizedVelocity / width.coerceAtLeast(1).toFloat(), onFinished)
        } else {
            startSceneReturnAnimatedSettle(commit, onFinished)
        }
    }

    fun cancelSceneReturnTransition() {
        if (!isSceneReturnTransitionActive) return
        sceneReturnAnimator?.cancel()
        removeCallbacks(sceneReturnVelocityRunnable)
        completeSceneReturnTransition(false)
    }

    private fun startSceneReturnAnimatedSettle(commit: Boolean, onFinished: (Boolean) -> Unit) {
        val start = sceneReturnProgress
        val end = if (commit) 1f else 0f
        val base = if (commit) RETURN_COMMIT_DURATION_MS else RETURN_ROLLBACK_DURATION_MS
        val durationMs = (abs(end - start) * base).toLong().coerceIn(80L, base)
        sceneReturnAnimator?.cancel()
        sceneReturnAnimator = ValueAnimator.ofFloat(start, end).apply {
            duration = durationMs
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim -> updateSceneReturnTransitionProgress(anim.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) {
                    if (!cancelled) {
                        completeSceneReturnTransition(commit)
                        onFinished(commit)
                    }
                }
            })
            start()
        }
    }

    private var sceneReturnVelocityTargetCommit = false
    private var sceneReturnVelocityProgressPerSecond = 0f
    private var sceneReturnVelocityLastTime = 0L
    private var sceneReturnVelocityFinished: ((Boolean) -> Unit)? = null
    private val sceneReturnVelocityRunnable = object : Runnable {
        override fun run() {
            if (!isSceneReturnTransitionActive) return
            val now = android.os.SystemClock.uptimeMillis()
            val dt = ((now - sceneReturnVelocityLastTime).coerceAtLeast(1L)).toFloat() / 1000f
            sceneReturnVelocityLastTime = now
            val next = sceneReturnProgress + sceneReturnVelocityProgressPerSecond * dt
            if (sceneReturnVelocityTargetCommit) {
                updateSceneReturnTransitionProgress(next.coerceAtMost(1f))
                if (sceneReturnProgress >= 0.9999f) {
                    val cb = sceneReturnVelocityFinished
                    completeSceneReturnTransition(true)
                    cb?.invoke(true)
                    return
                }
            } else {
                updateSceneReturnTransitionProgress(next.coerceAtLeast(0f))
                if (sceneReturnProgress <= 0.0001f) {
                    val cb = sceneReturnVelocityFinished
                    completeSceneReturnTransition(false)
                    cb?.invoke(false)
                    return
                }
            }
            postOnAnimation(this)
        }
    }

    private fun startSceneReturnVelocitySettle(commit: Boolean, progressVelocity: Float, onFinished: (Boolean) -> Unit) {
        sceneReturnAnimator?.cancel()
        sceneReturnVelocityTargetCommit = commit
        val durationSeconds = (if (commit) RETURN_COMMIT_DURATION_MS else RETURN_ROLLBACK_DURATION_MS) / 1000f
        sceneReturnVelocityProgressPerSecond = if (commit) {
            progressVelocity.coerceIn(1f / durationSeconds, 8f)
        } else {
            progressVelocity.coerceIn(-8f, -3.5f)
        }
        sceneReturnVelocityFinished = onFinished
        sceneReturnVelocityLastTime = android.os.SystemClock.uptimeMillis()
        postOnAnimation(sceneReturnVelocityRunnable)
    }

    private fun completeSceneReturnTransition(committed: Boolean) {
        for (holder in layoutState.viewPool.getAll()) {
            (holder.view as? PowerListSceneItem)?.H(false)
        }
        layoutState.cancelTransition()
        activePowerListTransition = ActivePowerListTransition.NONE
        activeTransitionDirection = true
        activeTransitionUseStrictOverlap = false
        sceneReturnProgress = 0f
        onSceneReturnProgressChanged?.invoke(if (committed) 1f else 0f)
        sceneReturnAnimator = null
        sceneReturnVelocityFinished = null
        val engine = layoutState.getActiveEngine() as? GridListEngine
        engine?.resetTransition()
        scrollY = sceneReturnSourceScrollY.coerceAtLeast(0)
        layoutState.setScrollOffset(0.0, scrollY.toDouble())
        layoutState.refreshLayout(true, false)
        for (holder in layoutState.viewPool.getAll()) {
            val view = holder.view ?: continue
            view.alpha = 1f
            view.scaleX = 1f
            view.scaleY = 1f
            view.rotation = 0f
            view.rotationX = 0f
            view.rotationY = 0f
        }
        invalidate()
    }

    private class SceneReturnFadeEngine(
        private val sourceFirst: Int,
        private val sourceLast: Int,
        private val targetSceneId: Int,
        private val densityValue: Float,
        source: GridListEngine,
        private val sourceSlot: Int,
        private val exitOffsetX: Int
    ) : GridListEngine(source.cols, source.rowHeight, densityValue) {
        override fun positionItem(holder: ViewHolder, view: View, position: ItemPosition, sceneId: Int) {
            val sourcePosition = holder.getPositionData(sourceSlot)
            if (sourcePosition != null && !sourcePosition.isTranslationZero()) {
                position.copyFrom(sourcePosition)
                position.offset(exitOffsetX, 0)
            } else {
                positionItemFromGeometry(holder.position, position)
            }
            position.setAlpha(0f)
            position.setScaleX(0.92f)
            position.setScaleY(0.92f)
            position.H(targetSceneId)
        }

        override fun performLayout(
            layoutStatus: Int,
            firstVisible: Int,
            lastVisible: Int,
            itemCount: Int,
            stateManager: LayoutState
        ) {
            if (itemCount <= 0) return
            calculateColumnWidth()
            actualRowHeight = rowHeight.takeIf { it > 0 } ?: actualRowHeight.coerceAtLeast(1)
            totalRows = if (cols <= 1) itemCount else (itemCount + cols - 1) / cols
            val first = sourceFirst.coerceIn(0, itemCount - 1)
            val last = sourceLast.coerceIn(first, itemCount - 1)
            firstVisibleRow = positionToRow(first)
            lastVisibleRow = positionToRow(last)
            visibleRows = (lastVisibleRow - firstVisibleRow + 1).coerceAtLeast(1)
            val slotIndex = positionSlot
            for (positionIndex in first..last) {
                val holder = stateManager.getViewHolder(positionIndex) ?: continue
                val view = holder.view ?: continue
                val itemPos = holder.getPositionData(slotIndex) ?: ItemPosition().also {
                    holder.setPositionData(slotIndex, it)
                }
                positionItem(holder, view, itemPos, slotIndex)
            }
        }

        private fun positionItemFromGeometry(positionIndex: Int, out: ItemPosition) {
            val row = positionToRow(positionIndex)
            val col = positionToCol(positionIndex)
            val left = padding.left + col * colWidth + exitOffsetX
            val top = padding.top + row * getRowStrideForReturn()
            out.set(
                left,
                top,
                left + colWidth.coerceAtLeast(1),
                top + actualRowHeight.coerceAtLeast(1)
            )
        }

        private fun getRowStrideForReturn(): Int {
            return (getRowScrollOffset(1) - getRowScrollOffset(0)).coerceAtLeast(actualRowHeight.coerceAtLeast(1))
        }
    }

    /**
     * Update visible items for the backup (source) engine during dual-engine transition.
     * With shared ViewPool architecture, this is no longer needed — both engines write
     * to different slots on the SAME ViewHolder, and updateVisibleItems() reads both slots.
     * Kept as a no-op for compatibility with LayoutState.refreshLayout() calls.
     */
    fun updateBackupVisibleItems() {
        // No-op: shared ViewPool handles both engines' positions in updateVisibleItems()
    }
    
    /**
     * Checks if an item is on-screen (has pixels visible in the viewport).
     * @param posData Item position data
     * @return true if item intersects with viewport
     */
    /**
     * Clears all child view translation offsets for a holder.
     */
    private fun clearChildOffsets(view: View, holder: ViewHolder) {
        val parent = view as? ViewGroup ?: return
        // Reset all known child view transforms
        val ids = intArrayOf(R.id.aa_image, R.id.title, R.id.line2, R.id.meta, R.id.select_box)
        for (id in ids) {
            val child = parent.findViewById<View>(id) ?: continue
            child.translationX = 0f
            child.translationY = 0f
        }
    }

    /**
     * Recycles views that are no longer visible.
     * Uses position range with extra buffer + pixel-level viewport check as safety net.
     */
    private fun recycleOffscreenViews(firstVisible: Int, lastVisible: Int) {
        // Poweramp v0.m3023(true) marks holders INVISIBLE and clears slots; it does not
        // remove child views on normal scroll. ViewPool.setExactRange() already handles
        // out-of-range holders this way, so avoid remove/add churn in 4/3-column grids.
        if (layoutState.transitionState == LayoutState.STATE_DUAL_ENGINE_TRANSITION) return
    }
    
    // ==================== Dual-Engine Snap Transition ====================

    /**
     * Whether current snap transition is zoom-in (SMALL→NORMAL/ZOOMED)
     * Poweramp's a2.f4188 equivalent
     */
    private var snapIsZoomIn: Boolean = false

    /**
     * Source engine's scroll Y at the start of the transition.
     * Saved BEFORE scrollY is adjusted for the target level.
     * Used in finishSnapTransition to recalculate correct scrollY
     * when the final level differs from the initial target level.
     */
    private var transitionSourceScrollY: Int = 0

    /**
     * Source engine's row height at the start of the transition.
     * Used with transitionSourceScrollY to recalculate scrollY via ratio.
     */
    private var transitionSourceRowHeight: Int = 0

    /** Source viewport anchor used to preserve the same top item across zoom levels. */
    private var transitionAnchorPosition: Int = 0
    private var transitionAnchorOffset: Int = 0

    /** Source zoom level at the start of the transition. */
    private var transitionSourceLevel: ListZoomIndex = ListZoomIndex.NORMAL

    /** Target zoom level for the transition. */
    private var transitionTargetLevel: ListZoomIndex = ListZoomIndex.NORMAL

    private fun startColumnZoomTransition(targetMode: PowerListDisplayMode, isZoomIn: Boolean) {
        if (layoutState.transitionState == LayoutState.STATE_DUAL_ENGINE_TRANSITION) return
        val sourceEngine = layoutState.getActiveEngine() as? GridListEngine ?: return
        columnTransitionAnimator?.cancel()
        columnTransitionActive = true
        activePowerListTransition = ActivePowerListTransition.COLUMN_ZOOM
        activeTransitionUseStrictOverlap = false
        columnTransitionSourceMode = currentDisplayMode
        columnTransitionTargetMode = targetMode
        columnTransitionSourceScrollY = scrollY
        snapIsZoomIn = isZoomIn
        // Keep the same direction contract as Poweramp's zoom transition setup:
        // source and target engines receive opposite zoom bases, while shared-slot
        // interpolation moves from the backup/source slot to the main/target slot.
        columnTransitionPowerampDirection = isZoomIn
        activeTransitionDirection = columnTransitionPowerampDirection

        columnTransitionAnchorPosition = chooseColumnTransitionAnchorPosition(sourceEngine)
        val sourceAnchorRow = sourceEngine.positionToRow(columnTransitionAnchorPosition)
        val sourceRowStride = (sourceEngine.getRowScrollOffset(1) - sourceEngine.getRowScrollOffset(0)).coerceAtLeast(1)
        columnTransitionAnchorOffset = (sourceAnchorRow * sourceRowStride - columnTransitionSourceScrollY).coerceAtMost(height).coerceAtLeast(-height)

        removeCallbacks(zoomUpdateRunnable)
        zoomUpdateScheduled = false
        pendingZoomParams = null

        layoutState.swapContainers()

        val targetEngine = createEngineForDisplayMode(targetMode)
        targetEngine.setDimensions(
            sourceEngine.bounds.width(), sourceEngine.bounds.height(),
            sourceEngine.padding.left, sourceEngine.padding.top,
            sourceEngine.padding.right, sourceEngine.padding.bottom
        )
        targetEngine.copyLayoutMetricsFrom(sourceEngine)
        if (targetEngine is GridEngine) applyPowerampGridParams(targetEngine)
        if (targetEngine is ListEngine) {
            val params = targetMode.listLevel?.let { listZoomManager.paramsForLevel(it) } ?: listZoomManager.paramsForLevel(ListZoomIndex.ZOOMED)
            targetEngine.activeZoomParams = params
            targetEngine.setZoomRowHeight(computeRowHeightPx(params))
            targetEngine.preserveRowHeightForZoom = true
        } else if (targetEngine is GridEngine) {
            targetEngine.preserveRowHeightForZoom = false
        }

        targetEngine.positionSlot = layoutState.mainSlot
        targetEngine.calculateTotalRows(itemCount)
        targetEngine.performLayout(LayoutState.STATE_IDLE, 0, 0, itemCount, layoutState)
        columnTransitionTargetScrollY = targetEngine.computeZoomAnchorScrollY(
            columnTransitionAnchorPosition,
            columnTransitionPowerampDirection,
            itemCount
        )
        targetEngine.setScrollOffset(0.0, columnTransitionTargetScrollY.toDouble())

        layoutState.setMainEngine(targetEngine)
        val backupEng = layoutState.getBackupEngine() as? GridListEngine
        backupEng?.setupTransitionMode(0, !columnTransitionPowerampDirection, 0)
        targetEngine.setupTransitionMode(0, columnTransitionPowerampDirection, 0)
        backupEng?.transitionScaleFactor = 1f
        targetEngine.transitionScaleFactor = 1f
        layoutState.transitionProgress = 0f
        layoutState.setTransitionState(LayoutState.STATE_DUAL_ENGINE_TRANSITION)
        layoutState.refreshLayout(true, false)
        invalidate()
    }

    private fun chooseColumnTransitionAnchorPosition(sourceEngine: GridListEngine): Int {
        if (itemCount <= 0) return 0
        val rowStride = (sourceEngine.getRowScrollOffset(1) - sourceEngine.getRowScrollOffset(0)).coerceAtLeast(1)
        val viewportCenterY = (scrollY + sourceEngine.getAvailableHeight() / 2).coerceAtLeast(0)
        val centerRow = (viewportCenterY / rowStride).coerceAtLeast(0)
        return sourceEngine.rowToPosition(centerRow).coerceIn(0, itemCount - 1)
    }

    private fun updateColumnZoomTransition(progress: Float, scaleFactor: Float) {
        if (!columnTransitionActive || layoutState.transitionState != LayoutState.STATE_DUAL_ENGINE_TRANSITION) return
        layoutState.transitionProgress = progress.coerceIn(0f, 1f)
        val c = scaleFactor.coerceIn(0.85f, 1.15f)
        (layoutState.getActiveEngine() as? GridListEngine)?.transitionScaleFactor = c
        (layoutState.getBackupEngine() as? GridListEngine)?.transitionScaleFactor = c
        layoutState.refreshLayout(true, true)
        invalidate()
    }

    private fun finishColumnZoomTransition(confirmed: Boolean, releaseProgressVelocity: Float = 0f) {
        if (!columnTransitionActive || layoutState.transitionState != LayoutState.STATE_DUAL_ENGINE_TRANSITION) return
        val start = layoutState.transitionProgress
        val end = if (confirmed) 1f else 0f
        columnTransitionAnimator?.cancel()
        val baseDuration = if (confirmed) COLUMN_COMMIT_DURATION_MS else COLUMN_ROLLBACK_DURATION_MS
        val minDuration = if (confirmed) 100L else baseDuration / 3L
        val velocityDuration = computeColumnVelocityHandoffDurationMs(start, end, confirmed, releaseProgressVelocity)
        columnTransitionAnimator = ValueAnimator.ofFloat(start, end).apply {
            duration = velocityDuration
                ?: (kotlin.math.abs(end - start) * baseDuration).toLong().coerceIn(minDuration, baseDuration)
            interpolator = if (velocityDuration != null) LinearInterpolator() else AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                val p = anim.animatedValue as Float
                updateColumnZoomTransition(p, 1f)
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }
                override fun onAnimationEnd(animation: Animator) {
                    if (!cancelled) completeColumnZoomTransition(confirmed)
                }
            })
            start()
        }
    }

    private fun computeColumnVelocityHandoffDurationMs(
        start: Float,
        end: Float,
        confirmed: Boolean,
        releaseProgressVelocity: Float
    ): Long? {
        if ((confirmed && releaseProgressVelocity <= 0f) || (!confirmed && releaseProgressVelocity >= 0f)) return null
        val baseDurationSeconds = (if (confirmed) COLUMN_COMMIT_DURATION_MS else COLUMN_ROLLBACK_DURATION_MS) / 1000f
        val velocity = if (confirmed) {
            releaseProgressVelocity.coerceIn(1f / baseDurationSeconds, 8f)
        } else {
            releaseProgressVelocity.coerceIn(-8f, -3.5f)
        }
        val distance = kotlin.math.abs(end - start).coerceAtLeast(0.0001f)
        return (distance / kotlin.math.abs(velocity) * 1000f).toLong().coerceAtLeast(1L)
    }

    private fun completeColumnZoomTransition(confirmed: Boolean) {
        val finalMode = if (confirmed) columnTransitionTargetMode else columnTransitionSourceMode
        for (holder in layoutState.viewPool.getAll()) {
            (holder.view as? PowerListSceneItem)?.H(confirmed)
        }
        if (confirmed) layoutState.confirmTransition() else layoutState.cancelTransition()

        currentDisplayMode = finalMode
        currentColumns = finalMode.columns
        if (finalMode.listLevel != null) {
            listZoomManager.commitLevel(finalMode.listLevel)
            prefs.edit().putInt("list_zoom_level", finalMode.listLevel.zoomInt).apply()
        }
        saveColumnCount()

        val engine = layoutState.getActiveEngine() as? GridListEngine
        val finalScene = sceneForDisplayMode(finalMode)
        engine?.resetTransition()
        if (engine is GridEngine) {
            applyPowerampGridParams(engine)
            engine.preserveRowHeightForZoom = false
        }
        if (engine is ListEngine) {
            val params = finalMode.listLevel?.let { listZoomManager.paramsForLevel(it) } ?: listZoomManager.currentParams
            engine.activeZoomParams = params
            engine.setZoomRowHeight(computeRowHeightPx(params))
            engine.preserveRowHeightForZoom = true
        }

        for (holder in layoutState.viewPool.getAll()) {
            val view = holder.view ?: continue
            view.alpha = 1f
            view.scaleX = 1f
            view.scaleY = 1f
            view.rotation = 0f
            view.rotationX = 0f
            view.rotationY = 0f
            if (view is AAItemView) {
                view.suppressZoomLayoutRequest = true
                finalMode.listLevel?.let { applyZoomParamsToItem(view, listZoomManager.paramsForLevel(it)) }
                view.currentScene = finalScene
                holder.invalidatePositions()
                view.forceLayout()
                view.suppressZoomLayoutRequest = false
                holder.currentScene = finalScene
            }
        }

        scrollY = if (confirmed) {
            columnTransitionTargetScrollY.coerceAtLeast(0)
        } else {
            columnTransitionSourceScrollY.coerceAtLeast(0)
        }
        layoutState.setScrollOffset(0.0, scrollY.toDouble())
        layoutState.refreshLayout(true, false)
        if (scrollY > maxScrollY) {
            scrollY = maxScrollY
            layoutState.setScrollOffset(0.0, scrollY.toDouble())
            layoutState.refreshLayout(true, false)
        }
        columnTransitionActive = false
        activePowerListTransition = ActivePowerListTransition.NONE
        activeTransitionDirection = true
        activeTransitionUseStrictOverlap = false
        columnTransitionAnimator = null
        columnCountChangeListener?.onColumnCountChanged(currentColumns)
    }

    /**
     * Start snap transition — Poweramp's q1.p() → a2.B() flow.
     *
     * This is a 1:1 implementation of Poweramp's dual-engine container swap:
     *
     * 1. swapContainers() — move current main engine to backup (source)
     * 2. Create new target engine on main with target zoom params
     * 3. Set up transition modes on both engines via c()
     * 4. Set transitionState = STATE_DUAL_ENGINE_TRANSITION
     * 5. Layout both engines (m3017)
     *
     * Poweramp's a2.B():
     *   uVar.B = o2; uVar.f4251 = o;  // swap containers
     *   rVar.l(uVar.x, true);          // source engine: l(3, true) → sets transition mode
     *   powerList2.m(rVar, rVar, i, z); // create target engine
     *   uVar.m3016(3);                  // set transition state = 3
     */
    private fun startSnapTransition(targetLevel: ListZoomIndex, isZoomIn: Boolean) {
        if (currentColumns != 1) return
        // Guard against re-entry: if already in transition, ignore duplicate call
        if (layoutState.transitionState == LayoutState.STATE_DUAL_ENGINE_TRANSITION) {
            return
        }

        snapIsZoomIn = isZoomIn
        transitionSourceLevel = listZoomManager.currentLevel
        transitionTargetLevel = targetLevel
        val sourceParams = listZoomManager.paramsForLevel(transitionSourceLevel)

        val sourceEngine = layoutState.getActiveEngine() as? GridListEngine ?: return
        sourceEngine.activeZoomParams = sourceParams
        val oldRowHeight = sourceEngine.actualRowHeight
        if (oldRowHeight > 0) {
            sourceEngine.setZoomRowHeight(oldRowHeight)
            sourceEngine.preserveRowHeightForZoom = true
        }

        // Save source scroll position BEFORE any adjustment.
        transitionSourceScrollY = scrollY
        transitionSourceRowHeight = oldRowHeight
        if (oldRowHeight > 0) {
            transitionAnchorPosition = (transitionSourceScrollY / oldRowHeight).coerceAtLeast(0)
            transitionAnchorOffset = (transitionSourceScrollY - transitionAnchorPosition * oldRowHeight).coerceAtLeast(0)
        } else {
            transitionAnchorPosition = firstVisiblePosition.coerceAtLeast(0)
            transitionAnchorOffset = 0
        }

        // Cancel any pending zoom update from the pinch gesture to prevent
        // zoom params from being applied during the snap animation.
        removeCallbacks(zoomUpdateRunnable)
        zoomUpdateScheduled = false
        pendingZoomParams = null


        // 1. Swap containers — Poweramp's a2.B() flow
        // Both engines share the SAME ViewPool. Backup engine writes to slot 1
        // on the same ViewHolders that main engine writes to slot 0.
        layoutState.swapContainers()

        // 2. Create target engine with target zoom params
        val targetParams = listZoomManager.paramsForLevel(targetLevel)
        val targetEngine = ListEngine(rowHeight, context.resources.displayMetrics.density)
        
        targetEngine.setDimensions(
            sourceEngine.bounds.width(), sourceEngine.bounds.height(),
            sourceEngine.padding.left, sourceEngine.padding.top,
            sourceEngine.padding.right, sourceEngine.padding.bottom
        )
        targetEngine.copyLayoutMetricsFrom(sourceEngine)
        targetEngine.activeZoomParams = targetParams
        
        listZoomManager.applyToItem = { itemView ->
            applyZoomParamsToItem(itemView, targetParams)
        }

        // Pre-measure one view to get target row height
        val preMeasureHolder = layoutState.viewPool[firstVisiblePosition]
        val preMeasureView = preMeasureHolder?.view
        var targetRowHeight = 0
        if (preMeasureView != null && oldRowHeight > 0 && sourceEngine.colWidth > 0) {
            if (preMeasureView is AAItemView) {
                preMeasureView.beginSlotLayoutZoomParams(targetParams)
            }
            try {
                val wSpec = android.view.View.MeasureSpec.makeMeasureSpec(sourceEngine.colWidth, android.view.View.MeasureSpec.EXACTLY)
                val hSpec = android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED)
                preMeasureView.measure(wSpec, hSpec)
                targetRowHeight = preMeasureView.measuredHeight
            } finally {
                if (preMeasureView is AAItemView) {
                    preMeasureView.endSlotLayoutZoomParams()
                }
            }
        }
        // Poweramp: each engine computes its OWN positions with its OWN zoom params.
        // Target engine uses its own row height from target zoom params.
        // Don't force it to match source — the difference creates the zoom effect.
        if (targetRowHeight <= 0) {
            targetRowHeight = computeRowHeightPx(targetParams)
        }
        targetEngine.setZoomRowHeight(targetRowHeight)
        targetEngine.preserveRowHeightForZoom = true


        val targetScrollY = if (oldRowHeight > 0 && targetRowHeight > 0) {
            val scaledOffset = (transitionAnchorOffset.toLong() * targetRowHeight / oldRowHeight).toInt()
            (transitionAnchorPosition.toLong() * targetRowHeight + scaledOffset).toInt().coerceAtLeast(0)
        } else {
            transitionSourceScrollY
        }

        // Set target engine as main FIRST, then set its own scroll offset.
        layoutState.setMainEngine(targetEngine)
        targetEngine.setScrollOffset(0.0, targetScrollY.toDouble())

        // 3. Set up transition modes
        val backupEng = layoutState.getBackupEngine() as? GridListEngine
        // Poweramp PowerListZoomTransitionBase.m2948(): target.c(0, !z, 0),
        // source.c(0, z, 0). Dual-slot rows use geometry interpolation, but
        // one-sided rows depend on these zoom bases for the correct fade/scale
        // direction at the top/bottom edges.
        backupEng?.setupTransitionMode(0, isZoomIn, 0)

        targetEngine.setupTransitionMode(0, !isZoomIn, 0)


        // 4. Set transition state = 3 (dual-engine) and reset progress
        layoutState.transitionProgress = 0f
        layoutState.setTransitionState(LayoutState.STATE_DUAL_ENGINE_TRANSITION)
        
        // Set initial transitionScaleFactor = 1.0
        backupEng?.transitionScaleFactor = 1.0f
        targetEngine.transitionScaleFactor = 1.0f

        // 5. Layout both engines
        layoutState.refreshLayout(true, false)
        targetEngine.preserveRowHeightForZoom = true

        // Restore applyToItem
        listZoomManager.applyToItem = { itemView ->
            listZoomManager.applyToItemDefault(itemView)
        }

        // Clamp scroll
        if (scrollY > maxScrollY) {
            scrollY = maxScrollY
            layoutState.setScrollOffset(0.0, scrollY.toDouble())
        }

    }

    /**
     * Update snap transition each frame — Poweramp's z1.H() → u.m3013() → q1.mo2962() flow.
     *
     * This is a 1:1 implementation of Poweramp's dual-engine crossfade:
     *
     * 1. u.m3013(f) → p(f) — sets transition progress on both engines
     *    Main engine: t(f, false), Backup engine: t(1-f, true)
     *    (t() is a no-op in base class r.java)
     *
     * 2. q1.mo2962(1-f, true) — sets C = 1-f on BOTH engines
     *    ((q) mainEngine).B(f2) where f2 = 1-f
     *    ((q) backupEngine).B(f2) if backupEngine != mainEngine
     *
     * 3. u.m3017(true, true) — layout both engines with updated C
     *    Each engine's j() computes transforms using C and progress
     *
     * The actual visual effect comes from j() which uses:
     *   - C (scale factor = 1-progress)
     *   - s (scale base from c())
     *   - D (alpha start value)
     *   - progress (f for main, 1-f for backup)
     */
    private fun updateSnapTransition(progress: Float, scaleFactor: Float, isZoomIn: Boolean) {
        if (layoutState.transitionState != LayoutState.STATE_DUAL_ENGINE_TRANSITION) return

        // 1. Set transition progress
        layoutState.transitionProgress = progress

        // 2. Set C on BOTH engines — Poweramp q1.K() → mo2962()
        val c = scaleFactor.coerceIn(0.85f, 1.15f)
        val mainEngine = layoutState.getActiveEngine() as? GridListEngine
        val backupEngine = layoutState.getBackupEngine() as? GridListEngine

        mainEngine?.transitionScaleFactor = c
        backupEngine?.transitionScaleFactor = c

        // 3. Layout both engines
        // Poweramp does NOT interpolate zoom params during transition.
        // Source items keep source size, target items keep target size.
        // Visual zoom comes from j() scale transforms on the slots.
        layoutState.refreshLayout(true, true)

        invalidate()
    }

    /**
     * Finish snap transition — Poweramp's a2.m2952(z=true) flow.
     *
     * Confirms the transition: target engine becomes the new main,
     * source engine views are removed.
     *
     * Poweramp's m2952(z=true):
     *   uVar.B = o2; uVar.f4251 = o;  // swap back (target becomes main)
     *   o.A = false;                    // clear active flag
     *   uVar.A = d2Var;                 // set view pool
     *   uVar.m3015(false, z2);          // finalize
     *   uVar.m3016(0);                  // reset transition state
     */
    private fun finishSnapTransition(confirmed: Boolean = true) {
        if (layoutState.transitionState != LayoutState.STATE_DUAL_ENGINE_TRANSITION) return

        val finalLevel = if (confirmed) transitionTargetLevel else transitionSourceLevel
        currentDisplayMode = displayModeForListLevel(finalLevel)
        currentColumns = 1
        val finalParams = listZoomManager.paramsForLevel(finalLevel)
        val finalScene = resolveSceneIdForZoomParams(finalParams)

        // Poweramp u.H()/ItemSceneFastLayout.H(): commit or roll back each item's
        // internal scene transition before slots are invalidated.
        for (holder in layoutState.viewPool.getAll()) {
            val view = holder.view ?: continue
            (view as? PowerListSceneItem)?.H(confirmed)
        }

        if (confirmed) {
            layoutState.confirmTransition()
        } else {
            layoutState.cancelTransition()
        }

        // Apply current zoom params to engine and views. Also clear any one-sided
        // transition transform left on the physical View before the final normal
        // refresh, otherwise rows that faded out can stay invisible until recycled.
        val engine = layoutState.getActiveEngine() as? GridListEngine
        if (engine != null) {
            engine.activeZoomParams = finalParams
            for (holder in layoutState.viewPool.getAll()) {
                val view = holder.view
                if (view != null) {
                    view.alpha = 1f
                    view.scaleX = 1f
                    view.scaleY = 1f
                    view.rotation = 0f
                    view.rotationX = 0f
                    view.rotationY = 0f
                }
                if (view is AAItemView) {
                    view.suppressZoomLayoutRequest = true
                    applyZoomParamsToItem(view, finalParams)
                    view.currentScene = finalScene
                    view.forceLayout()
                    view.suppressZoomLayoutRequest = false
                    holder.currentScene = finalScene
                }
            }
        }

        var finalRowHeight = 0
        if (engine != null) {
            val preMeasureHolder = layoutState.viewPool[firstVisiblePosition]
            val preMeasureView = preMeasureHolder?.view
            if (preMeasureView != null && engine.colWidth > 0) {
                if (preMeasureView is AAItemView) {
                    preMeasureView.suppressZoomLayoutRequest = true
                    applyZoomParamsToItem(preMeasureView, finalParams)
                    preMeasureView.suppressZoomLayoutRequest = false
                }
                val wSpec = android.view.View.MeasureSpec.makeMeasureSpec(engine.colWidth, android.view.View.MeasureSpec.EXACTLY)
                val hSpec = android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED)
                preMeasureView.measure(wSpec, hSpec)
                finalRowHeight = preMeasureView.measuredHeight
            }
            if (finalRowHeight <= 0) {
                finalRowHeight = computeRowHeightPx(finalParams)
            }

            if (finalRowHeight > 0) {
                engine.setZoomRowHeight(finalRowHeight)
                engine.preserveRowHeightForZoom = true
            }
        }

        if (engine != null && transitionSourceRowHeight > 0) {
            if (confirmed) {
                scrollY = engine.getScrollOffset().y.coerceAtLeast(0)
            } else if (!confirmed) {
                scrollY = transitionSourceScrollY.coerceAtLeast(0)
            }
            engine.preserveRowHeightForZoom = true
            layoutState.setScrollOffset(0.0, scrollY.toDouble())
        }

        engine?.resetTransition()
        layoutState.refreshLayout(true, false)

        // Clamp scroll after maxScrollY is recalculated
        if (scrollY > maxScrollY) {
            scrollY = maxScrollY
            layoutState.setScrollOffset(0.0, scrollY.toDouble())
        }

    }

    // ==================== Column Count Control ====================
    
    /**
     * Switches to a specific column count.
     * @param columns Target column count
     */
    fun switchTo(columns: Int) {
        val targetCols = max(MIN_COLUMNS, min(columns, maxColumns))
        if (targetCols == currentColumns) return
        switchToColumnCount(targetCols)
    }

    private fun nextGestureColumnCount(): Int {
        return when {
            currentColumns <= 1 -> maxColumns
            currentColumns > 2 -> currentColumns - 1
            else -> 1
        }
    }
    
    /**
     * Increases columns.
     */
    fun increaseColumns() {
        if (currentColumns < maxColumns) {
            switchTo(currentColumns + 1)
        }
    }
    
    /**
     * Decreases columns.
     */
    fun decreaseColumns() {
        if (currentColumns > MIN_COLUMNS) {
            switchTo(currentColumns - 1)
        }
    }
    
    // ==================== Helpers ====================

    /** Linear interpolation for Int values — Poweramp's Utils.x(int, int, int) */
    private fun lerpInt(f: Float, from: Int, to: Int): Int {
        return Math.round(f * (to - from)) + from
    }

    /** Linear interpolation for Float values — Poweramp's Utils.X(float, float, float) */
    private fun lerp(f: Float, from: Float, to: Float): Float {
        return from + f * (to - from)
    }

    /** Fast view repositioning without layout pass — Poweramp's e4.n(z=false) */
    private fun setViewBoundsFast(view: View, l: Int, t: Int, r: Int, b: Int) {
        if (android.os.Build.VERSION.SDK_INT >= 22) {
            view.setLeftTopRightBottom(l, t, r, b)
        } else {
            view.layout(l, t, r, b)
        }
    }

    private fun applyZoomParamsToItem(itemView: AAItemView, params: ListZoomParams) {
        itemView.zoomParams = params
    }

    private fun resolveSceneIdForZoomParams(params: ListZoomParams): Int {
        return ListZoomLevels.sceneIdForParams(params)
    }

    /**
     * Applies zoom params with scroll position compensation.
     * During pinch gesture: coalesces multiple MOVE events per frame via postOnAnimation.
     * During snap animation: applies immediately (already vsync-aligned).
     */
    private fun applyZoomWithScrollCompensation(params: ListZoomParams) {
        if (listZoomManager.isPinching) {
            // During pinch: coalesce to one layout per frame.
            // Multiple MOVE events arrive within a single frame; only the latest params matter.
            pendingZoomParams = params
            if (!zoomUpdateScheduled) {
                zoomUpdateScheduled = true
                postOnAnimation(zoomUpdateRunnable)
            }
        } else {
            // During snap animation or direct call: apply immediately.
            // Cancel any pending pinch update to avoid stale params.
            removeCallbacks(zoomUpdateRunnable)
            zoomUpdateScheduled = false
            pendingZoomParams = null
            applyZoomImmediate(params)
        }
    }

    /**
     * Core zoom application: scroll compensation + layout + view updates.
     * Must be called at most once per frame to avoid jitter from multiple refreshLayout() calls.
     *
     * CRITICAL ORDER: zoom params must be set on items BEFORE refreshLayout(), because
     * performLayout() → b0() → measureCell() measures views during layout. If params are
     * set after, measurements use stale cover size/margins and items appear wrong sizes.
     */
    private fun applyZoomImmediate(params: ListZoomParams) {
        val engine = layoutState.getActiveEngine() as? GridListEngine


        if (engine != null && currentColumns == 1) {
            engine.activeZoomParams = params

            // Record old row height for scroll compensation.
            val oldRowHeight = engine.actualRowHeight

            // Set zoom params on visible items and forceLayout to clear measure cache.
            for (pos in firstVisiblePosition..lastVisiblePosition) {
                val holder = layoutState.viewPool[pos] ?: continue
                val view = holder.view
                if (view is AAItemView) {
                    view.suppressZoomLayoutRequest = true
                    listZoomManager.applyToItem(view)
                    view.suppressZoomLayoutRequest = false
                    view.forceLayout()
                }
            }

            // PRE-MEASURE: measure one view to get the actual row height with new params.
            // This avoids post-layout scroll correction that causes jitter.
            val preMeasureView = layoutState.viewPool[firstVisiblePosition]?.view
            var actualNewRowHeight = 0
            if (preMeasureView != null && oldRowHeight > 0 && engine.colWidth > 0) {
                val wSpec = android.view.View.MeasureSpec.makeMeasureSpec(
                    engine.colWidth, android.view.View.MeasureSpec.EXACTLY
                )
                val hSpec = android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED)
                preMeasureView.measure(wSpec, hSpec)
                actualNewRowHeight = preMeasureView.measuredHeight
            }

            // Use pre-measured height if available, otherwise fall back to estimate.
            val newRowHeightForEngine = if (actualNewRowHeight > 0) actualNewRowHeight else computeRowHeightPx(params)
            engine.setZoomRowHeight(newRowHeightForEngine)
            engine.preserveRowHeightForZoom = true

            // PRE-LAYOUT SCROLL CORRECTION (ratio formula — same as startSnapTransition):
            // Adjust scrollY BEFORE layout so items are positioned with the correct scroll offset.
            // Ratio formula: newScrollY = oldScrollY * newRowHeight / oldRowHeight
            // This preserves the scroll ratio so the same portion of the list is visible.
            if (oldRowHeight > 0 && newRowHeightForEngine > 0 && oldRowHeight != newRowHeightForEngine) {
                val anchorPosition = (scrollY / oldRowHeight).coerceAtLeast(0)
                val anchorOffset = (scrollY - anchorPosition * oldRowHeight).coerceAtLeast(0)
                val scaledOffset = (anchorOffset.toLong() * newRowHeightForEngine / oldRowHeight).toInt()
                scrollY = (anchorPosition.toLong() * newRowHeightForEngine + scaledOffset).toInt().coerceAtLeast(0)
                layoutState.setScrollOffset(0.0, scrollY.toDouble())
            }

            layoutState.refreshLayout(true, false)

            // Removed post-layout residual correction — it caused a 1-frame scroll flash.
            // The pre-measured row height is accurate enough (same view, same measure spec).
            // Any sub-pixel difference is negligible and won't accumulate.

            engine.preserveRowHeightForZoom = true
        } else {
            layoutState.refreshLayout(true, false)
        }

        // Clamp scroll after maxScrollY is recalculated by refreshLayout
        if (scrollY > maxScrollY) {
            scrollY = maxScrollY
            layoutState.setScrollOffset(0.0, scrollY.toDouble())
        }

        invalidate()
    }

    /**
     * Computes expected row height in pixels from zoom params.
     * This must match the ACTUAL measured row height from AAItemView.measureList(),
     * which is: maxOf(coverSize + coverMargins, textBlockHeight + padding, rowHeightParam).
     *
     * CRITICAL: Must include text height estimate. Without it, SMALL mode returns ~rowHeightValue
     * (e.g., 155px) but actual measured height includes 3 lines of text (~192px).
     * The scroll compensation ratio becomes inflated (288/155=1.86 vs correct 288/192=1.5),
     * causing content to slide rapidly during zoom.
     */
    private fun computeRowHeightPx(params: ListZoomParams): Int {
        val density = context.resources.displayMetrics.density
        val scaledDensity = context.resources.displayMetrics.scaledDensity

        // Cover contribution: coverSize + topMargin + bottomMargin
        // When coverSizeDp = -1 (MATCH_PARENT), cover height = rowHeight - margins
        // So cover doesn't contribute a fixed height — it adapts to row height.
        val coverSize = if (params.coverSizeDp < 0) 0 else (params.coverSizeDp * density).toInt()
        val coverMargins = ((params.coverMarginTopDp + params.coverMarginBottomDp) * density).toInt()

        // Minimum row height from params
        val minRowHeight = if (params.rowHeightIsSp) {
            (params.rowHeightValue * scaledDensity).toInt()
        } else {
            (params.rowHeightValue * density).toInt()
        }

        // Text height estimate using Poweramp base sizes * textScale:
        // title=22sp, line2=18.25sp, meta=13.5sp (all single-line)
        val scale = params.textScale
        val titleLineH = (22f * scale * density).toInt()
        val line2LineH = (18.25f * scale * density).toInt()
        val metaLineH = (13.5f * scale * density).toInt()
        val textPad = (16 * density).toInt() // top+bottom text block padding (8dp each)

        val textH = if (params.line2Visible) {
            val gap6 = (6 * density).toInt()
            val mif = params.metaInlineFraction
            if (mif >= 1f) {
                // SMALL compact: title + max(line2, meta) sharing a row
                titleLineH + gap6 + maxOf(line2LineH, metaLineH) + textPad
            } else if (mif <= 0f) {
                // NORMAL/ZOOMED stacked: title + line2 + meta
                titleLineH + gap6 + line2LineH + (5 * density).toInt() + metaLineH + textPad
            } else {
                // Transitioning: interpolate
                val stackedH = titleLineH + gap6 + line2LineH + (5 * density).toInt() + metaLineH + textPad
                val inlineH = titleLineH + gap6 + maxOf(line2LineH, metaLineH) + textPad
                (stackedH * (1f - mif) + inlineH * mif).toInt()
            }
        } else {
            0
        }

        return maxOf(coverSize + coverMargins, textH, minRowHeight)
    }

    /**
     * Easing curve — Poweramp's q1.B() method.
     * Uses sqrt curve with params (0.2, 0.2) for responsive feel.
     */
    private fun easingCurve(f: Float): Float {
        if (f > 0.0f) {
            val d = min(3.0, f.toDouble()) / 3.0
            return (Math.sqrt(d * 0.2) * 0.2f).toFloat()
        } else if (f < 0.0f) {
            val d = (1.0 - max(0.1, min(1.0, f.toDouble() + 1.0))) / 0.9
            return -(Math.sqrt(d * 0.2) * 0.2f).toFloat()
        }
        return 0.0f
    }

    /**
     * Recalculates the maximum scroll position.
     */
    internal fun recalculateMaxScroll() {
        val engine = layoutState.getActiveEngine() as? GridListEngine ?: return
        maxScrollY = engine.getMaxScrollY(itemCount)
    }
    
    /**
     * Forces a layout refresh.
     */
    fun refreshLayout() {
        // Update itemCount from data provider before layout
        itemCount = dataProvider?.getItemCount() ?: 0
        ensureSurfaceVisibleForItems()
        requestLayout()
    }

    /**
     * PowerList owns its item drawing surface. A previous empty-state fade-out can
     * leave the ViewGroup at alpha=0/GONE while child holders are still laid out and
     * clickable, so recover deterministically whenever data exists.
     */
    fun ensureSurfaceVisibleForItems() {
        if (itemCount <= 0) return
        if (visibility != View.VISIBLE || alpha < 0.999f) {
            animate().cancel()
            clearAnimation()
            visibility = View.VISIBLE
            alpha = 1f
        }
    }
}
