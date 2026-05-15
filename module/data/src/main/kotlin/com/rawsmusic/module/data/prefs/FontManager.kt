package com.rawsmusic.module.data.prefs

import android.content.Context
import android.graphics.Typeface
import android.widget.TextView

object FontManager {

    private var customTypeface: Typeface? = null

    val typeface: Typeface?
        get() = customTypeface

    fun init(context: Context) {
        val path = AppPreferences.UI.customFontPath
        if (path.isNotBlank()) {
            try {
                val tf = Typeface.createFromFile(path)
                customTypeface = applyStyle(tf)
            } catch (_: Exception) {
                try {
                    val uri = android.net.Uri.parse(path)
                    val input = context.contentResolver.openInputStream(uri)
                    if (input != null) {
                        val file = java.io.File(context.cacheDir, "custom_font.ttf")
                        file.outputStream().use { out -> input.copyTo(out) }
                        input.close()
                        val tf = Typeface.createFromFile(file)
                        customTypeface = applyStyle(tf)
                    }
                } catch (_: Exception) {
                    customTypeface = null
                }
            }
        } else {
            customTypeface = null
        }
    }

    private fun applyStyle(base: Typeface): Typeface {
        val italic = AppPreferences.UI.fontItalic
        val style = if (kotlin.math.abs(italic) > 0.1f) Typeface.ITALIC else Typeface.NORMAL
        return Typeface.create(base, style)
    }

    fun applyToTextView(textView: TextView) {
        customTypeface?.let { tf ->
            textView.typeface = tf
            val weight = AppPreferences.UI.fontWeight
            val italic = AppPreferences.UI.fontItalic
            textView.textScaleX = weight
            if (kotlin.math.abs(italic) > 0.01f) {
                textView.paint.textSkewX = italic
            }
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
}
