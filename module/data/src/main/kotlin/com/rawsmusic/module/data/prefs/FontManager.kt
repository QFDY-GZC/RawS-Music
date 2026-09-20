package com.rawsmusic.module.data.prefs

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.os.Build

object FontManager {
    private data class TypefaceKey(
        val path: String,
        val globalWeight: Int,
        val globalItalic: Boolean,
        val requestedWeight: Int,
        val requestedItalic: Boolean,
    )

    private val typefaceCache = LinkedHashMap<TypefaceKey, Typeface>()

    /**
     * Global UI typeface. An empty preference means the platform default font.
     *
     * The selected file can be either a system font or a font imported through the shared
     * application font catalog. Keep the cache path-aware so changing the selection is cheap and
     * does not force every TextStyle read to reopen the font file.
     */
    val typeface: Typeface?
        get() {
            val path = AppPreferences.UI.customFontPath
            val globalWeight = AppPreferences.UI.fontWeight.coerceIn(100, 900)
            val globalItalic = AppPreferences.UI.fontItalic
            return if (path.isBlank() && globalWeight == 400 && !globalItalic) {
                null
            } else {
                resolveTypeface()
            }
        }

    /**
     * Resolve one native text request after applying the global UI weight as an offset.
     *
     * requestedWeight is the control's original semantic weight (400 body, 500 medium,
     * 700 bold...). The global slider moves every request by the same delta, preserving the
     * relative hierarchy instead of collapsing all native Paint/RenderNode text back to 400/700.
     */
    fun resolveTypeface(
        requestedWeight: Int = 400,
        requestedItalic: Boolean = false,
    ): Typeface {
        val path = AppPreferences.UI.customFontPath
        val globalWeight = AppPreferences.UI.fontWeight.coerceIn(100, 900)
        val globalItalic = AppPreferences.UI.fontItalic
        val key = TypefaceKey(
            path = path,
            globalWeight = globalWeight,
            globalItalic = globalItalic,
            requestedWeight = requestedWeight.coerceIn(100, 900),
            requestedItalic = requestedItalic,
        )
        synchronized(this) {
            typefaceCache[key]?.let { return it }
        }

        val finalWeight = (key.requestedWeight + globalWeight - 400).coerceIn(100, 900)
        val finalItalic = globalItalic || requestedItalic
        val resolved = runCatching {
            val base = if (path.isBlank()) Typeface.DEFAULT else Typeface.createFromFile(path)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Typeface.create(base, finalWeight, finalItalic)
            } else {
                val legacyStyle = when {
                    finalWeight >= 600 && finalItalic -> Typeface.BOLD_ITALIC
                    finalWeight >= 600 -> Typeface.BOLD
                    finalItalic -> Typeface.ITALIC
                    else -> Typeface.NORMAL
                }
                Typeface.create(base, legacyStyle)
            }
        }.getOrElse {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Typeface.create(Typeface.DEFAULT, finalWeight, finalItalic)
            } else {
                Typeface.create(
                    Typeface.DEFAULT,
                    when {
                        finalWeight >= 600 && finalItalic -> Typeface.BOLD_ITALIC
                        finalWeight >= 600 -> Typeface.BOLD
                        finalItalic -> Typeface.ITALIC
                        else -> Typeface.NORMAL
                    },
                )
            }
        }

        synchronized(this) {
            if (typefaceCache.size >= 24) typefaceCache.clear()
            typefaceCache[key] = resolved
        }
        return resolved
    }

    fun init(@Suppress("UNUSED_PARAMETER") context: Context) {
        typeface
    }

    fun rebuildTypeface(@Suppress("UNUSED_PARAMETER") context: Context) {
        synchronized(this) {
            typefaceCache.clear()
        }
        typeface
    }

    fun clearScaledCache() = Unit

    fun getSystemFonts(): List<LyricFontManager.FontInfo> = LyricFontManager.getSystemFonts()

    fun getImportedFonts(context: Context): List<LyricFontManager.FontInfo> =
        LyricFontManager.getImportedFonts(context)

    fun importFont(context: Context, uri: Uri): LyricFontManager.FontInfo? =
        LyricFontManager.importFont(context, uri)

    fun selectFont(context: Context, font: LyricFontManager.FontInfo?) {
        AppPreferences.UI.customFontPath = font?.path.orEmpty()
        rebuildTypeface(context)
    }
}
