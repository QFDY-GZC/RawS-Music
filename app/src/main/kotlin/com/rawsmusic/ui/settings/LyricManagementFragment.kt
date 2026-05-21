package com.rawsmusic.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.NavHostFragment
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.player.LyriconProviderManager

class LyricManagementFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LiquidGlassLyricManagementScreen(
                    onNavigateToLyricFontSettings = {
                        try {
                            NavHostFragment.findNavController(this@LyricManagementFragment)
                                .navigate(com.rawsmusic.R.id.nav_lyric_font_settings)
                        } catch (_: Exception) {}
                    },
                    onBack = {
                        try {
                            NavHostFragment.findNavController(this@LyricManagementFragment).navigateUp()
                        } catch (_: Exception) {}
                    }
                )
            }
        }
    }
}

@Composable
fun LiquidGlassLyricManagementScreen(
    onNavigateToLyricFontSettings: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val colors = themeColors()

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

    val fontFamily = appFontFamily()

    SettingsPage(title = "歌词管理", onBack = onBack) {
        SettingsCard {
            SectionHeader("词幕（Lyricon）")
            Text(
                lyriconStatus,
                fontSize = 13.sp,
                color = colors.secondaryText,
                modifier = Modifier.padding(top = 2.dp),
                fontFamily = fontFamily
            )
            Text(
                "向系统状态栏推送歌词显示。需要安装词幕中央服务才能连接。",
                fontSize = 13.sp,
                color = colors.secondaryText,
                modifier = Modifier.padding(top = 8.dp),
                fontFamily = fontFamily
            )
            Spacer(Modifier.height(12.dp))
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

        Spacer(Modifier.height(12.dp))

        SettingsCard {
            SectionHeader("歌词字体")
            Text(
                "自定义歌词显示的字体、字重和缩放",
                fontSize = 13.sp,
                color = colors.secondaryText,
                modifier = Modifier.padding(top = 2.dp),
                fontFamily = fontFamily
            )
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onNavigateToLyricFontSettings) {
                Text("进入歌词字体设置 →", color = colors.primary, fontSize = 14.sp, fontFamily = fontFamily)
            }
        }
    }
}
