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
import com.rawsmusic.module.data.prefs.PersonalizationPreferences
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
    private var nonPredictiveBackCallback: android.window.OnBackInvokedCallback? = null
    private var nonPredictiveBackRegistered = false
    private var activityWindowHasFocus = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
    }

    override fun onResume() {
        super.onResume()
        refreshPredictiveBackPreference()
    }

    @android.annotation.SuppressLint("NewApi")
    fun refreshPredictiveBackPreference() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        // MIUIX dropdowns and dialogs use a child window. While that child owns focus, the
        // Activity-level fallback must not intercept back and finish the whole settings page.
        val shouldIntercept = !PersonalizationPreferences.predictiveBackAnimationEnabled &&
            activityWindowHasFocus
        try {
            if (shouldIntercept && !nonPredictiveBackRegistered) {
                val callback = nonPredictiveBackCallback ?: android.window.OnBackInvokedCallback {
                    if (activityWindowHasFocus && window.decorView.hasWindowFocus()) {
                        finish()
                    }
                }.also { nonPredictiveBackCallback = it }
                onBackInvokedDispatcher.registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    callback
                )
                nonPredictiveBackRegistered = true
            } else if (!shouldIntercept && nonPredictiveBackRegistered) {
                nonPredictiveBackCallback?.let(onBackInvokedDispatcher::unregisterOnBackInvokedCallback)
                nonPredictiveBackRegistered = false
            }
        } catch (_: Exception) {
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (activityWindowHasFocus == hasFocus) return
        activityWindowHasFocus = hasFocus
        refreshPredictiveBackPreference()
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && nonPredictiveBackRegistered) {
            try {
                nonPredictiveBackCallback?.let(onBackInvokedDispatcher::unregisterOnBackInvokedCallback)
            } catch (_: Exception) {
            }
            nonPredictiveBackRegistered = false
        }
        super.onDestroy()
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
