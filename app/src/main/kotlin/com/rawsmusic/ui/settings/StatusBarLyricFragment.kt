package com.rawsmusic.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.NavHostFragment
import com.rawsmusic.module.data.prefs.AppPreferences

class StatusBarLyricFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LiquidGlassStatusBarLyricScreen(
                    onBack = {
                        try {
                            NavHostFragment.findNavController(this@StatusBarLyricFragment).navigateUp()
                        } catch (_: Exception) {}
                    }
                )
            }
        }
    }
}

@Composable
fun LiquidGlassStatusBarLyricScreen(
    onBack: () -> Unit
) {
    val colors = themeColors()
    val lyricsPrefs = AppPreferences.Lyrics

    var tickerEnabled by remember { mutableStateOf(lyricsPrefs.tickerEnabled) }
    var tickerHideNotification by remember { mutableStateOf(lyricsPrefs.tickerHideNotification) }
    var samsungFloatingLyricTranslation by remember { mutableStateOf(lyricsPrefs.samsungFloatingLyricTranslation) }
    var lyricGetterEnabled by remember { mutableStateOf(lyricsPrefs.lyricGetterEnabled) }
    var bluetoothLyricEnabled by remember { mutableStateOf(lyricsPrefs.bluetoothLyricEnabled) }
    var bluetoothLyricTranslation by remember { mutableStateOf(lyricsPrefs.bluetoothLyricTranslation) }

    SettingsPage(title = "状态栏歌词", onBack = onBack) {
        SettingsCard {
            SectionHeader("Flyme 状态栏歌词")
            Text(
                "通过 Flyme 系统状态栏推送歌词显示",
                fontSize = 13.sp,
                color = colors.secondaryText,
                modifier = Modifier.padding(top = 2.dp),
                fontFamily = appFontFamily()
            )
            Spacer(Modifier.height(12.dp))
            SwitchRow("Flyme 状态栏歌词", tickerEnabled) { checked ->
                tickerEnabled = checked
                lyricsPrefs.tickerEnabled = checked
            }
            SwitchRow("隐藏独立歌词通知", tickerHideNotification, enabled = tickerEnabled) { checked ->
                tickerHideNotification = checked
                lyricsPrefs.tickerHideNotification = checked
            }
        }

        Spacer(Modifier.height(12.dp))

        SettingsCard {
            SectionHeader("三星浮动歌词")
            SwitchRow("三星浮动歌词翻译", samsungFloatingLyricTranslation, enabled = tickerEnabled) { checked ->
                samsungFloatingLyricTranslation = checked
                lyricsPrefs.samsungFloatingLyricTranslation = checked
            }
        }

        Spacer(Modifier.height(12.dp))

        SettingsCard {
            SectionHeader("Lyric Getter")
            Text(
                "通过 Lyric Getter 推送歌词到其他应用",
                fontSize = 13.sp,
                color = colors.secondaryText,
                modifier = Modifier.padding(top = 2.dp),
                fontFamily = appFontFamily()
            )
            Spacer(Modifier.height(12.dp))
            SwitchRow("Lyric Getter 歌词", lyricGetterEnabled) { checked ->
                lyricGetterEnabled = checked
                lyricsPrefs.lyricGetterEnabled = checked
            }
        }

        Spacer(Modifier.height(12.dp))

        SettingsCard {
            SectionHeader("蓝牙车载歌词")
            Text(
                "通过蓝牙将歌词推送到车载系统",
                fontSize = 13.sp,
                color = colors.secondaryText,
                modifier = Modifier.padding(top = 2.dp),
                fontFamily = appFontFamily()
            )
            Spacer(Modifier.height(12.dp))
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
