package com.rawsmusic.core.ui.animation

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.rawsmusic.core.ui.animation.AnimParams

object SpringAnimHelper {

    fun createSpringAnimation(
        view: View,
        property: androidx.dynamicanimation.animation.FloatPropertyCompat<View>,
        finalPosition: Float,
        stiffness: Float = SpringForce.STIFFNESS_MEDIUM,
        dampingRatio: Float = SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY
    ): SpringAnimation {
        val springForce = SpringForce(finalPosition).apply {
            this.stiffness = stiffness
            this.dampingRatio = dampingRatio
        }
        return SpringAnimation(view, property).apply {
            spring = springForce
        }
    }

    fun underdampedScale(view: View, targetScale: Float) {
        createSpringAnimation(
            view,
            androidx.dynamicanimation.animation.DynamicAnimation.SCALE_X,
            targetScale,
            stiffnessFromParams(),
            dampingFromParams()
        ).start()
        createSpringAnimation(
            view,
            androidx.dynamicanimation.animation.DynamicAnimation.SCALE_Y,
            targetScale,
            stiffnessFromParams(),
            dampingFromParams()
        ).start()
    }

    fun underdampedTranslationY(view: View, targetY: Float) {
        createSpringAnimation(
            view,
            androidx.dynamicanimation.animation.DynamicAnimation.TRANSLATION_Y,
            targetY,
            stiffnessFromParams(),
            dampingFromParams()
        ).start()
    }

    fun underdampedTranslationX(view: View, targetX: Float) {
        createSpringAnimation(
            view,
            androidx.dynamicanimation.animation.DynamicAnimation.TRANSLATION_X,
            targetX,
            stiffnessFromParams(),
            dampingFromParams()
        ).start()
    }

    private fun stiffnessFromParams(): Float {
        return AnimParams.SPRING_STIFFNESS * SpringForce.STIFFNESS_MEDIUM
    }

    private fun dampingFromParams(): Float {
        return AnimParams.SPRING_DAMPING
    }
}

object ButtonAnimHelper {

    private val animLocks = mutableMapOf<Int, Boolean>()

    fun pressAnim(view: View, scale: Float = AnimParams.BUTTON_PRESS_SCALE) {
        view.animate()
            .scaleX(scale)
            .scaleY(scale)
            .setDuration(AnimParams.BUTTON_PRESS_DURATION)
            .setInterpolator(AccelerateInterpolator())
            .start()
    }

    fun releaseAnim(
        view: View,
        scale: Float = 1f,
        overshoot: Float = AnimParams.BUTTON_RELEASE_OVERSHOOT,
        onEnd: (() -> Unit)? = null
    ) {
        view.animate()
            .scaleX(scale)
            .scaleY(scale)
            .setDuration(AnimParams.BUTTON_RELEASE_DURATION)
            .setInterpolator(OvershootInterpolator(overshoot))
            .withEndAction { onEnd?.invoke() }
            .start()
    }

    fun pressReleaseAnim(
        view: View,
        pressScale: Float = AnimParams.BUTTON_PRESS_SCALE,
        onEnd: (() -> Unit)? = null
    ) {
        pressAnim(view, pressScale)
        view.postDelayed({
            releaseAnim(view, onEnd = onEnd)
        }, AnimParams.BUTTON_PRESS_DURATION)
    }

