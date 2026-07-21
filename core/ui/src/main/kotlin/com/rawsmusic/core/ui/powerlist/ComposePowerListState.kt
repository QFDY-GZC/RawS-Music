package com.rawsmusic.core.ui.widget.powerlist

import android.content.Context
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlin.math.abs
import kotlin.math.sqrt

private const val PREFS_NAME = "power_list_prefs"
private const val KEY_LIST_ZOOM_LEVEL = "list_zoom_level"
private const val KEY_COLUMN_COUNT = "column_count"
private const val SNAP_DURATION_MS = 500
private const val COLUMN_COMMIT_DURATION_MS = 250
private const val COLUMN_ROLLBACK_DURATION_MS = 500
private const val VELOCITY_THRESHOLD_DP = 500f
private const val POSITION_THRESHOLD = 0.3f
private const val MIN_COLUMNS = 1
private const val MAX_COLUMNS = 4

internal enum class ComposePowerListDisplayMode(
    val columns: Int,
    val sceneId: Int,
    val listLevel: ListZoomIndex? = null
) {
    LIST_SMALL(1, PowerListSceneItem.SCENE_SMALL, ListZoomIndex.SMALL),
    LIST_NORMAL(1, PowerListSceneItem.SCENE_NORMAL, ListZoomIndex.NORMAL),
    LIST_ZOOMED(1, PowerListSceneItem.SCENE_ZOOMED, ListZoomIndex.ZOOMED),
    GRID_4(4, PowerListSceneItem.SCENE_GRID),
    GRID_3(3, PowerListSceneItem.SCENE_GRID),
    GRID_2(2, PowerListSceneItem.SCENE_GRID);

    val isGrid: Boolean get() = columns > 1
}

