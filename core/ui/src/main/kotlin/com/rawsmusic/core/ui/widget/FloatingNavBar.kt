package com.rawsmusic.core.ui.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.rawsmusic.core.ui.animation.ButtonAnimHelper

class FloatingNavBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    data class TabItem(
        val id: Int,
        val label: String,
        val iconRes: Int
    )

    var onTabSelected: ((Int) -> Unit)? = null

    private var selectedTabId: Int = 0
    private val tabViews = mutableMapOf<Int, TabView>()
    private val density = resources.displayMetrics.density

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xE6FFFFFF.toInt()
        style = Paint.Style.FILL
    }

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x18000000
        style = Paint.Style.FILL
    }

    private val indicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFC4956A.toInt()
        style = Paint.Style.FILL
    }

    private val bgRect = RectF()
    private val cornerRadius = 28f * density

    private var isDarkMode = false

    fun setDarkMode(dark: Boolean) {
        isDarkMode = dark
        bgPaint.color = if (dark) 0xE8393331.toInt() else 0xE6FFFFFF.toInt()
        shadowPaint.color = if (dark) 0x30000000 else 0x18000000
        tabViews.values.forEach { it.updateTheme(dark) }
        invalidate()
    }

    fun setTabs(tabs: List<TabItem>, selectedId: Int) {
        removeAllViews()
        tabViews.clear()
        selectedTabId = selectedId

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            setPadding((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
        }

        tabs.forEach { tab ->
            val tabView = TabView(context, tab, tab.id == selectedId, density, isDarkMode).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                onTabClick = { id ->
                    if (id != selectedTabId) {
                        selectedTabId = id
                        tabViews.values.forEach { tv -> tv.setTabSelected(tv.tabId == id) }
                        onTabSelected?.invoke(id)
                    }
                }
            }
            tabViews[tab.id] = tabView
            container.addView(tabView)
        }

        addView(container)
    }

    fun selectTab(id: Int) {
        if (id == selectedTabId) return
        selectedTabId = id
        tabViews.values.forEach { tv -> tv.setTabSelected(tv.tabId == id) }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        shadowPaint.setShadowLayer(18f * density, 0f, 6f * density, shadowPaint.color)
        bgRect.set(0f, 0f, w, h)
        canvas.drawRoundRect(bgRect, cornerRadius, cornerRadius, shadowPaint)
        canvas.drawRoundRect(bgRect, cornerRadius, cornerRadius, bgPaint)

        drawIndicator(canvas)
    }

    private fun drawIndicator(canvas: Canvas) {
        val selectedView = tabViews[selectedTabId] ?: return
        val tabW = selectedView.width.toFloat()
        val tabH = selectedView.height.toFloat()
        val tabLeft = selectedView.left.toFloat()
        val tabTop = selectedView.top.toFloat()

        val indicatorW = 24f * density
        val indicatorH = 3f * density
        val indicatorRadius = 2f * density
        val cx = tabLeft + tabW / 2f
        val cy = tabTop + tabH - 6f * density

        val rect = RectF(cx - indicatorW / 2f, cy - indicatorH / 2f, cx + indicatorW / 2f, cy + indicatorH / 2f)
        canvas.drawRoundRect(rect, indicatorRadius, indicatorRadius, indicatorPaint)
    }

    private class TabView(
        context: Context,
        val tabItem: TabItem,
        private var isSelected: Boolean,
        private val density: Float,
        private var isDark: Boolean
    ) : FrameLayout(context) {

        val tabId: Int = tabItem.id
        var onTabClick: ((Int) -> Unit)? = null

        private val iconView: ImageView
        private val labelView: TextView

        init {
            val container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            }

            iconView = ImageView(context).apply {
                setImageResource(tabItem.iconRes)
                val size = if (isSelected) (30 * density).toInt() else (26 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(size, size)
                setColorFilter(if (isDark) 0xFFD4B896.toInt() else 0xFFC4956A.toInt())
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding((2 * density).toInt(), (2 * density).toInt(), (2 * density).toInt(), (2 * density).toInt())
            }

            labelView = TextView(context).apply {
                text = tabItem.label
                textSize = if (isSelected) 12.5f else 11f
                setTextColor(if (isSelected) {
                    if (isDark) 0xFFD4B896.toInt() else 0xFFC4956A.toInt()
                } else {
                    if (isDark) 0xFF58524F.toInt() else 0xFFBAB3AF.toInt()
                })
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = (3 * density).toInt()
                }
            }

            container.addView(iconView)
            container.addView(labelView)
            addView(container)

            setOnClickListener {
                ButtonAnimHelper.pressReleaseAnim(this, 0.96f)
                onTabClick?.invoke(tabId)
            }

            updateSelectedState()
        }

        fun setTabSelected(selected: Boolean) {
            if (isSelected == selected) return
            isSelected = selected
            updateSelectedState()
        }

        fun updateTheme(dark: Boolean) {
            isDark = dark
            updateSelectedState()
        }

        private fun updateSelectedState() {
            val size = if (isSelected) (30 * density).toInt() else (26 * density).toInt()
            iconView.layoutParams = LinearLayout.LayoutParams(size, size)
            iconView.setColorFilter(if (isSelected) {
                if (isDark) 0xFFD4B896.toInt() else 0xFFC4956A.toInt()
            } else {
                if (isDark) 0xFF58524F.toInt() else 0xFFBAB3AF.toInt()
            })
            labelView.textSize = if (isSelected) 12.5f else 11f
            labelView.setTextColor(if (isSelected) {
                if (isDark) 0xFFD4B896.toInt() else 0xFFC4956A.toInt()
            } else {
                if (isDark) 0xFF58524F.toInt() else 0xFFBAB3AF.toInt()
            })
            labelView.setTypeface(null, if (isSelected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
    }
}
