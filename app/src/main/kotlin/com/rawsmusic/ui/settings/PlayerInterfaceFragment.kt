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

class PlayerInterfaceFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LiquidGlassPlayerInterfaceScreen(
                    onBack = {
                        try {
                            NavHostFragment.findNavController(this@PlayerInterfaceFragment).navigateUp()
                        } catch (_: Exception) {}
                    }
                )
            }
        }
    }
}

@Composable
fun LiquidGlassPlayerInterfaceScreen(
    onBack: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val colors = themeColors()
    val fontFamily = appFontFamily()

    var immersiveEnabled by remember { mutableStateOf(AppPreferences.UI.isImmersiveEnabled) }
    var letterModeEnabled by remember { mutableStateOf(AppPreferences.UI.isLetterModeEnabled) }
    var flowingLightDisabled by remember { mutableStateOf(AppPreferences.UI.isFlowingLightDisabled) }
    var miniCoverEnabled by remember { mutableStateOf(AppPreferences.UI.isMiniCoverEnabled) }
    var playPageMemoryEnabled by remember { mutableStateOf(AppPreferences.UI.isPlayPageMemoryEnabled) }

    SettingsPage(title = "播放界面", onBack = onBack) {
        SettingsCard {
            SectionHeader("沉浸模式")
            SwitchRow("沉浸模式", immersiveEnabled) { checked ->
                immersiveEnabled = checked
                AppPreferences.UI.isImmersiveEnabled = checked
                android.content.Intent("com.rawsmusic.action.IMMERSIVE_SETTING_CHANGED").also {
                    it.setPackage(context.packageName)
                    context.sendBroadcast(it)
                }
            }
            Text(
                "进入播放界面时显示专辑封面背景",
                fontSize = 12.sp,
                color = colors.secondaryText,
                modifier = Modifier.padding(start = 0.dp, top = 2.dp),
                fontFamily = fontFamily
            )
        }

        Spacer(Modifier.height(12.dp))

        SettingsCard {
            SectionHeader("信笺模式")
            SwitchRow("信笺模式", letterModeEnabled) { checked ->
                letterModeEnabled = checked
                AppPreferences.UI.isLetterModeEnabled = checked
                android.content.Intent("com.rawsmusic.action.LETTER_MODE_SETTING_CHANGED").also {
                    it.setPackage(context.packageName)
                    context.sendBroadcast(it)
                }
            }
            Text(
                "弧形封面轮播、单行歌词飞入、音频可视化",
                fontSize = 12.sp,
                color = colors.secondaryText,
                modifier = Modifier.padding(start = 0.dp, top = 2.dp),
                fontFamily = fontFamily
            )
        }

        Spacer(Modifier.height(12.dp))

        SettingsCard {
            SectionHeader("流动光效果")
            SwitchRow("关闭流动光", flowingLightDisabled) { checked ->
                flowingLightDisabled = checked
                AppPreferences.UI.isFlowingLightDisabled = checked
                android.content.Intent("com.rawsmusic.action.FLOWING_LIGHT_SETTING_CHANGED").also {
                    it.setPackage(context.packageName)
                    context.sendBroadcast(it)
                }
            }
            Text(
                "关闭播放界面背景的动态流动光效果",
                fontSize = 12.sp,
                color = colors.secondaryText,
                modifier = Modifier.padding(start = 0.dp, top = 2.dp),
                fontFamily = fontFamily
            )
        }

        Spacer(Modifier.height(12.dp))

        SettingsCard {
            SectionHeader("常驻封面")
            SwitchRow("主界面常驻封面", miniCoverEnabled) { checked ->
                miniCoverEnabled = checked
                AppPreferences.UI.isMiniCoverEnabled = checked
                android.content.Intent("com.rawsmusic.action.MINI_COVER_SETTING_CHANGED").also {
                    it.setPackage(context.packageName)
                    context.sendBroadcast(it)
                }
            }
            Text(
                "在主界面胶囊栏显示专辑封面",
                fontSize = 12.sp,
                color = colors.secondaryText,
                modifier = Modifier.padding(start = 0.dp, top = 2.dp),
                fontFamily = fontFamily
            )
        }

        Spacer(Modifier.height(12.dp))

        SettingsCard {
            SectionHeader("界面记忆")
            SwitchRow("播放界面记忆", playPageMemoryEnabled) { checked ->
                playPageMemoryEnabled = checked
                AppPreferences.UI.isPlayPageMemoryEnabled = checked
            }
            Text(
                "重新打开应用时恢复到上次的播放界面",
                fontSize = 12.sp,
                color = colors.secondaryText,
                modifier = Modifier.padding(start = 0.dp, top = 2.dp),
                fontFamily = fontFamily
            )
        }
    }
}
