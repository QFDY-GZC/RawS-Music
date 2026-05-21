package com.rawsmusic.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.NavHostFragment
import com.rawsmusic.core.ui.theme.ThemeManager
import com.rawsmusic.core.ui.theme.ThemeManager.ThemeMode

class AppearanceFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LiquidGlassAppearanceScreen(
                    onBack = {
                        try {
                            NavHostFragment.findNavController(this@AppearanceFragment).navigateUp()
                        } catch (_: Exception) {}
                    }
                )
            }
        }
    }
}

@Composable
fun LiquidGlassAppearanceScreen(
    onBack: () -> Unit
) {
    val colors = themeColors()
    val fontFamily = appFontFamily()

    var currentTheme by remember { mutableStateOf(ThemeManager.getCurrentTheme()) }

    SettingsPage(title = "外观主题", onBack = onBack) {
        SettingsCard {
            SectionHeader("主题模式")
            Text(
                "选择应用的显示主题",
                fontSize = 13.sp,
                color = colors.secondaryText,
                modifier = Modifier.padding(top = 2.dp),
                fontFamily = fontFamily
            )
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ThemeMode.values().forEach { mode ->
                    val isSelected = currentTheme == mode
                    val label = when (mode) {
                        ThemeMode.LIGHT -> "亮色"
                        ThemeMode.DARK -> "暗色"
                        ThemeMode.SYSTEM -> "跟随系统"
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) colors.primary else colors.surface)
                            .pointerInput(Unit) {
                                detectTapGestures {
                                    currentTheme = mode
                                    ThemeManager.applyTheme(mode)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            label,
                            fontSize = 14.sp,
                            fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                            color = if (isSelected) colors.onPrimary else colors.onSurfaceVariant,
                            fontFamily = fontFamily
                        )
                    }
                }
            }
        }
    }
}
