package com.rawsmusic.ui.settings

import top.yukonga.miuix.kmp.theme.MiuixTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.core.ui.theme.ThemeManager
import com.rawsmusic.core.ui.theme.ThemeManager.ThemeMode
import com.rawsmusic.core.ui.theme.ColorThemeMode
import com.rawsmusic.core.ui.theme.setColorThemeMode
import com.rawsmusic.core.ui.theme.getCurrentColorThemeMode

@Composable
fun LiquidGlassAppearanceScreen(
    onBack: () -> Unit
) {
    
    val fontFamily = appFontFamily()
    val context = LocalContext.current

    var currentTheme by remember { mutableStateOf(ThemeManager.getCurrentTheme()) }

    SettingsPage(title = "外观主题", onBack = onBack) {
        SettingsCard {
            SectionHeader("主题模式")
            Text(
                "选择应用的显示主题",
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
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
                            .background(if (isSelected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.surfaceContainer)
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
                            color = if (isSelected) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onBackgroundVariant,
                            fontFamily = fontFamily
                        )
                    }
                }
            }
        }

        SettingsCard {
            SectionHeader("配色方案")
            Text(
                "选择应用的配色风格",
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 2.dp),
                fontFamily = fontFamily
            )
            Spacer(Modifier.height(12.dp))

            var currentColorTheme by remember { mutableStateOf(getCurrentColorThemeMode()) }

            val colorOptions = listOf(
                ColorThemeMode.MIUIX to "MIUIx",
                ColorThemeMode.MONET_AUTO to "Monet 自动",
                ColorThemeMode.MONET_LIGHT to "Monet 浅色",
                ColorThemeMode.MONET_DARK to "Monet 深色"
            )

            colorOptions.chunked(2).forEach { row ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    row.forEach { (mode, label) ->
                        val isSelected = currentColorTheme == mode
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.surfaceContainer)
                                .pointerInput(Unit) {
                                    detectTapGestures {
                                        currentColorTheme = mode
                                        setColorThemeMode(mode, context)
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                label,
                                fontSize = 14.sp,
                                fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                                color = if (isSelected) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onBackgroundVariant,
                                fontFamily = fontFamily
                            )
                        }
                    }
                    if (row.size == 1) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}
