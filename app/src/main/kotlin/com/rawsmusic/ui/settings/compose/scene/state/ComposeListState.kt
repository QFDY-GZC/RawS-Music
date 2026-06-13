package com.rawsmusic.ui.settings.compose.scene.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.rawsmusic.ui.settings.powerlist.ListZoomIndex
import com.rawsmusic.ui.settings.powerlist.ListZoomLevelKt
import com.rawsmusic.ui.settings.powerlist.ListZoomLevels
import com.rawsmusic.ui.settings.powerlist.ListZoomParams

/**
 * 纯 Compose 版本的列表状态管理
 * 替代原版 PowerListView + LayoutState 的双引擎架构
 *
 * 原版参数对照 (ListZoomParams):
 * - coverSizeDp: 封面大小 (dp), SMALL=32, NORMAL=80, ZOOMED=120
 * - rowHeightValue: 行高值, SMALL=55, NORMAL=96, ZOOMED=137
 * - rowHeightIsSp: 行高是否为 sp (false=dp)
 * - coverMarginLeftDp: 封面左边距, SMALL=16, NORMAL=12, ZOOMED=12
 * - coverMarginTopDp: 封面上边距, SMALL=0, NORMAL=8, ZOOMED=8
 * - coverMarginBottomDp: 封面下边距, SMALL=0, NORMAL=8, ZOOMED=9
 * - cornerRadiusTracksDp: 曲目圆角, SMALL=8, NORMAL=18, ZOOMED=24
 * - cornerRadiusAlbumsDp: 专辑圆角, SMALL=8, NORMAL=8, ZOOMED=12
 * - textMarginLeftDp: 文本左边距, SMALL=18, NORMAL=20, ZOOMED=20
 * - textMarginRightDp: 文本右边距, SMALL=22, NORMAL=20, ZOOMED=20
 * - line2Visible: 是否显示第二行, SMALL=true, NORMAL=false, ZOOMED=true
 * - metaVisible: 是否显示元数据, SMALL=true, NORMAL=true, ZOOMED=true
 * - titleTopOffsetDp: 标题顶部偏移, SMALL=0, NORMAL=8, ZOOMED=8
 * - metaInlineFraction: 元数据内联比例, SMALL=1.0, NORMAL=0.0, ZOOMED=0.0
 * - textScale: 文本缩放比例, SMALL=0.85, NORMAL=0.9, ZOOMED=1.0
 */
class ComposeListState {
    // ==================== 场景状态 ====================

    /** 当前场景 (对应原版 ListZoomManager.currentLevel) */
    var currentScene by mutableStateOf(ListZoomIndex.NORMAL)

    /** 源场景（过渡开始时的场景，对应原版 ListZoomManager.transitionSourceLevel） */
    var sourceScene by mutableStateOf(ListZoomIndex.NORMAL)

    /** 目标场景（过渡结束时的场景，对应原版 ListZoomManager.transitionTargetLevel） */
    var targetScene by mutableStateOf(ListZoomIndex.NORMAL)

    /** 过渡进度 (0..1)，1 表示完成 (对应原版 ListZoomManager.transitionProgress) */
    var transitionProgress by mutableFloatStateOf(1f)

    /** 是否正在过渡 (对应原版 ListZoomManager.transitionStarted) */
    var isTransitioning by mutableStateOf(false)

    // ==================== 缩放参数 ====================

    /** 源场景的缩放参数 (对应原版 ListZoomManager.currentParams) */
    var sourceParams by mutableStateOf(ListZoomLevels.params[ListZoomIndex.NORMAL]!!)

    /** 目标场景的缩放参数 */
    var targetParams by mutableStateOf(ListZoomLevels.params[ListZoomIndex.NORMAL]!!)

    /** 当前生效的参数（插值后） */
    val currentParams: ListZoomParams
        get() = if (isTransitioning) {
            ListZoomLevelKt.lerpZoomParams(sourceParams, targetParams, transitionProgress)
        } else {
            sourceParams
        }

    // ==================== 列表/网格模式 ====================

    /** 列数 (1=列表, 2/3/4=网格) */
    var columns by mutableIntStateOf(1)

    /** 是否为网格模式 */
    val isGrid: Boolean get() = columns > 1