@Stable
class ComposePowerListState internal constructor(
    initialLevel: ListZoomIndex,
    initialColumns: Int,
    private val persistZoomLevel: (ListZoomIndex) -> Unit,
    private val persistColumns: (Int) -> Unit
) {
    var currentLevel by mutableStateOf(initialLevel)
        private set

    var currentColumns by mutableIntStateOf(initialColumns.coerceIn(MIN_COLUMNS, MAX_COLUMNS))
        private set

    internal var sourceMode by mutableStateOf(displayModeForColumns(currentColumns, currentLevel))
        private set

    internal var targetMode by mutableStateOf(sourceMode)
        private set

    var transitionProgress by mutableFloatStateOf(1f)
        private set

    var transitionScaleFactor by mutableFloatStateOf(1f)
        private set

    var boundaryElasticScale by mutableFloatStateOf(1f)
        private set

    var isTransitioning by mutableStateOf(false)
        private set

    var isPinching by mutableStateOf(false)
        internal set

    internal val isBoundaryElasticActive: Boolean
        get() = abs(boundaryElasticScale - 1f) >= 0.001f || abs(boundaryRawOverPull) >= 0.001f

    private var boundaryAnimationGeneration = 0
    private var boundaryRawOverPull by mutableFloatStateOf(0f)

    /** 外部请求滚动到指定索引（字母索引用），-1 表示无请求 */
    var currentVisibleCenterIndex by mutableIntStateOf(0)
        private set

    var currentVisibleRange by mutableStateOf(IntRange.EMPTY)
        private set

    fun isIndexVisible(index: Int): Boolean {
        return index >= 0 && !currentVisibleRange.isEmpty() && index in currentVisibleRange
    }

    internal fun updateVisibleRangeForNavigation(range: IntRange) {
        currentVisibleRange = range
        if (!range.isEmpty()) {
            currentVisibleCenterIndex = ((range.first + range.last) / 2).coerceAtLeast(0)
        }
    }

    var scrollToIndexRequestIndex by mutableIntStateOf(-1)
        private set

    var scrollToIndexRequestSerial by mutableIntStateOf(0)
        private set

    fun requestScrollToIndex(index: Int) {
        if (index < 0) return
        scrollToIndexRequestIndex = index
        scrollToIndexRequestSerial += 1
    }

    internal fun consumeScrollToIndexRequest(serial: Int) {
        if (scrollToIndexRequestSerial == serial) {
            scrollToIndexRequestIndex = -1
        }
    }

    internal var viewportScrollY by mutableFloatStateOf(0f)

    /** Current vertical scroll offset in pixels. Useful for external header/hero sync. */
    val viewportScrollYPx: Float
        get() = viewportScrollY

    internal val currentMode: ComposePowerListDisplayMode
        get() = displayModeForColumns(currentColumns, currentLevel)

    internal val renderMode: ComposePowerListDisplayMode
        get() = currentMode

    val isGrid: Boolean get() = renderMode.isGrid
    val columns: Int get() = renderMode.columns

    internal val sourceOneSlotZoomScaleBase: Float
        get() = 1f

    internal val targetOneSlotZoomScaleBase: Float
        get() = 1f

    val currentParams: ListZoomParams
        get() = paramsFor(currentLevel)

    fun beginPinch() {
        boundaryAnimationGeneration += 1
        isPinching = true
    }

    fun updatePinch(rawDelta: Float, velocityDp: Float) {
        val isZoomIn = rawDelta > 0f
        if (!isTransitioning) {
            val target = nextMode(isZoomIn)
            if (target == null) {
                updateBoundaryElastic(rawDelta, isZoomIn)
                return
            }
            beginTransition(target)
        }

        val signedDelta = if (isZoomIn == isZoomInTransition()) abs(rawDelta) else -abs(rawDelta)
        transitionProgress = signedDelta.coerceIn(0f, 1f)
        transitionScaleFactor = elasticScale(signedDelta, isZoomInTransition())
        if (velocityDp != 0f) {
            boundaryRawOverPull = 0f
            boundaryElasticScale = 1f
        }
    }

    suspend fun finishPinch(velocityDp: Float) {
        isPinching = false
        if (!isTransitioning) {
            animateBoundaryBack()
            return
        }
        val confirm = if (abs(velocityDp) >= VELOCITY_THRESHOLD_DP) {
            (velocityDp > 0f) == isZoomInTransition()
        } else {
            transitionProgress > POSITION_THRESHOLD
        }
        animateTransition(confirm, velocityDp)
    }

    suspend fun snapToLevel(level: ListZoomIndex) {
        val target = displayModeForListLevel(level)
        if (target == currentMode) return
        beginTransition(target)
        animateTransition(confirm = true, releaseVelocityDp = 0f)
    }

    suspend fun snapToColumns(columns: Int) {
        val targetColumns = columns.coerceIn(MIN_COLUMNS, MAX_COLUMNS)
        val target = displayModeForColumns(targetColumns, currentLevel)
        if (target == currentMode) return
        beginTransition(target)
        animateTransition(confirm = true, releaseVelocityDp = 0f)
    }

    private fun beginTransition(target: ComposePowerListDisplayMode) {
        sourceMode = currentMode
        targetMode = target
        transitionProgress = 0f
        transitionScaleFactor = 1f
        boundaryRawOverPull = 0f
        boundaryElasticScale = 1f
        isTransitioning = true
    }

    private suspend fun animateTransition(confirm: Boolean, releaseVelocityDp: Float) {
        val start = transitionProgress
        val end = if (confirm) 1f else 0f
        val baseDuration = if (sourceMode.isGrid || targetMode.isGrid) {
            if (confirm) COLUMN_COMMIT_DURATION_MS else COLUMN_ROLLBACK_DURATION_MS
        } else {
            SNAP_DURATION_MS
        }
        val velocityDuration = computeVelocityDuration(start, end, releaseVelocityDp)
        val duration = velocityDuration
            ?: (abs(end - start) * baseDuration).toInt().coerceIn(
                if (confirm) 100 else baseDuration / 3,
                baseDuration
            )
        try {
            animate(
                initialValue = start,
                targetValue = end,
                animationSpec = tween(durationMillis = duration, easing = LinearEasing)
            ) { value, _ ->
                transitionProgress = value
                transitionScaleFactor = 1f
            }
            completeTransition(confirm)
        } catch (cancelled: CancellationException) {
            throw cancelled
        }
    }

    private fun completeTransition(confirm: Boolean) {
        val finalMode = if (confirm) targetMode else sourceMode
        finalMode.listLevel?.let {
            currentLevel = it
            persistZoomLevel(it)
            currentColumns = 1
            persistColumns(1)
        } ?: run {
            currentColumns = finalMode.columns
            persistColumns(finalMode.columns)
        }
        sourceMode = finalMode
        targetMode = finalMode
        transitionProgress = 1f
        transitionScaleFactor = 1f
        boundaryRawOverPull = 0f
        boundaryElasticScale = 1f
        isTransitioning = false
    }

    private fun updateBoundaryElastic(rawDelta: Float, expands: Boolean) {
        val settledMode = currentMode
        sourceMode = settledMode
        targetMode = settledMode
        transitionProgress = 1f
        boundaryRawOverPull = if (expands) abs(rawDelta) else -abs(rawDelta)
        boundaryElasticScale = computeBoundaryElasticScale(boundaryRawOverPull)
        transitionScaleFactor = 1f
        isTransitioning = false
    }

    private suspend fun animateBoundaryBack() {
        val generation = boundaryAnimationGeneration
        val start = boundaryRawOverPull
        if (abs(start) < 0.001f) {
            boundaryRawOverPull = 0f
            boundaryElasticScale = 1f
            return
        }
        animate(
            initialValue = start,
            targetValue = 0f,
            animationSpec = tween(durationMillis = 350)
        ) { value, _ ->
            if (generation == boundaryAnimationGeneration) {
                boundaryRawOverPull = value
                boundaryElasticScale = computeBoundaryElasticScale(value)
            }
        }
        if (generation == boundaryAnimationGeneration) {
            boundaryRawOverPull = 0f
            boundaryElasticScale = 1f
        }
    }

    private fun nextMode(isZoomIn: Boolean): ComposePowerListDisplayMode? {
        return if (currentColumns <= 1) {
            val nextLevel = adjacentLevel(currentLevel, isZoomIn)
            when {
                nextLevel != currentLevel -> displayModeForListLevel(nextLevel)
                currentLevel == ListZoomIndex.ZOOMED && isZoomIn -> ComposePowerListDisplayMode.GRID_4
                else -> null
            }
        } else if (isZoomIn) {
            when (currentColumns) {
                4 -> ComposePowerListDisplayMode.GRID_3
                3 -> ComposePowerListDisplayMode.GRID_2
                else -> null
            }
        } else {
            when (currentColumns) {
                2 -> ComposePowerListDisplayMode.GRID_3
                3 -> ComposePowerListDisplayMode.GRID_4
                4 -> ComposePowerListDisplayMode.LIST_ZOOMED
                else -> null
            }
        }
    }

    private fun isZoomInTransition(): Boolean {
        return modeOrder(targetMode) > modeOrder(sourceMode)
    }

    companion object {
        fun fromContext(
            context: Context,
            namespace: String = "default"
        ): ComposePowerListState {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val prefix = namespace
                .takeIf { it.isNotBlank() && it != "default" }
                ?.let { "${it}_" }
                .orEmpty()
            val zoomKey = prefix + KEY_LIST_ZOOM_LEVEL
            val columnsKey = prefix + KEY_COLUMN_COUNT

            val initialLevel = ListZoomIndex.fromZoomInt(
                prefs.getInt(zoomKey, ListZoomIndex.NORMAL.zoomInt)
            )
            val initialColumns = prefs.getInt(columnsKey, MIN_COLUMNS)
                .coerceIn(MIN_COLUMNS, MAX_COLUMNS)
            return ComposePowerListState(
                initialLevel = initialLevel,
                initialColumns = initialColumns,
                persistZoomLevel = { level ->
                    prefs.edit().putInt(zoomKey, level.zoomInt).apply()
                },
                persistColumns = { columns ->
                    prefs.edit().putInt(columnsKey, columns).apply()
                }
            )
        }
    }
}

