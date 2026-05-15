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
import android.widget.ImageView
import androidx.interpolator.view.animation.FastOutSlowInInterpolator

object Animations {

    fun playBounceAnimation(view: View) {
        ObjectAnimator.ofFloat(view, "scaleX", 1f, 1.2f, 1f).apply {
            duration = 300
            interpolator = OvershootInterpolator()
            start()
        }
        ObjectAnimator.ofFloat(view, "scaleY", 1f, 1.2f, 1f).apply {
            duration = 300
            interpolator = OvershootInterpolator()
            start()
        }
    }

    fun rotateAnimation(view: ImageView, isPlaying: Boolean) {
        val animator = view.tag as? ObjectAnimator
        if (isPlaying) {
            if (animator == null) {
                val rotate = ObjectAnimator.ofFloat(view, "rotation", 0f, 360f).apply {
                    duration = 8000
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = AccelerateDecelerateInterpolator()
                }
                view.tag = rotate
                rotate.start()
            } else if (!animator.isRunning) {
                animator.resume()
            }
        } else {
            animator?.pause()
        }
    }

    fun slideInFromBottom(view: View, duration: Long = 300) {
        view.translationY = view.height.toFloat()
        view.alpha = 0f
        view.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(duration)
            .setInterpolator(FastOutSlowInInterpolator())
            .start()
    }

    fun slideOutToBottom(view: View, duration: Long = 300, onEnd: (() -> Unit)? = null) {
        view.animate()
            .translationY(view.height.toFloat())
            .alpha(0f)
            .setDuration(duration)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction { onEnd?.invoke() }
            .start()
    }

    fun slideInFromRight(view: View, duration: Long = 300) {
        view.translationX = view.width.toFloat()
        view.alpha = 0f
        view.animate()
            .translationX(0f)
            .alpha(1f)
            .setDuration(duration)
            .setInterpolator(FastOutSlowInInterpolator())
            .start()
    }

    fun crossFade(inView: View, outView: View, duration: Long = 300) {
        inView.alpha = 0f
        inView.visibility = View.VISIBLE

        inView.animate()
            .alpha(1f)
            .setDuration(duration)
            .setInterpolator(DecelerateInterpolator())
            .start()

        outView.animate()
            .alpha(0f)
            .setDuration(duration)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction { outView.visibility = View.GONE }
            .start()
    }
}
