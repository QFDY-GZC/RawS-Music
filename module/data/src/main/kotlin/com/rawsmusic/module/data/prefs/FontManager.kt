package com.rawsmusic.module.data.prefs

import android.content.Context
import android.graphics.Typeface
import android.os.Build

object FontManager {

    private const val BUILTIN_FONT_PATH = "fonts/MiSansLatinVF.ttf"
    private const val DEFAULT_WEIGHT = 400
    private const val DEFAULT_SIZE_SCALE = 100
    private const val DEFAULT_ITALIC = false

    private var baseTypeface: Typeface? = null
    private var customTypeface: Typeface? = null

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

    fun clearScaledCache() = Unit
}