internal fun paramsFor(level: ListZoomIndex): ListZoomParams = ListZoomLevels.params[level]!!

internal fun displayModeForListLevel(level: ListZoomIndex): ComposePowerListDisplayMode = when (level) {
    ListZoomIndex.SMALL -> ComposePowerListDisplayMode.LIST_SMALL
    ListZoomIndex.NORMAL -> ComposePowerListDisplayMode.LIST_NORMAL
    ListZoomIndex.ZOOMED -> ComposePowerListDisplayMode.LIST_ZOOMED
}

internal fun displayModeForColumns(columns: Int, currentLevel: ListZoomIndex): ComposePowerListDisplayMode {
    return when {
        columns <= 1 -> displayModeForListLevel(currentLevel)
        columns >= 4 -> ComposePowerListDisplayMode.GRID_4
        columns == 3 -> ComposePowerListDisplayMode.GRID_3
        else -> ComposePowerListDisplayMode.GRID_2
    }
}

private fun adjacentLevel(from: ListZoomIndex, isZoomIn: Boolean): ListZoomIndex {
    return if (isZoomIn) {
        when (from) {
            ListZoomIndex.SMALL -> ListZoomIndex.NORMAL
            ListZoomIndex.NORMAL -> ListZoomIndex.ZOOMED
            ListZoomIndex.ZOOMED -> ListZoomIndex.ZOOMED
        }
    } else {
        when (from) {
            ListZoomIndex.ZOOMED -> ListZoomIndex.NORMAL
            ListZoomIndex.NORMAL -> ListZoomIndex.SMALL
            ListZoomIndex.SMALL -> ListZoomIndex.SMALL
        }
    }
}

