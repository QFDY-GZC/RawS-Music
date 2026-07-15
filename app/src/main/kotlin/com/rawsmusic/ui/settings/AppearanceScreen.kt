package com.rawsmusic.ui.settings

import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.core.ui.theme.ThemeManager
import com.rawsmusic.core.ui.theme.RawThemeRuntimeState
import com.rawsmusic.core.ui.theme.ThemeManager.ThemeMode
import com.rawsmusic.core.ui.theme.ColorThemeMode
import com.rawsmusic.core.ui.theme.setColorThemeMode
import com.rawsmusic.core.ui.theme.getCurrentColorThemeMode
import androidx.compose.ui.res.stringResource
import com.rawsmusic.R

@Composable
fun LiquidGlassAppearanceScreen(
    onBack: () -> Unit
) {
    
    val fontFamily = appFontFamily()
    val context = LocalContext.current

    val themeRuntimeVersion = RawThemeRuntimeState.version
    var currentTheme by remember(themeRuntimeVersion) { mutableStateOf(ThemeManager.getCurrentTheme()) }

    SettingsPage(title = stringResource(R.string.settings_appearance_title), onBack = onBack) {
        SettingsCard {
            SectionHeader(stringResource(R.string.settings_appearance_theme_mode))
            Text(
                stringResource(R.string.settings_appearance_theme_mode_desc),
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
                        ThemeMode.LIGHT -> stringResource(R.string.settings_theme_light)
                        ThemeMode.DARK -> stringResource(R.string.settings_theme_dark)
                        ThemeMode.SYSTEM -> stringResource(R.string.settings_theme_system)
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.surfaceContainer)
                            .clickable {
                                currentTheme = mode
                                ThemeManager.applyTheme(mode)
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
            SectionHeader(stringResource(R.string.settings_appearance_color_scheme))
            Text(
                stringResource(R.string.settings_appearance_color_scheme_desc),
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 2.dp),
                fontFamily = fontFamily
            )
            Spacer(Modifier.height(12.dp))

            var currentColorTheme by remember(themeRuntimeVersion) { mutableStateOf(getCurrentColorThemeMode()) }

            val colorOptions = listOf(
                ColorThemeMode.MIUIX to stringResource(R.string.settings_color_miuix),
                ColorThemeMode.MONET_AUTO to stringResource(R.string.settings_color_monet_auto),
                ColorThemeMode.MONET_LIGHT to stringResource(R.string.settings_color_monet_light),
                ColorThemeMode.MONET_DARK to stringResource(R.string.settings_color_monet_dark)
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
                                .clickable {
                                    currentColorTheme = mode
                                    setColorThemeMode(mode, context)
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
