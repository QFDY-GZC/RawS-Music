package com.rawsmusic.ui.settings.compose.scene.gesture

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Poweramp 缓动函数
 * 直接复用原版 ListZoomManager.powerampEasing()
 *
 * 用于计算弹性缩放效果
 */
object PowerampEasing {
    /**
     * Poweramp 缓动函数
     * 对应原版 ListZoomManager.powerampEasing()
     *
     * @param d 归一化距离 (-3..3)
     * @return 缓动值 (-0.2..0.2)
     */
    fun powerampEasing(d: Float): Float {
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

    /**
     * 过渡中的弹性缩放因子
     * 对应原版 ListZoomManager.powerampElasticScale()
     *
     * @param signedDelta 有符号增量
     * @param isZoomIn 是否为放大方向
     * @return 弹性缩放因子 (0.85..1.15)
     */
    fun powerampElasticScale(signedDelta: Float, isZoomIn: Boolean): Float {
        if (signedDelta in 0f..1f) return 1f
        val beyond = if (signedDelta > 1f) signedDelta - 1f else -signedDelta
        val eased = abs(powerampEasing(beyond))
        return if ((signedDelta > 1f) == isZoomIn) {
            1f + eased
        } else {
            1f - eased
        }.coerceIn(0.85f, 1.15f)
    }

    /**
     * 边界弹性缩放因子
     * 对应原版 ListZoomManager.computeBoundaryElasticScale()
     *
     * @param overPull 过拉距离
     * @param expands 是否为放大方向
     * @return 弹性缩放因子
     */
    fun computeBoundaryElasticScale(overPull: Float, expands: Boolean): Float {
        val eased = abs(powerampEasing(overPull))
        return if (expands) 1f + eased else 1f - eased
    }

    /**
     * 判断是否应该提交过渡
     * 对应原版 ListZoomManager.finishPinch() 中的 shouldConfirm 逻辑
     *
     * @param velocity 速度 (dp/s)
     * @param progress 过渡进度 (0..1)
     * @param isZoomIn 是否为放大方向
     * @return 是否应该提交
     */
    fun shouldConfirmTransition(velocity: Float, progress: Float, isZoomIn: Boolean): Boolean {
        return if (abs(velocity) >= 500f) {
            (velocity > 0f) == isZoomIn
        } else {
            progress > 0.3f
        }
    }

    /**
     * 计算释放时的进度速度
     * 对应原版 ListZoomManager.finishPinch() 中的 releaseProgressVelocity 逻辑
     *
     * @param progress 过渡进度
     * @param velocity 速度
     * @param ratioVelocity 比率速度
     * @param isZoomIn 是否为放大方向
     * @return 释放进度速度
     */
    fun computeReleaseProgressVelocity(
        progress: Float,
        velocity: Float,
        ratioVelocity: Float,
        isZoomIn: Boolean
    ): Float {
        val shouldConfirm = shouldConfirmTransition(velocity, progress, isZoomIn)
        val velocityTowardEnd = (velocity > 0f) == isZoomIn
        return when {
            shouldConfirm && velocityTowardEnd && progress > 0.8f -> abs(ratioVelocity) / 4f
            !shouldConfirm && !velocityTowardEnd && progress < 0.2f -> -abs(ratioVelocity) / 4f
            else -> 0f
        }
    }
}
