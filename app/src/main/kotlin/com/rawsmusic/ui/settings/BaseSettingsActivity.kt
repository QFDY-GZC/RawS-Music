package com.rawsmusic.ui.settings

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent as setComposeContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.rawsmusic.R
import com.rawsmusic.core.ui.theme.RawSMusicTheme
import com.rawsmusic.module.player.PlayerController
import com.rawsmusic.ui.songs.PlayerHolder
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 所有设置子页面的基类。
 * 不使用 enableEdgeToEdge()，
 * 系统栏由主题（SettingsActivityTheme）处理。
 */
abstract class BaseSettingsActivity : ComponentActivity() {

    val playerController: PlayerController? get() = PlayerHolder.controller

    private var lastNavigateTime = 0L
    private var lastNavigateClass: Class<*>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
    }

    /** 设置 Compose 内容。 */
    fun setContent(content: @Composable () -> Unit) {
        setComposeContent {
            RawSMusicTheme {
                val settingsBackground = MiuixTheme.colorScheme.background
                val isDark = settingsBackground.luminance() < 0.5f

                SideEffect {
                    window.statusBarColor = android.graphics.Color.TRANSPARENT
                    window.navigationBarColor = settingsBackground.toArgb()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        window.isStatusBarContrastEnforced = false
                        window.isNavigationBarContrastEnforced = false
                    }
                    WindowInsetsControllerCompat(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !isDark
                        isAppearanceLightNavigationBars = !isDark
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(settingsBackground)
                ) {
                    content()
                }
            }
        }
    }

    /** 导航到另一个设置子页面 */
    fun navigateToSettings(activityClass: Class<*>) {
        if (this::class.java == activityClass) return

        val now = SystemClock.elapsedRealtime()
        if (lastNavigateClass == activityClass && now - lastNavigateTime < 600) {
            return
        }
        lastNavigateClass = activityClass
        lastNavigateTime = now

        startActivity(
            Intent(this, activityClass).apply {
                setPackage(packageName)
            }
        )

        if (Build.VERSION.SDK_INT < 34) {
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.settings_enter_from_right, R.anim.settings_exit_to_left)
        }
    }

    @Suppress("DEPRECATION")
    override fun finish() {
        super.finish()
        if (Build.VERSION.SDK_INT < 34) {
            overridePendingTransition(R.anim.settings_enter_from_left, R.anim.settings_exit_to_right)
        }
    }
}
