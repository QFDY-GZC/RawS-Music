package com.rawsmusic.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.widget.LinearLayout

class CapsuleRootLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private var expandedBg: Drawable? = null
    private var collapsedBg: Drawable? = null
    var foldProgress: Float = 0f

    fun setBackgrounds(expanded: Drawable, collapsed: Drawable) {
        expandedBg = expanded
        collapsedBg = collapsed
    }

    override fun dispatchDraw(canvas: Canvas) {
        val alpha = (foldProgress * 255).toInt()

        expandedBg?.let {
            it.setBounds(0, 0, width, height)
            it.alpha = 255 - alpha
            it.draw(canvas)
        }

        collapsedBg?.let {
            it.setBounds(0, 0, width, height)
            it.alpha = alpha
            it.draw(canvas)
        }

        super.dispatchDraw(canvas)
    }
}
