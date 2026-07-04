package com.rawsmusic.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.player.LyriconProviderManager

@Composable
fun LiquidGlassLyricManagementScreen(
    onNavigateToLyricFontSettings: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val lyriconPrefs = AppPreferences.Lyricon
    var lyriconEnabled by remember { mutableStateOf(lyriconPrefs.enabled) }
    var lyriconTranslation by remember { mutableStateOf(lyriconPrefs.displayTranslation) }
    var lyriconRoma by remember { mutableStateOf(lyriconPrefs.displayRoma) }
    var lyriconStatus by remember {
        mutableStateOf(
            if (!lyriconEnabled) "未启用"
            else if (LyriconProviderManager.isConnected()) "已连接"
            else "未连接"
        )
    }

    LaunchedEffect(lyriconEnabled) {
        if (lyriconEnabled) {
            LyriconProviderManager.onConnectionStatusChanged = { status ->
                lyriconStatus = when {
                    !AppPreferences.Lyricon.enabled -> "未启用"
                    status == io.github.proify.lyricon.provider.ConnectionStatus.CONNECTED -> "已连接"
                    status == io.github.proify.lyricon.provider.ConnectionStatus.CONNECTING -> "连接中…"
                    else -> "未连接"
                }
            }
        } else {
            LyriconProviderManager.onConnectionStatusChanged = null
        }
    }

    SettingsPage(title = "歌词管理", onBack = onBack) {
        SettingsSection("词幕") {
            SettingsInfoEntry(
                title = "Lyricon 状态",
                description = "$lyriconStatus · 需要安装词幕中央服务才能连接"
            )
            SwitchRow("启用词幕推送", lyriconEnabled) { checked ->
                lyriconEnabled = checked
                lyriconPrefs.enabled = checked
                if (checked) {
                    LyriconProviderManager.init(context.applicationContext, com.rawsmusic.R.mipmap.ic_launcher)
                    lyriconStatus = if (LyriconProviderManager.isConnected()) "已连接" else "未连接"
                } else {
                    LyriconProviderManager.stopPositionSync()
                    LyriconProviderManager.destroy()
                    lyriconStatus = "未启用"
                }
            }
            SwitchRow("显示翻译", lyriconTranslation, enabled = lyriconEnabled) { checked ->
                lyriconTranslation = checked
                LyriconProviderManager.setDisplayTranslation(checked)
            }
            SwitchRow("显示罗马音", lyriconRoma, enabled = lyriconEnabled) { checked ->
                lyriconRoma = checked
                LyriconProviderManager.setDisplayRoma(checked)
            }
        }

        SettingsSection("显示") {
            SettingsNavigationEntry(
                title = "歌词字体",
                description = "字体、字重、缩放和显示样式",
                onClick = onNavigateToLyricFontSettings
            )
        }
    }
}
