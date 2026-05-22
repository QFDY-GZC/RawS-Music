package io.github.proify.lyricon.lyric.view

import android.animation.ValueAnimator
import android.view.View
import android.view.animation.OvershootInterpolator
import kotlin.math.abs

object WordAnimator {
    fun bounce(view: View, upDp: Float = 3f, scale: Float = 1.1f, duration: Long = 150L) {
        view.animate()
            .translationY(-upDp.dp)
            .scaleX(scale)
            .scaleY(scale)
            .setDuration(duration)
            .setInterpolator(OvershootInterpolator(1.5f))
            .withEndAction {
                view.animate()
                    .translationY(0f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(duration)
                    .start()
            }
            .start()
    }
}