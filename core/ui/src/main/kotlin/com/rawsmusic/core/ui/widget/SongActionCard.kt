package com.rawsmusic.core.ui.widget

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.rawsmusic.core.ui.R

/**
 * 歌曲操作卡片 — 悬浮磨砂模糊样式
 *
 * 长按歌曲时悬浮显示（不填充底部屏幕）
 * - 磨砂模糊背景 + R角矩形
 * - 顶部：全选键(含勾选框图标) + 歌曲计数(x/x) + 关闭
 * - 底部：操作按钮横排（下一首播放、添加到歌单、删除）
 * - 全选点击后变为反选
 */
class SongActionCard @JvmOverloads constructor(
    context: Context
) : FrameLayout(context) {

    var onPlayNext: (() -> Unit)? = null
    var onAddToPlaylist: (() -> Unit)? = null
    var onDelete: (() -> Unit)? = null
    var onClose: (() -> Unit)? = null
    var onSelectAll: (() -> Unit)? = null
    var onDeselectAll: (() -> Unit)? = null

    var songCount: Int = 0
        set(value) {
            field = value
            updateCountText()
        }

    var selectedCount: Int = 0
        set(value) {
            field = value
            updateCountText()
        }

    var isAllSelected: Boolean = false
        private set

    private val density = resources.displayMetrics.density
    private var tvSongCount: TextView? = null
    private var btnSelectAll: View? = null

    /** 侧滑边缘手势回调 */
    var onSwipeDismiss: (() -> Unit)? = null

    /** 封面背景色 — 与歌曲/歌词界面统一取色 */
    private var coverPrimaryColor: Int = Color.argb(160, 20, 16, 12)
    private var coverDarkColor: Int = Color.argb(200, 10, 8, 6)

    /** 设置封面取色 — 与歌曲/歌词界面背景统一 */
    fun setCoverColors(primary: Int, dark: Int) {
        coverPrimaryColor = primary
        coverDarkColor = dark
        // 实时更新模糊背景色：使用主色调叠加暗色调，半透明毛玻璃
        val blendedColor = Color.argb(
            200,  // 不透明度
            (Color.red(dark) * 0.6f + Color.red(primary) * 0.4f).toInt().coerceIn(0, 255),
            (Color.green(dark) * 0.6f + Color.green(primary) * 0.4f).toInt().coerceIn(0, 255),
            (Color.blue(dark) * 0.6f + Color.blue(primary) * 0.4f).toInt().coerceIn(0, 255)
        )
        bgBlurView?.surfaceColor = blendedColor
    }

    private var bgBlurView: LiquidGlassView? = null

    private var swipeStartX = 0f
    private var swipeStartY = 0f
    private var swipeConfirmed = false
    private val swipeSlop = 30f * density

    init {
        // 磨砂模糊背景 — 使用封面取色（与歌曲/歌词界面统一）
        val blurBg = LiquidGlassView(context).apply {
            cornerRadius = 18f * density
            surfaceColor = Color.argb(180, 20, 16, 12)  // 默认深色，运行时由setCoverColors覆盖
            blurRadius = 50f * density
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        addView(blurBg)
        // 保存引用以便运行时更新颜色
        bgBlurView = blurBg

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
            setPadding(
                (16 * density).toInt(),
                (12 * density).toInt(),
                (16 * density).toInt(),
                (14 * density).toInt()
            )
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }

        // === 顶部区域：全选 + 歌曲计数 + 关闭 ===
        val topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val selectAll = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (4 * density).toInt(), (12 * density).toInt(), (4 * density).toInt())
            setOnClickListener {
                isAllSelected = !isAllSelected
                updateSelectAllButton(this)
                if (isAllSelected) onSelectAll?.invoke() else onDeselectAll?.invoke()
            }

            val icon = ImageView(context).apply {
                id = View.generateViewId()
                setImageResource(R.drawable.ic_select_checked)
                val size = (20 * density).toInt()
                layoutParams = android.widget.LinearLayout.LayoutParams(size, size)
                setColorFilter(Color.WHITE)
            }
            addView(icon)

            val label = TextView(context).apply {
                id = View.generateViewId()
                text = "全选"
                setTextColor(Color.WHITE)
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = (4 * density).toInt() }
            }
            addView(label)
        }
        btnSelectAll = selectAll
        topRow.addView(selectAll)

        val tvCount = TextView(context).apply {
            text = "0/0"
            setTextColor(Color.argb(128, 255, 255, 255))
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        tvSongCount = tvCount
        topRow.addView(tvCount)

        val btnClose = ImageView(context).apply {
            setImageResource(R.drawable.ic_close)
            setColorFilter(Color.argb(180, 255, 255, 255))
            val size = (24 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size)
            setPadding((2 * density).toInt(), (2 * density).toInt(), (2 * density).toInt(), (2 * density).toInt())
            setOnClickListener { onClose?.invoke() }
        }
        topRow.addView(btnClose)

        container.addView(topRow)

        // === 底部区域：操作按钮横排 ===
        val actionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(0, (8 * density).toInt(), 0, 0)
        }

        val btnPlayNext = createActionView("下一首播放", R.drawable.ic_skip_next) {
            onPlayNext?.invoke()
        }
        actionRow.addView(btnPlayNext)

        val btnAddPlaylist = createActionView("添加到歌单", R.drawable.ic_add_outline) {
            onAddToPlaylist?.invoke()
        }
        val addLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        addLp.marginStart = (6 * density).toInt()
        btnAddPlaylist.layoutParams = addLp
        actionRow.addView(btnAddPlaylist)

        val btnDelete = createActionView("删除", R.drawable.ic_delete) {
            onDelete?.invoke()
        }
        val delLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        delLp.marginStart = (6 * density).toInt()
        btnDelete.layoutParams = delLp
        actionRow.addView(btnDelete)

        container.addView(actionRow)

        addView(container)

        // R角矩形裁剪
        val cornerRadius = 18f * density
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, cornerRadius)
            }
        }
        clipToOutline = true
        elevation = 16f * density

        // 初始隐藏
        alpha = 0f
        translationY = 40f * density
    }

    private fun updateCountText() {
        tvSongCount?.text = "$selectedCount/$songCount"
    }

    /** 更新全选/反选按钮的图标和文字 */
    private fun updateSelectAllButton(container: android.widget.LinearLayout) {
        val icon = container.getChildAt(0) as? ImageView ?: return
        val label = container.getChildAt(1) as? TextView ?: return
        if (isAllSelected) {
            // 已全选状态 → 显示"反选"按钮（点击后执行反选操作）
            icon.setImageResource(R.drawable.ic_select_unchecked)
            icon.setColorFilter(Color.argb(180, 255, 255, 255))
            label.text = "反选"
        } else {
            // 未全选状态 → 显示"全选"按钮（点击后执行全选操作）
            icon.setImageResource(R.drawable.ic_select_checked)
            icon.setColorFilter(Color.WHITE)
            label.text = "全选"
        }
    }

    private fun createActionView(label: String, iconRes: Int, onClick: () -> Unit): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val size = (32 * density).toInt()

            val icon = ImageView(context).apply {
                setImageResource(iconRes)
                setColorFilter(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(size, size)
                setPadding((6 * density).toInt(), (6 * density).toInt(), (6 * density).toInt(), (6 * density).toInt())
                setBackgroundResource(R.drawable.bg_add_btn)
            }
            addView(icon)

            val text = TextView(context).apply {
                text = label
                setTextColor(Color.argb(200, 255, 255, 255))
                textSize = 10f
                gravity = Gravity.CENTER
            }
            addView(text)

            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
    }

    /** 处理侧滑边缘手势 */
    fun handleTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swipeStartX = ev.rawX
                swipeStartY = ev.rawY
                swipeConfirmed = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!swipeConfirmed) {
                    val dx = ev.rawX - swipeStartX
                    if (kotlin.math.abs(dx) > swipeSlop) {
                        swipeConfirmed = true
                    }
                }
                if (swipeConfirmed) {
                    onSwipeDismiss?.invoke()
                    return true
                }
            }
        }
        return swipeConfirmed
    }

    fun show() {
        animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(200)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.5f))
            .start()
    }

    fun dismiss() {
        animate()
            .alpha(0f)
            .translationY(40f * density)
            .setDuration(150)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .start()
    }
}