    // ==================== 手势状态 ====================

    /** 是否正在捏合 (对应原版 ListZoomManager.isPinching) */
    var isPinching by mutableStateOf(false)

    /** 捏合速度 (dp/s) (对应原版 ListZoomManager.pinchVelocityDp) */
    var pinchVelocity by mutableFloatStateOf(0f)

    /** 是否待确认网格切换 (对应原版 ListZoomManager.pendingGridZoom) */
    var pendingGridZoom by mutableStateOf(false)

    /** 网格缩放进度 (对应原版 ListZoomManager.gridZoomProgress) */
    var gridZoomProgress by mutableFloatStateOf(0f)

    /** 边界弹性激活 (对应原版 ListZoomManager.boundaryElasticActive) */
    var boundaryElasticActive by mutableStateOf(false)

    /** 边界弹性缩放因子 (对应原版 ListZoomManager.boundaryElasticScale) */
    var boundaryElasticScale by mutableFloatStateOf(1f)

    // ==================== 过渡动画状态 ====================

    /** 过渡缩放因子 (对应原版 ListZoomManager.transitionScaleFactor) */
    var transitionScaleFactor by mutableFloatStateOf(1f)

    /** 是否为缩放方向 (对应原版 ListZoomManager.transitionIsZoomIn) */
    var transitionIsZoomIn by mutableStateOf(true)

    /** Snap 进度 (对应原版 ListZoomManager.snapProgress) */
    var snapProgress by mutableFloatStateOf(1f)

    // ==================== 方法 ====================

    /**
     * 开始过渡到目标场景 (对应原版 ListZoomManager.onPinchStart)
     */
    fun startTransition(target: ListZoomIndex, isZoomIn: Boolean) {
        if (target == currentScene && !isTransitioning) return

        sourceScene = currentScene
        targetScene = target
        sourceParams = ListZoomLevels.params[currentScene]!!
        targetParams = ListZoomLevels.params[target]!!
        transitionIsZoomIn = isZoomIn
        transitionProgress = 0f
        transitionScaleFactor = 1f
        isTransitioning = true
    }

    /**
     * 更新过渡进度 (对应原版 ListZoomManager.onPinchProgress)
     */
    fun updateTransitionProgress(progress: Float, scaleFactor: Float) {
        transitionProgress = progress.coerceIn(0f, 1f)
        transitionScaleFactor = scaleFactor.coerceIn(0.85f, 1.15f)
    }

    /**
     * 提交过渡结果 (对应原版 ListZoomManager.commitLevel)
     */
    fun commitTransition(confirmed: Boolean) {
        if (confirmed) {
            currentScene = targetScene
            sourceParams = targetParams
        } else {
            targetParams = sourceParams
        }
        transitionProgress = 1f
        transitionScaleFactor = 1f
        isTransitioning = false
        snapProgress = 1f
    }

    /**
     * 重置手势状态 (对应原版 ListZoomManager.resetGestureState)
     */
    fun resetGestureState() {
        isTransitioning = false
        transitionProgress = 0f
        transitionScaleFactor = 1f
        pendingGridZoom = false
        gridZoomProgress = 0f
        boundaryElasticActive = false
        boundaryElasticScale = 1f
        snapProgress = 1f
        pinchVelocity = 0f
    }

    /**
     * 获取相邻级别 (对应原版 ListZoomManager.adjacentLevel)
     */
    fun adjacentLevel(from: ListZoomIndex, isZoomIn: Boolean): ListZoomIndex {
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

    companion object {
        /** 速度阈值 (dp/s) - 对应原版 ListZoomManager.VELOCITY_THRESHOLD_DP */
        const val VELOCITY_THRESHOLD_DP = 500f

        /** 位置阈值 - 对应原版 ListZoomManager.POSITION_THRESHOLD */
        const val POSITION_THRESHOLD = 0.3f

        /** Snap 动画时长 (ms) - 对应原版 ListZoomManager.SNAP_DURATION_MS */
        const val SNAP_DURATION_MS = 500L

        /** 边界弹性时长 (ms) - 对应原版 PowerListView.BOUNDARY_ELASTIC_DURATION_MS */
        const val BOUNDARY_ELASTIC_DURATION_MS = 350L
    }
}
