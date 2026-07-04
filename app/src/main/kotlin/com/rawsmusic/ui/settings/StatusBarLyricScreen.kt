package com.rawsmusic.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.rawsmusic.module.data.prefs.AppPreferences

@Composable
fun LiquidGlassStatusBarLyricScreen(
    onBack: () -> Unit
) {
    val lyricsPrefs = AppPreferences.Lyrics

    var tickerEnabled by remember { mutableStateOf(lyricsPrefs.tickerEnabled) }
    var tickerHideNotification by remember { mutableStateOf(lyricsPrefs.tickerHideNotification) }
    var tickerHeadsUpLyrics by remember { mutableStateOf(lyricsPrefs.tickerHeadsUpLyrics) }
    var samsungFloatingLyricTranslation by remember { mutableStateOf(lyricsPrefs.samsungFloatingLyricTranslation) }
    var lyricGetterEnabled by remember { mutableStateOf(lyricsPrefs.lyricGetterEnabled) }
    var bluetoothLyricEnabled by remember { mutableStateOf(lyricsPrefs.bluetoothLyricEnabled) }
    var bluetoothLyricTranslation by remember { mutableStateOf(lyricsPrefs.bluetoothLyricTranslation) }

    SettingsPage(title = "状态栏歌词", onBack = onBack) {
        SettingsSection("Flyme") {
            SettingsInfoEntry(
                title = "状态栏歌词",
                description = "通过 Flyme 系统状态栏推送歌词显示"
            )
            SwitchRow("Flyme 状态栏歌词", tickerEnabled) { checked ->
                tickerEnabled = checked
                lyricsPrefs.tickerEnabled = checked
            }
            SwitchRow("隐藏独立歌词通知", tickerHideNotification, enabled = tickerEnabled) { checked ->
                tickerHideNotification = checked
                lyricsPrefs.tickerHideNotification = checked
            }
            SwitchRow("悬浮歌词（非魅族设备）", tickerHeadsUpLyrics, enabled = tickerEnabled) { checked ->
                tickerHeadsUpLyrics = checked
                lyricsPrefs.tickerHeadsUpLyrics = checked
            }
        }

        SettingsSection("三星") {
            SwitchRow("三星浮动歌词翻译", samsungFloatingLyricTranslation, enabled = tickerEnabled) { checked ->
                samsungFloatingLyricTranslation = checked
                lyricsPrefs.samsungFloatingLyricTranslation = checked
            }
        }

        SettingsSection("Lyric Getter") {
            SettingsInfoEntry(
                title = "外部歌词",
                description = "通过 Lyric Getter 推送歌词到其他应用"
            )
            SwitchRow("Lyric Getter 歌词", lyricGetterEnabled) { checked ->
                lyricGetterEnabled = checked
                lyricsPrefs.lyricGetterEnabled = checked
            }
        }

        SettingsSection("蓝牙") {
            SettingsInfoEntry(
                title = "车载歌词",
                description = "通过蓝牙将歌词推送到车载系统"
            )
            SwitchRow("蓝牙车载歌词", bluetoothLyricEnabled) { checked ->
                bluetoothLyricEnabled = checked
                lyricsPrefs.bluetoothLyricEnabled = checked
            }
            SwitchRow("车载歌词翻译", bluetoothLyricTranslation, enabled = bluetoothLyricEnabled) { checked ->
                bluetoothLyricTranslation = checked
                lyricsPrefs.bluetoothLyricTranslation = checked
            }
        }
    }
}
