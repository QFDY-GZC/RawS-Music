package com.rawsmusic.helper

import android.app.Activity
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.rawsmusic.R
import com.rawsmusic.core.ui.theme.ColorThemeMode
import com.rawsmusic.core.ui.theme.getCurrentColorThemeMode

class SystemBarsHelper(
    private val activity: Activity
) {
    fun setupEdgeToEdge(isDarkMode: Boolean) {
        val window = activity.window
        WindowCompat.setDecorFitsSystemWindows(window, false)

        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = ContextCompat.getColor(activity, R.color.scrim_color)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !isDarkMode
            isAppearanceLightNavigationBars = !isDarkMode
        }
    }

    fun updateForScene(isMainScene: Boolean, isDarkMode: Boolean) {
        val window = activity.window
        val colorTheme = getCurrentColorThemeMode()

        // Monet 模式下不覆盖状态栏颜色，让 RawMonetSystemBars 管理
        if (colorTheme != ColorThemeMode.MIUIX) {
            WindowInsetsControllerCompat(window, window.decorView)
                .isAppearanceLightStatusBars = !isDarkMode
            return
        }

        // MIUIx 模式：主场景透明，播放器场景跟随主题
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        WindowInsetsControllerCompat(window, window.decorView)
            .isAppearanceLightStatusBars = isMainScene && !isDarkMode
    }
}
