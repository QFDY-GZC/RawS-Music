package com.rawsmusic.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.rawsmusic.module.data.prefs.AppPreferences

@Composable
fun LiquidGlassAlbumArtSettingsScreen(
    onBack: () -> Unit
) {
    var forceArgb8888 by remember { mutableStateOf(AppPreferences.AlbumArt.forceArgb8888) }
    var useHigherRes by remember { mutableStateOf(AppPreferences.AlbumArt.useHigherRes) }
    var alwaysShowCover by remember { mutableStateOf(AppPreferences.AlbumArt.alwaysShowCover) }
    var coverAnimation by remember { mutableStateOf(AppPreferences.AlbumArt.coverAnimation) }

    SettingsPage(title = "专辑图", onBack = onBack) {
        SettingsSection("画质") {
            SwitchRow("24位 RGB (ARGB_8888)", forceArgb8888) { checked ->
                forceArgb8888 = checked
                AppPreferences.AlbumArt.forceArgb8888 = checked
            }
            SettingsInfoEntry(
                title = "强制使用 ARGB_8888 解码",
                description = "使用 32位色深（24位RGB+Alpha），画质最佳但内存占用增加约一倍。低内存设备（<128MB）建议关闭。"
            )
            SwitchRow("高清封面", useHigherRes) { checked ->
                useHigherRes = checked
                AppPreferences.AlbumArt.useHigherRes = checked
            }
            SettingsInfoEntry(
                title = "使用更高分辨率封面",
                description = "解码目标尺寸翻倍，播放界面封面更清晰。"
            )
        }

        SettingsSection("显示") {
            SwitchRow("始终显示封面", alwaysShowCover) { checked ->
                alwaysShowCover = checked
                AppPreferences.AlbumArt.alwaysShowCover = checked
            }
            SwitchRow("封面切换动画", coverAnimation) { checked ->
                coverAnimation = checked
                AppPreferences.AlbumArt.coverAnimation = checked
            }
        }
    }
}
