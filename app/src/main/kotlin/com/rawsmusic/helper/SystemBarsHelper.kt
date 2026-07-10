package com.rawsmusic.helper

import android.app.Activity
import android.os.Build
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.rawsmusic.core.ui.theme.ColorThemeMode
import com.rawsmusic.core.ui.theme.getCurrentColorThemeMode

class SystemBarsHelper(
    private val activity: Activity
) {
    fun setupEdgeToEdge(isDarkMode: Boolean) {
        val window = activity.window
        WindowCompat.setDecorFitsSystemWindows(window, false)

        window.statusBarColor = android.graphics.Color.TRANSPARENT
        // Project-style edge-to-edge: the system gesture/navigation handle stays visible,
        // but the app surface is allowed to draw behind it. Do not paint a scrim here.
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
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

        // MIUIX 模式：状态栏/导航栏都保持透明，具体页面自己绘制背景。
        // This mirrors the design: content goes edge-to-edge, controls opt into safe insets.
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = isMainScene && !isDarkMode
            isAppearanceLightNavigationBars = !isDarkMode
        }
    }
}
