package com.rawsmusic.core.common.ext

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager

val Context.isDarkMode: Boolean
    get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

val Context.screenWidth: Int
    get() = displayMetrics.widthPixels

val Context.screenHeight: Int
    get() = displayMetrics.heightPixels

val Context.displayMetrics: DisplayMetrics
    get() {
        val dm = DisplayMetrics()
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getMetrics(dm)
        return dm
    }

val Context.isAtLeastR: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

val Context.isAtLeastT: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
