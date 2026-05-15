package com.rawsmusic.core.ui.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.rawsmusic.core.ui.animation.ButtonAnimHelper
import kotlin.math.abs

class SideMenuView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    data class MenuItem(
        val id: Int,
        val title: String,
        val iconRes: Int
    )

    var onMenuToggle: ((Boolean) -> Unit)? = null
    var onMenuItemClick: ((Int) -> Unit)? = null

    var isOpen = false
        private set
    private var slideOffset = 0f
    private val density = resources.displayMetrics.density
    private val menuWidth = 280 * density

    private val menuContainer: LinearLayout
    private val menuItems = mutableListOf<MenuItemView>()
    private var selectedMenuId: Int = 0

    /** 设置封面取色 — 更新背景渐变和文字颜色 */
    fun setCoverColors(primary: Int, dark: Int) {
        val bg = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
            intArrayOf(primary, dark)
        )
        background = bg
        headerTextReference?.setTextColor(0xFFFFFFFF.toInt())
        menuItems.forEach { it.updateDarkMode() }
    }

    private var headerTextReference: TextView? = null

    /** 左滑关闭菜单的手势追踪 */
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var isSwipeToClose = false

    init {
        menuContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.TRANSPARENT)  // 容器透明，由 fullScreenBgView 统一渲染背景
            setPadding(
                (12 * density).toInt(),
                (60 * density).toInt(),
                (12 * density).toInt(),
                (24 * density).toInt()
            )
        }

        val scrollView = ScrollView(context).apply {
            addView(menuContainer)
            isVerticalScrollBarEnabled = false
        }

        addView(scrollView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        setWillNotDraw(false)

        val headerText = TextView(context).apply {
            text = "RawSMusic"
            setTextColor(0xFFF0EBE8.toInt())
            textSize = 22f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            val lp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = (24 * density).toInt()
            menuContainer.addView(this, 0, lp)
        }
        headerTextReference = headerText
    }

    fun setMenuItems(groups: List<Pair<String, List<MenuItem>>>, selectedId: Int) {
        selectedMenuId = selectedId
        while (menuContainer.childCount > 1) {
            menuContainer.removeViewAt(menuContainer.childCount - 1)
        }
        menuItems.clear()

        groups.forEachIndexed { index, (groupTitle, items) ->
            if (index > 0) addDivider()

            val groupLabel = TextView(context).apply {
                text = groupTitle
                setTextColor(0xFF928A86.toInt())
                textSize = 12f
                val lp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
                lp.bottomMargin = (8 * density).toInt()
                lp.topMargin = (8 * density).toInt()
                menuContainer.addView(this, lp)
            }

            items.forEach { item ->
                val menuItemView = MenuItemView(context, item, density).apply {
                    updateSelected(selectedId)
                    setOnClickListener {
                        ButtonAnimHelper.pressReleaseAnim(it)
                        onMenuItemClick?.invoke(item.id)
                        setSelectedMenuId(item.id)
                        // 不再自动关闭菜单 — 由MainActivity根据菜单项类型决定是否关闭
                    }
                }
                menuItems.add(menuItemView)
                menuContainer.addView(menuItemView)
            }
        }
    }

    fun setSelectedMenuId(id: Int) {
        selectedMenuId = id
        menuItems.forEach { it.updateSelected(id) }
    }

    fun open() {
        if (isOpen) return
        isOpen = true
        onMenuToggle?.invoke(true)
        slideOffset = 1f
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        onMenuToggle?.invoke(false)
        slideOffset = 0f
    }

    fun toggle() {
        if (isOpen) close() else open()
    }

    fun setSlideOffset(offset: Float) {
        slideOffset = offset
        isOpen = offset > 0.01f
        invalidate()
    }

    private fun addDivider() {
        // 分割线已移除
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // 背景透明 — 由 fullScreenBgView 统一渲染动态封面背景
    }

    /**
     * 拦截左滑手势以关闭菜单，其余不拦截让子View处理
     */
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (!isOpen) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchStartX = ev.rawX
                touchStartY = ev.rawY
                isSwipeToClose = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.rawX - touchStartX
                val dy = ev.rawY - touchStartY
                // 左滑检测：水平位移>20dp，水平>1.2倍垂直（降低阈值提高灵敏度）
                if (dx < -20f * density && abs(dx) > abs(dy) * 1.2f) {
                    isSwipeToClose = true
                    return true
                }
            }
        }
        return false
    }

    /**
     * 菜单打开时消费触摸事件（防止穿透到底层）
     * 左滑手势触发关闭菜单
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isOpen) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchStartX = event.rawX
                touchStartY = event.rawY
                isSwipeToClose = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - touchStartX
                val dy = event.rawY - touchStartY
                // 左滑检测：水平位移>20dp，水平>1.2倍垂直
                if (dx < -20f * density && abs(dx) > abs(dy) * 1.2f) {
                    isSwipeToClose = true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val dx = event.rawX - touchStartX
                // 左滑超过60dp → 关闭菜单（降低阈值提高灵敏度）
                if (isSwipeToClose && dx < -60f * density) {
                    onMenuToggle?.invoke(false)
                }
                isSwipeToClose = false
            }
        }
        return true
    }

    private class MenuItemView(
        context: Context,
        private val menuItem: MenuItem,
        private val density: Float
    ) : FrameLayout(context) {

        private val titleView: TextView
        private val iconView: ImageView
        private var isSelected = false
        private val selectedBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x30C4956A.toInt()  // 更高透明度，适配深色背景
            style = Paint.Style.FILL
        }
        private val bgRect = RectF()

        init {
            setPadding(
                (16 * density).toInt(),
                (12 * density).toInt(),
                (16 * density).toInt(),
                (12 * density).toInt()
            )

            isClickable = true
            isFocusable = true

            val innerLayout = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            iconView = ImageView(context).apply {
                setImageResource(menuItem.iconRes)
                val size = (20 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(size, size)
                setColorFilter(0xFF928A86.toInt())
            }

            titleView = TextView(context).apply {
                text = menuItem.title
                setTextColor(0xFFF0EBE8.toInt())
                textSize = 15f
                val lp = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
                lp.marginStart = (16 * density).toInt()
                layoutParams = lp
            }

            innerLayout.addView(iconView)
            innerLayout.addView(titleView)
            addView(innerLayout, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }

        fun updateSelected(selectedId: Int) {
            val wasSelected = isSelected
            isSelected = menuItem.id == selectedId
            if (wasSelected != isSelected) {
                titleView.setTypeface(
                    titleView.typeface,
                    if (isSelected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL
                )
                titleView.setTextColor(
                    if (isSelected) 0xFFFFFFFF.toInt() else 0xCCFFFFFF.toInt()
                )
                iconView.setColorFilter(
                    if (isSelected) 0xFFFFFFFF.toInt() else 0xCCFFFFFF.toInt()
                )
                invalidate()
            }
        }

        /** 深色模式适配 */
        fun updateDarkMode() {
            titleView.setTextColor(
                if (isSelected) 0xFFFFFFFF.toInt() else 0xCCFFFFFF.toInt()
            )
            iconView.setColorFilter(
                if (isSelected) 0xFFFFFFFF.toInt() else 0xCCFFFFFF.toInt()
            )
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (isSelected) {
                bgRect.set(0f, 0f, width.toFloat(), height.toFloat())
                val cornerRadius = 12 * density
                canvas.drawRoundRect(bgRect, cornerRadius, cornerRadius, selectedBg)
            }
        }
    }
}
