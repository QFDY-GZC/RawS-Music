package com.rawsmusic.module.data.prefs

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.widget.TextView

object FontManager {

    private const val BUILTIN_FONT_PATH = "fonts/MiSansLatinVF.ttf"
    private const val DEFAULT_WEIGHT = 400
    private const val DEFAULT_SIZE_SCALE = 100
    private const val DEFAULT_ITALIC = false

    private var baseTypeface: Typeface? = null
    private var customTypeface: Typeface? = null

    private val scaledViews = mutableSetOf<Int>()

    val typeface: Typeface?
        get() = customTypeface ?: baseTypeface

    fun init(context: Context) {
        try {
            baseTypeface = Typeface.createFromAsset(context.assets, BUILTIN_FONT_PATH)
        } catch (_: Exception) {
            baseTypeface = null
        }
        rebuildTypeface(context)
    }

    fun rebuildTypeface(context: Context) {
        val base = baseTypeface ?: return
        val weight = AppPreferences.UI.fontWeight
        val italic = AppPreferences.UI.fontItalic

        customTypeface = buildVariableTypeface(context, base, weight, italic)
        scaledViews.clear()
    }

    private fun buildVariableTypeface(context: Context, base: Typeface, weight: Int, italic: Boolean): Typeface {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val axis = "wght $weight"
                val builder = Typeface.Builder(context.assets, BUILTIN_FONT_PATH)
                builder.setFontVariationSettings(axis)
                val tf = builder.build()
                return if (italic) {
                    Typeface.create(tf, Typeface.ITALIC)
                } else {
                    tf
                }
            } catch (_: Exception) {}
        }
        val style = if (italic) Typeface.ITALIC else Typeface.NORMAL
        return Typeface.create(base, style)
    }

    fun applyToTextView(textView: TextView) {
        val tf = customTypeface ?: baseTypeface
        if (tf != null) {
            textView.typeface = tf
        }
        applyTextSizeScale(textView)
        applyItalic(textView)
    }

    private fun applyTextSizeScale(textView: TextView) {
        val viewId = textView.hashCode()
        if (viewId in scaledViews) return
        val scale = AppPreferences.UI.fontSizeScale / 100f
        if (scale == 1f) return
        val currentSize = textView.textSize
        if (currentSize > 0f) {
            val scaledSize = currentSize * scale
            textView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, scaledSize)
            scaledViews.add(viewId)
        }
    }

    private fun applyItalic(textView: TextView) {
        val italic = AppPreferences.UI.fontItalic
        if (italic) {
            textView.paint.textSkewX = -0.2f
        }
    }

    fun applyRecursive(view: android.view.View) {
        if (view is TextView) {
            applyToTextView(view)
        }
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                applyRecursive(view.getChildAt(i))
            }
        }
    }

    fun clearScaledCache() {
        scaledViews.clear()
    }
}
