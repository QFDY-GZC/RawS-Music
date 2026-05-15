package com.rawsmusic.core.common.ext

import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator

fun View.visible() {
    visibility = View.VISIBLE
}

fun View.invisible() {
    visibility = View.INVISIBLE
}

fun View.gone() {
    visibility = View.GONE
}

fun View.isVisible(): Boolean = visibility == View.VISIBLE

fun View.toggleVisibility() {
    if (isVisible()) gone() else visible()
}

fun View.fadeIn(duration: Long = 300) {
    alpha = 0f
    visible()
    animate()
        .alpha(1f)
        .setDuration(duration)
        .setInterpolator(DecelerateInterpolator())
        .start()
}

fun View.fadeOut(duration: Long = 300, onEnd: (() -> Unit)? = null) {
    animate()
        .alpha(0f)
        .setDuration(duration)
        .setInterpolator(DecelerateInterpolator())
        .withEndAction {
            gone()
            onEnd?.invoke()
        }
        .start()
}

fun View.scaleIn(duration: Long = 300) {
    scaleX = 0f
    scaleY = 0f
    visible()
    animate()
        .scaleX(1f)
        .scaleY(1f)
        .setDuration(duration)
        .setInterpolator(OvershootInterpolator())
        .start()
}

fun View.scaleOut(duration: Long = 300, onEnd: (() -> Unit)? = null) {
    animate()
        .scaleX(0f)
        .scaleY(0f)
        .setDuration(duration)
        .setInterpolator(DecelerateInterpolator())
        .withEndAction {
            gone()
            onEnd?.invoke()
        }
        .start()
}

fun View.setOnClickListenerWithDebounce(delay: Long = 500, onClick: (View) -> Unit) {
    var lastClickTime = 0L
    setOnClickListener {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastClickTime > delay) {
            lastClickTime = currentTime
            onClick(it)
        }
    }
}