    fun secondaryPressAnim(view: View) {
        view.animate()
            .alpha(AnimParams.SECONDARY_PRESS_ALPHA)
            .scaleX(AnimParams.SECONDARY_PRESS_SCALE)
            .scaleY(AnimParams.SECONDARY_PRESS_SCALE)
            .setDuration(AnimParams.BUTTON_PRESS_DURATION)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                view.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(AnimParams.BUTTON_RELEASE_DURATION)
                    .setInterpolator(OvershootInterpolator(0.8f))
                    .start()
            }
            .start()
    }

    fun playPauseAnim(
        view: View,
        isPlaying: Boolean,
        onAnimEnd: (() -> Unit)? = null
    ) {
        val viewId = view.hashCode()
        if (animLocks[viewId] == true) return
        animLocks[viewId] = true

        view.animate()
            .scaleX(AnimParams.PLAY_BUTTON_PRESS_SCALE)
            .scaleY(AnimParams.PLAY_BUTTON_PRESS_SCALE)
            .setDuration(AnimParams.BUTTON_PRESS_DURATION / 2)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                onAnimEnd?.invoke()
                view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(AnimParams.BUTTON_RELEASE_DURATION)
                    .setInterpolator(OvershootInterpolator(0.6f))
                    .withEndAction {
                        animLocks[viewId] = false
                    }
                    .start()
            }
            .start()
    }

    fun rippleExpandAnim(view: View) {
        view.alpha = 0.4f
        view.scaleX = 0.5f
        view.scaleY = 0.5f
        view.visibility = View.VISIBLE
        view.animate()
            .alpha(0f)
            .scaleX(2f)
            .scaleY(2f)
            .setDuration(AnimParams.RIPPLE_DURATION)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                view.visibility = View.GONE
            }
            .start()
    }

    fun skipAnim(view: View, isNext: Boolean) {
        val shiftX = if (isNext) -AnimParams.ICON_SHIFT_OFFSET else AnimParams.ICON_SHIFT_OFFSET
        view.animate()
            .translationX(shiftX)
            .setDuration(80)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                view.animate()
                    .translationX(0f)
                    .setDuration(AnimParams.BUTTON_RELEASE_DURATION)
                    .setInterpolator(OvershootInterpolator(0.5f))
                    .start()
            }
            .start()
    }

    fun coverSwitchAnim(view: View, isNext: Boolean) {
        view.animate().cancel()
        view.alpha = 1f
        view.translationX = 0f
        val shiftX = if (isNext) -AnimParams.COVER_SHIFT_OFFSET else AnimParams.COVER_SHIFT_OFFSET
        view.animate()
            .translationX(shiftX)
            .alpha(0.5f)
            .setDuration(AnimParams.COVER_TRANSITION_DURATION / 2)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                view.translationX = -shiftX
                view.animate()
                    .translationX(0f)
                    .alpha(1f)
                    .setDuration(AnimParams.COVER_TRANSITION_DURATION / 2)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
            .start()
    }
}

object PageTransitionHelper {

    fun slideUpEnter(view: View, onEnd: (() -> Unit)? = null) {
        view.translationY = view.height.toFloat()
        view.alpha = 0f
        view.visibility = View.VISIBLE
        view.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(AnimParams.PAGE_TRANSITION_DURATION)
            .setInterpolator(DecelerateInterpolator(1.5f))
            .withEndAction { onEnd?.invoke() }
            .start()
    }

    fun slideDownExit(view: View, onEnd: (() -> Unit)? = null) {
        view.animate()
            .translationY(view.height.toFloat())
            .alpha(0f)
            .setDuration(AnimParams.PAGE_TRANSITION_DURATION)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                view.visibility = View.GONE
                onEnd?.invoke()
            }
            .start()
    }

    fun fadeOutUp(view: View, onEnd: (() -> Unit)? = null) {
        view.animate()
            .translationY(-view.height * 0.1f)
            .alpha(0f)
            .setDuration(AnimParams.PAGE_TRANSITION_DURATION)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                view.visibility = View.GONE
                onEnd?.invoke()
            }
            .start()
    }

    fun fadeInDown(view: View, onEnd: (() -> Unit)? = null) {
        view.translationY = -view.height * 0.1f
        view.alpha = 0f
        view.visibility = View.VISIBLE
        view.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(AnimParams.PAGE_TRANSITION_DURATION)
            .setInterpolator(DecelerateInterpolator(1.5f))
            .withEndAction { onEnd?.invoke() }
            .start()
    }

    fun crossFade(outView: View, inView: View) {
        outView.animate()
            .alpha(0f)
            .setDuration(AnimParams.FADE_DURATION)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction { outView.visibility = View.GONE }
            .start()

        inView.alpha = 0f
        inView.visibility = View.VISIBLE
        inView.animate()
            .alpha(1f)
            .setDuration(AnimParams.FADE_DURATION)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }
}
