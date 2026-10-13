package com.rawsmusic.ui.settings

import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent as setComposeContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.rawsmusic.R
import com.rawsmusic.core.common.ui.AppNoticeBus
import com.rawsmusic.core.common.ui.AppNoticeIcon
import com.rawsmusic.core.ui.scene.AppNoticeHost
import com.rawsmusic.core.ui.theme.RawSMusicTheme
import com.rawsmusic.core.ui.theme.ThemeManager
import com.rawsmusic.module.player.PlayerController
import com.rawsmusic.module.player.UsbStatusNoticeBus
import com.rawsmusic.module.data.prefs.PersonalizationPreferences
import com.rawsmusic.ui.songs.PlayerHolder
import com.rawsmusic.core.ui.widget.background.CustomMediaBackground
import com.rawsmusic.core.ui.widget.background.CustomMediaBackgroundState
import com.rawsmusic.core.ui.widget.bitmaps.resolvePlaybackArtworkKey
import com.rawsmusic.core.ui.widget.flow.RawFlowBackground
import com.rawsmusic.core.ui.widget.flow.rememberCurrentRawFlowMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

/**
 * 所有设置子页面的基类。
 * 系统栏在 Activity 首帧前建立 edge-to-edge，Compose 主题随后接管图标明暗与背景。
 */
abstract class BaseSettingsActivity : ComponentActivity() {

    val playerController: PlayerController? get() = PlayerHolder.controller

    private var pendingSystemFileAccessAction: (() -> Unit)? = null
    private val systemFileAccessLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val action = pendingSystemFileAccessAction
        pendingSystemFileAccessAction = null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()) {
            action?.invoke()
        } else {
            AppNoticeBus.error(getString(R.string.scan_settings_legacy_access_denied_toast))
        }
    }

    private var lastNavigateTime = 0L
    private var lastNavigateClass: Class<*>? = null
    private var nonPredictiveBackCallback: android.window.OnBackInvokedCallback? = null
    private var nonPredictiveBackRegistered = false
    private var activityWindowHasFocus = true
    private val usbStatusNoticeListener: (UsbStatusNoticeBus.Notice) -> Unit = { notice ->
        if (!isFinishing && !isDestroyed) {
            AppNoticeBus.post(
                message = notice.message,
                icon = notice.icon,
            )
            UsbStatusNoticeBus.acknowledge(notice.id)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER
        super.onCreate(savedInstanceState)
        // LocaleManager recreates settings Activities on Android 13+. Establish edge-to-edge
        // synchronously, before Compose's first frame, so the launch-theme status-bar fill cannot
        // survive a rapid locale round trip as an opaque white strip.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        val isDark = ThemeManager.isDarkMode(this)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !isDark
            isAppearanceLightNavigationBars = !isDark
        }
    }

    /** Android 11+ hard gate before opening a system file/document picker. */
    fun withSystemFileAccess(action: () -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()) {
            action()
            return
        }

        pendingSystemFileAccessAction = action
        val launched = runCatching {
            systemFileAccessLauncher.launch(
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
            true
        }.recoverCatching {
            systemFileAccessLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            true
        }.getOrDefault(false)

        if (!launched) {
            pendingSystemFileAccessAction = null
            AppNoticeBus.error(getString(R.string.scan_settings_legacy_access_denied_toast))
        }
    }

    override fun onResume() {
        super.onResume()
        UsbStatusNoticeBus.attach(usbStatusNoticeListener)
        refreshPredictiveBackPreference()
    }

    override fun onPause() {
        UsbStatusNoticeBus.detach(usbStatusNoticeListener)
        super.onPause()
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
                com.rawsmusic.core.ui.widget.bitmaps.SceneHdrWindow(com.rawsmusic.module.data.prefs.HdrScene.SETTINGS)
                CustomMediaBackgroundState.ensureInitialized(this@BaseSettingsActivity)
                @Suppress("UNUSED_VARIABLE")
                val customBackgroundRevision = CustomMediaBackgroundState.revision
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

                val settingsBackdrop = rememberLayerBackdrop()
                val rawFlowMode = rememberCurrentRawFlowMode()
                val currentSong = playerController?.currentSong?.collectAsState()?.value
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(settingsBackground)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .layerBackdrop(settingsBackdrop)
                            // Include the window fill in the recording as well as flow/media.
                            // Otherwise transparent child pages sample holes wherever the
                            // animated background has not drawn, including below the status bar.
                            .background(settingsBackground)
                    ) {
                        // Settings live in a separate Activity, so they cannot sample the
                        // MainActivity RenderNode used by LocalAppBackdrop. Re-create the same
                        // persistent RawFlow layer inside this Activity and record it into the
                        // settings backdrop. Without this source a default settings page only
                        // records a flat theme color, so lens/refraction appears to do nothing.
                        RawFlowBackground(
                            mode = rawFlowMode,
                            sourceCoverKey = currentSong.resolvePlaybackArtworkKey(null),
                            modifier = Modifier.fillMaxSize(),
                        )
                        CustomMediaBackground(
                            active = CustomMediaBackgroundState.showOnSettings,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    CompositionLocalProvider(LocalSettingsBackdrop provides settingsBackdrop) {
                        content()
                    }
                    AppNoticeHost(
                        backdrop = settingsBackdrop,
                        modifier = Modifier.fillMaxSize(),
                    )
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
        SettingsBackHandoffRuntime.noteSettingsFinish()
        super.finish()
        if (Build.VERSION.SDK_INT < 34) {
            overridePendingTransition(R.anim.settings_enter_from_left, R.anim.settings_exit_to_right)
        }
    }
}
