package com.rawsmusic.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.rawsmusic.module.data.prefs.AppPreferences

@Composable
fun LiquidGlassPlayerInterfaceScreen(
    onBack: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    var defaultBackgroundEnabled by remember { mutableStateOf(AppPreferences.UI.isDefaultBackgroundEnabled) }
    var immersiveEnabled by remember { mutableStateOf(AppPreferences.UI.isImmersiveEnabled) }
    var audioVisualizerEnabled by remember { mutableStateOf(AppPreferences.UI.isAudioVisualizerEnabled) }
    var miniCoverEnabled by remember { mutableStateOf(AppPreferences.UI.isMiniCoverEnabled) }
    var playPageMemoryEnabled by remember { mutableStateOf(AppPreferences.UI.isPlayPageMemoryEnabled) }

    SettingsPage(title = "界面设置", onBack = onBack) {
        SettingsSection("背景") {
            SwitchRow("默认背景", defaultBackgroundEnabled) { checked ->
                defaultBackgroundEnabled = checked
                AppPreferences.UI.isDefaultBackgroundEnabled = checked
                android.content.Intent("com.rawsmusic.action.DEFAULT_BACKGROUND_SETTING_CHANGED").also {
                    it.setPackage(context.packageName)
                    context.sendBroadcast(it)
                }
            }
            SettingsInfoEntry(
                title = "默认背景规则",
                description = "亮色模式白底黑字，暗色模式纯黑底白字"
            )
        }

        SettingsSection("播放界面") {
            SwitchRow("沉浸模式", immersiveEnabled) { checked ->
                immersiveEnabled = checked
                AppPreferences.UI.isImmersiveEnabled = checked
                android.content.Intent("com.rawsmusic.action.IMMERSIVE_SETTING_CHANGED").also {
                    it.setPackage(context.packageName)
                    context.sendBroadcast(it)
                }
            }
            SettingsInfoEntry(
                title = "沉浸模式",
                description = "进入播放界面时显示专辑封面背景"
            )
            SwitchRow("音频可视化", audioVisualizerEnabled) { checked ->
                audioVisualizerEnabled = checked
                AppPreferences.UI.isAudioVisualizerEnabled = checked
                android.content.Intent("com.rawsmusic.action.AUDIO_VISUALIZER_SETTING_CHANGED").also {
                    it.setPackage(context.packageName)
                    context.sendBroadcast(it)
                }
            }
            SettingsInfoEntry(
                title = "音频可视化",
                description = "播放界面底部显示音频频谱动画"
            )
        }

        SettingsSection("主界面") {
            SwitchRow("主界面常驻封面", miniCoverEnabled) { checked ->
                miniCoverEnabled = checked
                AppPreferences.UI.isMiniCoverEnabled = checked
                android.content.Intent("com.rawsmusic.action.MINI_COVER_SETTING_CHANGED").also {
                    it.setPackage(context.packageName)
                    context.sendBroadcast(it)
                }
            }
            SettingsInfoEntry(
                title = "主界面常驻封面",
                description = "在主界面胶囊栏显示专辑封面"
            )
            SwitchRow("播放界面记忆", playPageMemoryEnabled) { checked ->
                playPageMemoryEnabled = checked
                AppPreferences.UI.isPlayPageMemoryEnabled = checked
            }
            SettingsInfoEntry(
                title = "播放界面记忆",
                description = "重新打开应用时恢复到上次的播放界面"
            )
        }
    }
}
