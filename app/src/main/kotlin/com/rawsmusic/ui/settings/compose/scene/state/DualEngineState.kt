package com.rawsmusic.ui.settings.compose.scene.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.rawsmusic.core.ui.widget.powerlist.ListZoomIndex
import com.rawsmusic.core.ui.widget.powerlist.ListZoomLevels
import com.rawsmusic.core.ui.widget.powerlist.ListZoomParams

/**
 * 双引擎状态管理
 * 对应原版 LayoutState 的双引擎架构
 *
 * 原版 LayoutState 管理两个布局引擎：
 * - mainEngine (目标引擎) - 写入 slot 0
 * - backupEngine (源引擎) - 写入 slot 1
 *
 * Compose 版本通过双槽位参数模拟：
 * - sourceParams (源场景参数)
 * - targetParams (目标场景参数)
 * - transitionProgress (过渡进度)
 */
@Stable
class DualEngineState {
    // ==================== 引擎状态 ====================

    /** 是否正在双引擎过渡 */
    var isDualEngineTransition by mutableStateOf(false)
        private set

    /** 源引擎的缩放参数 (对应原版 backupEngine 的参数) */
    var sourceEngineParams by mutableStateOf(ListZoomLevels.params[ListZoomIndex.NORMAL]!!)
        private set

    /** 目标引擎的缩放参数 (对应原版 mainEngine 的参数) */
    var targetEngineParams by mutableStateOf(ListZoomLevels.params[ListZoomIndex.NORMAL]!!)
        private set

    /** 过渡进度 (0..1) (对应原版 LayoutState.transitionProgress) */
    var transitionProgress by mutableFloatStateOf(1f)
        private set

    /** 源引擎的列数 */
    var sourceColumns by mutableStateOf(1)
        private set

    /** 目标引擎的列数 */
    var targetColumns by mutableStateOf(1)
        private set

    // ==================== 引擎槽位 ====================

    /** 主槽位 (对应原版 LayoutState.mainSlot) */
    val mainSlot: Int = 0

    /** 备份槽位 (对应原版 LayoutState.backupSlot) */
    val backupSlot: Int = 1

    // ==================== 过渡缩放 ====================

    /** 过渡缩放因子 (对应原版 GridListEngine.transitionScaleFactor) */
    var transitionScaleFactor by mutableFloatStateOf(1f)
        private set

    /** 是否为缩放方向 (对应原版 PowerListView.snapIsZoomIn) */
    var isZoomIn by mutableStateOf(true)
        private set

    // ==================== 方法 ====================

    /**
     * 开始双引擎过渡
     * 对应原版 LayoutState.setTransitionState(STATE_DUAL_ENGINE_TRANSITION)
     *
     * @param sourceParams 源场景参数
     * @param targetParams 目标场景参数
     * @param sourceColumns 源列数
     * @param targetColumns 目标列数
     * @param isZoomIn 是否为放大方向
     */
    fun startTransition(
        sourceParams: ListZoomParams,
        targetParams: ListZoomParams,
        sourceColumns: Int = 1,
        targetColumns: Int = 1,
        isZoomIn: Boolean = true
    ) {
        this.sourceEngineParams = sourceParams
        this.targetEngineParams = targetParams
        this.sourceColumns = sourceColumns
        this.targetColumns = targetColumns
        this.isZoomIn = isZoomIn
        this.transitionProgress = 0f
        this.transitionScaleFactor = 1f
        this.isDualEngineTransition = true
    }

    /**
     * 更新过渡进度
     * 对应原版 LayoutState.transitionProgress 的更新
     *
     * @param progress 进度 (0..1)
     * @param scaleFactor 缩放因子
     */
    fun updateProgress(progress: Float, scaleFactor: Float = 1f) {
        this.transitionProgress = progress.coerceIn(0f, 1f)
        this.transitionScaleFactor = scaleFactor.coerceIn(0.85f, 1.15f)
    }

    /**
     * 确认过渡
     * 对应原版 LayoutState.confirmTransition()
     */
    fun confirmTransition() {
        this.isDualEngineTransition = false
        this.transitionProgress = 1f
        this.transitionScaleFactor = 1f
    }

    /**
     * 取消过渡
     * 对应原版 LayoutState.cancelTransition()
     */
    fun cancelTransition() {
        this.isDualEngineTransition = false
        this.transitionProgress = 0f
        this.transitionScaleFactor = 1f
    }

    /**
     * 获取当前生效的参数
     * 对应原版插值后的 ListZoomParams
     */
    fun getCurrentParams(): ListZoomParams {
        return if (isDualEngineTransition) {
            lerpZoomParams(sourceEngineParams, targetEngineParams, transitionProgress)
        } else {
            sourceEngineParams
        }
    }

    /**
     * 获取当前生效的列数
     */
    fun getCurrentColumns(): Int {
        return if (isDualEngineTransition) {
            if (transitionProgress < 0.5f) sourceColumns else targetColumns
        } else {
            sourceColumns
        }
    }

    /**
     * 重置状态
     */
    fun reset() {
        isDualEngineTransition = false
        sourceEngineParams = ListZoomLevels.params[ListZoomIndex.NORMAL]!!
        targetEngineParams = ListZoomLevels.params[ListZoomIndex.NORMAL]!!
        sourceColumns = 1
        targetColumns = 1
        transitionProgress = 1f
        transitionScaleFactor = 1f
        isZoomIn = true
    }

    companion object {
        /** 过渡状态常量 */
        const val STATE_IDLE = 0
        const val STATE_STARTED = 1
        const val STATE_IN_PROGRESS = 2
        const val STATE_DUAL_ENGINE_TRANSITION = 3
    }
}

/**
 * 在两个 ListZoomParams 之间插值
 * 对应原版 ListZoomLevel.kt 中的 lerpZoomParams()
 */
private fun lerpZoomParams(from: ListZoomParams, to: ListZoomParams, fraction: Float): ListZoomParams {
    val f = fraction.coerceIn(0f, 1f)
    return ListZoomParams(
        coverSizeDp = lerp(from.coverSizeDp, to.coverSizeDp, f),
        rowHeightValue = lerp(from.rowHeightValue, to.rowHeightValue, f),
        rowHeightIsSp = if (f < 0.5f) from.rowHeightIsSp else to.rowHeightIsSp,
        coverMarginLeftDp = lerp(from.coverMarginLeftDp, to.coverMarginLeftDp, f),
        coverMarginTopDp = lerp(from.coverMarginTopDp, to.coverMarginTopDp, f),
        coverMarginBottomDp = lerp(from.coverMarginBottomDp, to.coverMarginBottomDp, f),
        cornerRadiusTracksDp = lerp(from.cornerRadiusTracksDp, to.cornerRadiusTracksDp, f),
        cornerRadiusAlbumsDp = lerp(from.cornerRadiusAlbumsDp, to.cornerRadiusAlbumsDp, f),
        textMarginLeftDp = lerp(from.textMarginLeftDp, to.textMarginLeftDp, f),
        textMarginRightDp = lerp(from.textMarginRightDp, to.textMarginRightDp, f),
        line2Visible = if (f < 0.5f) from.line2Visible else to.line2Visible,
        metaVisible = if (f < 0.5f) from.metaVisible else to.metaVisible,
        titleTopOffsetDp = lerp(from.titleTopOffsetDp, to.titleTopOffsetDp, f),
        metaInlineFraction = lerp(from.metaInlineFraction, to.metaInlineFraction, f),
        textScale = lerp(from.textScale, to.textScale, f)
    )
}

private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
