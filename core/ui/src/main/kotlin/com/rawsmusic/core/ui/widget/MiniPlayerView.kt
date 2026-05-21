package com.rawsmusic.core.ui.widget

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import coil.Coil
import coil.ImageLoader
import coil.request.ImageRequest
import com.rawsmusic.core.ui.R
import com.rawsmusic.core.ui.animation.ButtonAnimHelper
import kotlin.math.abs

class MiniPlayerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    val ivCover: ImageView
    val tvLyricOriginal: TextView
    val tvLyricTranslation: TextView
    val tvRemainingTime: TextView
    val btnPlayPause: ImageView

    var onPlayPauseClick: (() -> Unit)? = null
    var onNextClick: (() -> Unit)? = null
    var onPreviousClick: (() -> Unit)? = null
    var onBarClick: (() -> Unit)? = null

    private val density = resources.displayMetrics.density

    private var touchDownTime = 0L
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var isHorizontalSwipe = false

    private var isChineseLyric = false
    private var isRotating = false

    private val rotateAnimator by lazy {
        ObjectAnimator.ofFloat(ivCover, View.ROTATION, 0f, 360f).apply {
            duration = 20000L
            repeatCount = ObjectAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
        }
    }

    private val isDarkMode: Boolean
        get() = com.rawsmusic.core.ui.theme.ThemeManager.isDarkMode(context)

    private val textPrimary: Int
        get() = if (isDarkMode) 0xFFE6E1DD.toInt() else 0xFF1C1B1F.toInt()

    private val textSecondary: Int
        get() = if (isDarkMode) 0xFF9F8D80.toInt() else 0xFF49454F.toInt()

    init {
        val coverSize = (52 * density).toInt()
        val padding = (10 * density).toInt()
        val iconSize = (28 * density).toInt()
        val touchArea = (48 * density).toInt()

        val glassBackground = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LiquidGlassMiniPlayerBg()
            }
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(padding, (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }

        ivCover = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(coverSize, coverSize)
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageDrawable(null)
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            clipToOutline = true
        }

        val lyricContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                marginStart = (12 * density).toInt()
                marginEnd = (8 * density).toInt()
            }
        }

        tvLyricOriginal = TextView(context).apply {
            setTextColor(textPrimary)
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = -1
            isSingleLine = true
            isSelected = true
            isFocusable = true
            isFocusableInTouchMode = true
            text = "暂无音乐播放"
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
        }

        tvLyricTranslation = TextView(context).apply {
            setTextColor(textSecondary)
            textSize = 11f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = -1
            isSingleLine = true
            isSelected = true
            isFocusable = true
            isFocusableInTouchMode = true
            text = ""
            visibility = View.GONE
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
        }

        tvRemainingTime = TextView(context).apply {
            setTextColor(textSecondary)
            textSize = 10f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            text = ""
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
        }

        lyricContainer.addView(tvLyricOriginal)
        lyricContainer.addView(tvLyricTranslation)
        lyricContainer.addView(tvRemainingTime)

        btnPlayPause = ImageView(context).apply {
            setImageResource(R.drawable.ic_play)
            setColorFilter(textPrimary)
            layoutParams = LinearLayout.LayoutParams(touchArea, touchArea)
            setPadding((touchArea - iconSize) / 2, (touchArea - iconSize) / 2, (touchArea - iconSize) / 2, (touchArea - iconSize) / 2)
            setOnClickListener {
                ButtonAnimHelper.pressReleaseAnim(it, 0.96f)
                onPlayPauseClick?.invoke()
            }
        }

        container.addView(ivCover)
        container.addView(lyricContainer)
        container.addView(btnPlayPause)

        val cornerRadius = 24f * density
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, cornerRadius)
            }
        }
        clipToOutline = true

        addView(glassBackground)
        addView(container)

        isClickable = false
    }

    fun setCoverImage(path: String?) {
        val imageLoader = Coil.imageLoader(context)
        val request = ImageRequest.Builder(context)
            .data(path)
            .size(256)
            .allowHardware(false)
            .target(
                onStart = {},
                onSuccess = { drawable ->
                    if (!isAttachedToWindow) return@target
                    ivCover.setImageDrawable(drawable)
                    ivCover.clearColorFilter()
                },
                onError = {
                    if (!isAttachedToWindow) return@target
                    ivCover.setImageDrawable(null)
                }
            )
            .build()
        imageLoader.enqueue(request)
    }

    override fun onInterceptTouchEvent(ev: android.view.MotionEvent): Boolean {
        return true
    }

    override fun onTouchEvent(ev: android.view.MotionEvent): Boolean {
        val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                touchDownTime = System.currentTimeMillis()
                touchDownX = ev.rawX
                touchDownY = ev.rawY
                isHorizontalSwipe = false
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                val dx = ev.rawX - touchDownX
                val dy = ev.rawY - touchDownY
                if (!isHorizontalSwipe && abs(dx) > touchSlop && abs(dx) > abs(dy)) {
                    isHorizontalSwipe = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
            android.view.MotionEvent.ACTION_UP -> {
                val dx = ev.rawX - touchDownX
                val dy = ev.rawY - touchDownY
                val dt = System.currentTimeMillis() - touchDownTime
                val swipeThreshold = 60f * density

                if (isHorizontalSwipe && abs(dx) > swipeThreshold) {
                    if (dx > 0) {
                        onNextClick?.invoke()
                    } else {
                        onPreviousClick?.invoke()
                    }
                } else {
                    val isTap = dt < 300 && dx * dx + dy * dy < (20 * density) * (20 * density)
                    if (isTap) {
                        when {
                            isTouchInsideView(ev.rawX, ev.rawY, btnPlayPause) -> {
                                ButtonAnimHelper.pressReleaseAnim(btnPlayPause, 0.96f)
                                onPlayPauseClick?.invoke()
                            }
                            else -> {
                                onBarClick?.invoke()
                            }
                        }
                    }
                }
                isHorizontalSwipe = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            android.view.MotionEvent.ACTION_CANCEL -> {
                isHorizontalSwipe = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    private fun isTouchInsideView(rawX: Float, rawY: Float, view: View): Boolean {
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        return rawX >= loc[0] && rawX <= loc[0] + view.width &&
                rawY >= loc[1] && rawY <= loc[1] + view.height
    }

    fun setPlaying(isPlaying: Boolean) {
        btnPlayPause.setImageResource(
            if (isPlaying) R.drawable.ic_pause
            else R.drawable.ic_play
        )
        if (isPlaying && !isRotating) {
            isRotating = true
            rotateAnimator.start()
        } else if (!isPlaying && isRotating) {
            isRotating = false
            rotateAnimator.cancel()
        }
    }

    fun setSongInfo(title: String, artist: String) {
        if (tvLyricOriginal.text?.toString() != title) {
            tvLyricOriginal.text = title
        }
        if (tvLyricTranslation.text?.toString() != artist) {
            tvLyricTranslation.text = artist
        }
        tvLyricOriginal.ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
        tvLyricOriginal.marqueeRepeatLimit = -1
        tvLyricOriginal.isSingleLine = true
        tvLyricOriginal.isFocusable = true
        tvLyricOriginal.isFocusableInTouchMode = true
        tvLyricOriginal.isSelected = true
        tvLyricOriginal.requestFocus()
        tvLyricTranslation.visibility = if (artist.isBlank()) View.GONE else View.VISIBLE
    }

    fun updateLyric(original: String?, translation: String?, isDualLine: Boolean = false, isChinese: Boolean = false) {
        isChineseLyric = isChinese
        if (original.isNullOrBlank()) {
            tvLyricTranslation.visibility = View.GONE
        } else {
            val isLyricChanged = tvLyricOriginal.text?.toString() != original ||
                    tvLyricTranslation.text?.toString() != (translation ?: "")
            if (isLyricChanged) {
                animateLyricSwitch(original, translation, isDualLine, isChinese)
            } else {
                applyLyricLayout(original, translation, isDualLine, isChinese)
            }
        }
    }

    private fun animateLyricSwitch(original: String, translation: String?, isDualLine: Boolean, isChinese: Boolean) {
        val duration = 150L
        tvLyricOriginal.animate()
            .alpha(0f)
            .translationX(-20f * density)
            .setDuration(duration)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction {
                applyLyricLayout(original, translation, isDualLine, isChinese)
                tvLyricOriginal.translationX = 20f * density
                tvLyricOriginal.alpha = 0f
                tvLyricOriginal.animate()
                    .alpha(1f)
                    .translationX(0f)
                    .setDuration(250L)
                    .setInterpolator(android.view.animation.OvershootInterpolator(0.8f))
                    .withEndAction {
                        tvLyricOriginal.isSelected = true
                        tvLyricOriginal.requestFocus()
                        if (tvLyricTranslation.visibility == View.VISIBLE) {
                            tvLyricTranslation.isSelected = true
                        }
                    }
                    .start()

                if (tvLyricTranslation.visibility == View.VISIBLE) {
                    tvLyricTranslation.translationX = 20f * density
                    tvLyricTranslation.alpha = 0f
                    tvLyricTranslation.animate()
                        .alpha(1f)
                        .translationX(0f)
                        .setDuration(250L)
                        .setInterpolator(android.view.animation.OvershootInterpolator(0.8f))
                        .start()
                }
            }
            .start()

        if (tvLyricTranslation.visibility == View.VISIBLE) {
            tvLyricTranslation.animate()
                .alpha(0f)
                .translationX(-20f * density)
                .setDuration(duration)
                .setInterpolator(android.view.animation.AccelerateInterpolator())
                .start()
        }
    }

    private fun applyLyricLayout(original: String, translation: String?, isDualLine: Boolean, isChinese: Boolean) {
        tvLyricOriginal.text = original
        tvLyricOriginal.ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
        tvLyricOriginal.marqueeRepeatLimit = -1
        tvLyricOriginal.isSingleLine = true
        tvLyricOriginal.isFocusable = true
        tvLyricOriginal.isFocusableInTouchMode = true
        tvLyricOriginal.isSelected = true
        tvLyricOriginal.requestFocus()
        if (translation.isNullOrBlank() || !isDualLine) {
            tvLyricOriginal.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            tvLyricTranslation.visibility = View.GONE
        } else {
            tvLyricOriginal.gravity = Gravity.START or Gravity.BOTTOM
            tvLyricTranslation.text = translation
            tvLyricTranslation.ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
            tvLyricTranslation.marqueeRepeatLimit = -1
            tvLyricTranslation.isSingleLine = true
            tvLyricTranslation.isFocusable = true
            tvLyricTranslation.isFocusableInTouchMode = true
            tvLyricTranslation.isSelected = true
            tvLyricTranslation.visibility = View.VISIBLE
        }
    }

    fun updateRemainingTime(remainingMs: Long) {
        val totalSeconds = remainingMs / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        tvRemainingTime.text = "剩余$minutes:${String.format("%02d", seconds)}秒"
    }
}
