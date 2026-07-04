package com.rawsmusic.helper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.rawsmusic.core.ui.theme.RawThemeRuntimeState

/**
 * 主题/外观变更协调器。
 *
 * 统一处理所有外观相关广播，避免 MainActivity receiver 膨胀。
 */
class ThemeCoordinator(
    private val context: Context,
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
    private val applyDefaultBackground: () -> Unit,
    private val syncImmersiveBackgroundSettings: () -> Unit,
    private val refreshImmersiveState: () -> Unit,
    private val updateHiresBadge: () -> Unit,
    private val reapplyScene: () -> Unit
) {
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_IMMERSIVE -> {
                    refreshImmersiveState()
                    applyDefaultBackground()
                    syncImmersiveBackgroundSettings()
                    mainHandler.post { updateHiresBadge() }
                }
                ACTION_DEFAULT_BACKGROUND -> {
                    applyDefaultBackground()
                }
                ACTION_MINI_COVER -> {
                    refreshImmersiveState()
                    syncImmersiveBackgroundSettings()
                    mainHandler.post { updateHiresBadge() }
                }
                ACTION_FLOWING_LIGHT -> {
                    mainHandler.post { reapplyScene() }
                }
                ACTION_COLOR_THEME -> {
                    RawThemeRuntimeState.invalidate()
                    applyDefaultBackground()
                    syncImmersiveBackgroundSettings()
                    mainHandler.post {
                        updateHiresBadge()
                        reapplyScene()
                    }
                }
            }
        }
    }

    fun register() {
        val filter = IntentFilter().apply {
            addAction(ACTION_IMMERSIVE)
            addAction(ACTION_DEFAULT_BACKGROUND)
            addAction(ACTION_MINI_COVER)
            addAction(ACTION_FLOWING_LIGHT)
            addAction(ACTION_COLOR_THEME)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
    }

    fun unregister() {
        try {
            context.unregisterReceiver(receiver)
        } catch (_: Exception) {}
    }

    companion object {
        const val ACTION_IMMERSIVE = "com.rawsmusic.action.IMMERSIVE_SETTING_CHANGED"
        const val ACTION_DEFAULT_BACKGROUND = "com.rawsmusic.action.DEFAULT_BACKGROUND_SETTING_CHANGED"
        const val ACTION_MINI_COVER = "com.rawsmusic.action.MINI_COVER_SETTING_CHANGED"
        const val ACTION_FLOWING_LIGHT = "com.rawsmusic.action.FLOWING_LIGHT_SETTING_CHANGED"
        const val ACTION_COLOR_THEME = "com.rawsmusic.action.COLOR_THEME_CHANGED"
    }
}
