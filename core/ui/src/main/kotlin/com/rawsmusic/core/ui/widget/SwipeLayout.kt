package com.rawsmusic.core.ui.widget

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import com.rawsmusic.core.ui.animation.AnimParams
import com.rawsmusic.core.ui.animation.SpringAnimHelper
import kotlin.math.abs

class SwipeLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    var playerView: View? = null
    var lyricView: View? = null

    private var isLyricPageShown = false
    private var startY = 0f
    private var startX = 0f
    private var isDragging = false
    private var isVerticalSwipe = false

    var onLyricPageShown: (() -> Unit)? = null
    var onLyricPageHidden: (() -> Unit)? = null
    var onSwipeProgress: ((progress: Float) -> Unit)? = null

    private val swipeThreshold by lazy {
        height * AnimParams.SWIPE_THRESHOLD_RATIO
    }

    fun showLyricPage(animated: Boolean = true) {
        if (isLyricPageShown) return
        isLyricPageShown = true
        lyricView?.visibility = View.VISIBLE

        if (animated) {
            playerView?.animate()
                ?.translationY(-height * 0.1f)
                ?.alpha(0f)
                ?.setDuration(AnimParams.PAGE_TRANSITION_DURATION)
                ?.setInterpolator(android.view.animation.DecelerateInterpolator(1.5f))
                ?.withEndAction { playerView?.visibility = View.GONE }
                ?.start()

            lyricView?.translationY = height.toFloat()
            lyricView?.alpha = 0f
            lyricView?.animate()
                ?.translationY(0f)
                ?.alpha(1f)
                ?.setDuration(AnimParams.PAGE_TRANSITION_DURATION)
                ?.setInterpolator(android.view.animation.DecelerateInterpolator(1.5f))
                ?.start()
        } else {
            playerView?.visibility = View.GONE
            lyricView?.translationY = 0f
            lyricView?.alpha = 1f
        }
        onLyricPageShown?.invoke()
    }

    fun hideLyricPage(animated: Boolean = true) {
        if (!isLyricPageShown) return
        isLyricPageShown = false

        if (animated) {
            lyricView?.animate()
                ?.translationY(height.toFloat())
                ?.alpha(0f)
                ?.setDuration(AnimParams.PAGE_TRANSITION_DURATION)
                ?.setInterpolator(android.view.animation.AccelerateInterpolator())
                ?.withEndAction { lyricView?.visibility = View.GONE }
                ?.start()

            playerView?.visibility = View.VISIBLE
            playerView?.translationY = -height * 0.1f
            playerView?.alpha = 0f
            playerView?.animate()
                ?.translationY(0f)
                ?.alpha(1f)
                ?.setDuration(AnimParams.PAGE_TRANSITION_DURATION)
                ?.setInterpolator(android.view.animation.DecelerateInterpolator(1.5f))
                ?.start()
        } else {
            lyricView?.visibility = View.GONE
            playerView?.visibility = View.VISIBLE
            playerView?.translationY = 0f
            playerView?.alpha = 1f
        }
        onLyricPageHidden?.invoke()
    }

    fun isLyricVisible(): Boolean = isLyricPageShown

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                startY = ev.rawY
                startX = ev.rawX
                isDragging = false
                isVerticalSwipe = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = ev.rawY - startY
                val dx = ev.rawX - startX

                if (!isVerticalSwipe && abs(dy) > abs(dx) && abs(dy) > 20) {
                    isVerticalSwipe = true
                    if (shouldInterceptSwipe(dy)) {
                        isDragging = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                }
            }
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                startY = ev.rawY
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = startY - ev.rawY
                handleSwipeMove(dy)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handleSwipeEnd()
                isDragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(ev)
    }

    private fun shouldInterceptSwipe(dy: Float): Boolean {
        return if (isLyricPageShown) {
            dy < 0
        } else {
            dy > 0
        }
    }

    private fun handleSwipeMove(dy: Float) {
        val progress = (abs(dy) / height).coerceIn(0f, 1f)
        onSwipeProgress?.invoke(progress)

        if (!isLyricPageShown) {
            playerView?.translationY = -dy * 0.1f
            playerView?.alpha = 1f - progress * 0.5f

            lyricView?.visibility = View.VISIBLE
            lyricView?.translationY = (1f - progress) * height * 0.5f
            lyricView?.alpha = progress
        } else {
            lyricView?.translationY = -dy * 0.5f
            lyricView?.alpha = 1f - progress * 0.5f

            playerView?.visibility = View.VISIBLE
            playerView?.translationY = -(1f - progress) * height * 0.1f
            playerView?.alpha = progress
        }
    }

    private fun handleSwipeEnd() {
        val player = playerView ?: return
        val lyric = lyricView ?: return
        val playerTransY = player.translationY
        val lyricTransY = lyric.translationY

        if (!isLyricPageShown) {
            if (abs(playerTransY) > swipeThreshold) {
                showLyricPage(true)
            } else {
                SpringAnimHelper.underdampedTranslationY(player, 0f)
                player.animate().alpha(1f).setDuration(200).start()
                lyric.animate().alpha(0f).setDuration(200)
                    .withEndAction { lyric.visibility = View.GONE }.start()
            }
        } else {
            if (lyricTransY > swipeThreshold) {
                hideLyricPage(true)
            } else {
                SpringAnimHelper.underdampedTranslationY(lyric, 0f)
                lyric.animate().alpha(1f).setDuration(200).start()
                player.animate().alpha(0f).setDuration(200)
                    .withEndAction { player.visibility = View.GONE }.start()
            }
        }
    }
}
