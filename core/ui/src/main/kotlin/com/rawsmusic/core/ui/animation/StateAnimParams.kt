package com.rawsmusic.core.ui.animation

import android.view.View

/**
 * 视图属性快照 — 状态驱动的动画基础设施
 *
 * 核心思想：不写死"从 A 到 B"的动画，而是定义"状态"，
 * 由系统自动读取 View 当前属性作为起始值，按进度插值到目标状态。
 */
class ViewSnapshot {

    var alpha: Float = 1f
    var scaleX: Float = 1f
    var scaleY: Float = 1f
    var translationX: Float = 0f
    var translationY: Float = 0f
    var visibility: Int = View.VISIBLE

    /** 哪些属性需要动画（位掩码） */
    var animatedProps: Int = 0

    companion object {
        const val PROP_ALPHA = 1 shl 0
        const val PROP_SCALE_X = 1 shl 1
        const val PROP_SCALE_Y = 1 shl 2
        const val PROP_TRANSLATION_X = 1 shl 3
        const val PROP_TRANSLATION_Y = 1 shl 4
        const val PROP_VISIBILITY = 1 shl 5

        /** 便捷：全部属性 */
        const val PROP_ALL = PROP_ALPHA or PROP_SCALE_X or PROP_SCALE_Y or
                PROP_TRANSLATION_X or PROP_TRANSLATION_Y or PROP_VISIBILITY

        /** 便捷：位置+缩放（页面转场常用） */
        const val PROP_TRANSFORM = PROP_SCALE_X or PROP_SCALE_Y or
                PROP_TRANSLATION_X or PROP_TRANSLATION_Y

        /** 便捷：仅位置 */
        const val PROP_POSITION = PROP_TRANSLATION_X or PROP_TRANSLATION_Y

        // 动画参数 — 对应 Poweramp 的 DecelerateInterpolator(2.0f)
        const val PAGE_DECELERATE_FACTOR = 2.0f
        const val SCENE_ANIM_DURATION = 300L
    }

    /** 从 View 读取当前状态 */
    fun captureFrom(view: View, props: Int = PROP_ALL) {
        animatedProps = props
        if (props and PROP_ALPHA != 0) alpha = view.alpha
        if (props and PROP_SCALE_X != 0) scaleX = view.scaleX
        if (props and PROP_SCALE_Y != 0) scaleY = view.scaleY
        if (props and PROP_TRANSLATION_X != 0) translationX = view.translationX
        if (props and PROP_TRANSLATION_Y != 0) translationY = view.translationY
        if (props and PROP_VISIBILITY != 0) visibility = view.visibility
    }

    /** 应用到 View */
    fun applyTo(view: View) {
        if (animatedProps and PROP_ALPHA != 0) view.alpha = alpha
        if (animatedProps and PROP_SCALE_X != 0) view.scaleX = scaleX
        if (animatedProps and PROP_SCALE_Y != 0) view.scaleY = scaleY
        if (animatedProps and PROP_TRANSLATION_X != 0) view.translationX = translationX
        if (animatedProps and PROP_TRANSLATION_Y != 0) view.translationY = translationY
        if (animatedProps and PROP_VISIBILITY != 0) view.visibility = visibility
    }

    /** 在两个快照之间按进度插值并应用到 View */
    fun interpolateAndApply(from: ViewSnapshot, to: ViewSnapshot, progress: Float, view: View) {
        if (from.animatedProps and PROP_ALPHA != 0) {
            view.alpha = from.alpha + (to.alpha - from.alpha) * progress
        }
        if (from.animatedProps and PROP_SCALE_X != 0) {
            view.scaleX = from.scaleX + (to.scaleX - from.scaleX) * progress
        }
        if (from.animatedProps and PROP_SCALE_Y != 0) {
            view.scaleY = from.scaleY + (to.scaleY - from.scaleY) * progress
        }
        if (from.animatedProps and PROP_TRANSLATION_X != 0) {
            view.translationX = from.translationX + (to.translationX - from.translationX) * progress
        }
        if (from.animatedProps and PROP_TRANSLATION_Y != 0) {
            view.translationY = from.translationY + (to.translationY - from.translationY) * progress
        }
    }
}
