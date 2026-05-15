package com.rawsmusic.core.ui.widget

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * 竖向胶囊卡片 — 点击三点按钮展开
 * 宽度与三点按钮一致，内含元数据图标，点击图标触发回调打开详情界面
 */
class MetadataCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val iconView: ImageView

    var isExpanded = false
        private set

    /** 点击元数据图标回调 */
    var onMetadataClick: (() -> Unit)? = null

    init {
        val capsuleWidth = (36 * density).toInt() // 与btnMore宽度一致

        // 胶囊背景
        val bgDrawable = GradientDrawable().apply {
            setColor(Color.argb(220, 30, 27, 24))
            cornerRadius = 18f * density
        }

        // 胶囊容器
        val capsule = FrameLayout(context).apply {
            background = bgDrawable
            layoutParams = LayoutParams(capsuleWidth, LayoutParams.WRAP_CONTENT)
            setPadding(
                (4 * density).toInt(),
                (8 * density).toInt(),
                (4 * density).toInt(),
                (8 * density).toInt()
            )
        }

        // 元数据图标
        iconView = ImageView(context).apply {
            setImageResource(com.rawsmusic.core.ui.R.drawable.ic_info_green)
            layoutParams = FrameLayout.LayoutParams(
                (28 * density).toInt(),
                (28 * density).toInt()
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                onMetadataClick?.invoke()
            }
        }

        capsule.addView(iconView)
        addView(capsule)

        visibility = View.GONE
    }

    /**
     * 展开胶囊（带动画）
     */
    fun expand(anchorX: Float, anchorY: Float) {
        if (isExpanded) return
        isExpanded = true
        visibility = View.VISIBLE
        alpha = 0f
        scaleY = 0.3f
        pivotX = width / 2f
        pivotY = 0f
        animate()
            .alpha(1f)
            .scaleY(1f)
            .setDuration(200)
            .setInterpolator(android.view.animation.OvershootInterpolator(0.6f))
            .start()
    }

    /**
     * 收起胶囊（带动画）
     */
    fun collapse() {
        if (!isExpanded) return
        isExpanded = false
        animate()
            .alpha(0f)
            .scaleY(0.3f)
            .setDuration(150)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction {
                visibility = View.GONE
            }
            .start()
    }

    /**
     * 切换展开/收起
     */
    fun toggle(anchorX: Float, anchorY: Float) {
        if (isExpanded) collapse() else expand(anchorX, anchorY)
    }
}
