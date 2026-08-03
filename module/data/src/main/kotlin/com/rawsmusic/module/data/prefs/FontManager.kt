package com.rawsmusic.module.data.prefs

import android.content.Context
import android.graphics.Typeface

object FontManager {

    // No application typeface is bundled. Compose and Miuix therefore use the
    // device font while RawSMusicTheme still applies size, weight and italic settings.
    val typeface: Typeface? = null

    fun init(@Suppress("UNUSED_PARAMETER") context: Context) = Unit

    fun rebuildTypeface(@Suppress("UNUSED_PARAMETER") context: Context) = Unit

    fun clearScaledCache() = Unit
}