private fun modeOrder(mode: ComposePowerListDisplayMode): Int = when (mode) {
    ComposePowerListDisplayMode.LIST_SMALL -> 0
    ComposePowerListDisplayMode.LIST_NORMAL -> 1
    ComposePowerListDisplayMode.LIST_ZOOMED -> 2
    ComposePowerListDisplayMode.GRID_4 -> 3
    ComposePowerListDisplayMode.GRID_3 -> 4
    ComposePowerListDisplayMode.GRID_2 -> 5
}

private fun computeVelocityDuration(start: Float, end: Float, velocityDp: Float): Int? {
    val velocity = abs(velocityDp)
    if (velocity < VELOCITY_THRESHOLD_DP) return null
    val distance = abs(end - start).coerceAtLeast(0.0001f)
    val normalizedVelocity = (velocity / VELOCITY_THRESHOLD_DP).coerceIn(1f, 4f)
    return ((SNAP_DURATION_MS * distance) / normalizedVelocity).toInt().coerceIn(80, SNAP_DURATION_MS)
}

private fun easing(distance: Float): Float {
    val clamped = (distance.coerceAtMost(3f) / 3f).coerceIn(0f, 1f)
    return 1f - (1f - clamped) * (1f - clamped)
}

private fun elasticScale(signedDelta: Float, isZoomIn: Boolean): Float {
    val beyond = if (signedDelta > 1f) signedDelta - 1f else -signedDelta
    if (beyond <= 0f) return 1f
    val eased = easing(beyond)
    val direction = if (isZoomIn) 1f else -1f
    return (1f + eased * 0.15f * direction).coerceIn(0.85f, 1.15f)
}

private fun computeBoundaryElasticScale(rawOverPull: Float): Float {
    val easedOffset = if (rawOverPull > 0f) {
        boundaryEasing((rawOverPull.coerceAtMost(3f) / 3f).coerceIn(0f, 1f))
    } else if (rawOverPull < 0f) {
        val reverse = (1f - (1f + rawOverPull).coerceIn(0.1f, 1f)) / 0.9f
        -boundaryEasing(reverse.coerceIn(0f, 1f))
    } else {
        0f
    }
    return (1f + easedOffset).coerceIn(0.9105f, 1.0895f)
}

private fun boundaryEasing(value: Float): Float {
    val clamped = value.coerceIn(0f, 1f)
    return sqrt(clamped * 0.2f) * 0.2f
}
