package com.rawsmusic.ui.settings

import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.preference.SliderPreference
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.data.prefs.FontManager
import com.rawsmusic.core.ui.theme.RawThemeRuntimeState
import androidx.compose.ui.res.stringResource
import com.rawsmusic.R

@Composable
fun GlobalFontSettingsScreen(
    onBack: () -> Unit,
    onApply: () -> Unit
) {
    

    var fontWeight by remember { mutableStateOf(AppPreferences.UI.fontWeight) }
    var fontSizeScale by remember { mutableStateOf(AppPreferences.UI.fontSizeScale) }
    var fontItalic by remember { mutableStateOf(AppPreferences.UI.fontItalic) }

    val context = LocalContext.current
    fun applyFontSettings() {
        FontManager.rebuildTypeface(context)
        FontManager.clearScaledCache()
        RawThemeRuntimeState.invalidate()
        onApply()
    }
    val previewFontFamily = FontFamily.Default

    SettingsPage(title = stringResource(R.string.settings_global_font_title), onBack = onBack) {
        SettingsCard {
            SectionHeader(stringResource(R.string.settings_font_preview))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MiuixTheme.colorScheme.surfaceContainer)
                    .padding(16.dp)
            ) {
                Column {
                    Text(
                        stringResource(R.string.settings_global_font_preview_title),
                        fontSize = (22 * fontSizeScale / 100f).sp,
                        fontFamily = previewFontFamily,
                        fontWeight = FontWeight(fontWeight),
                        fontStyle = if (fontItalic) androidx.compose.ui.text.font.FontStyle.Italic else androidx.compose.ui.text.font.FontStyle.Normal,
                        color = MiuixTheme.colorScheme.onBackground
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.settings_global_font_preview_alphabet),
                        fontSize = (14 * fontSizeScale / 100f).sp,
                        fontFamily = previewFontFamily,
                        fontWeight = FontWeight(fontWeight),
                        fontStyle = if (fontItalic) androidx.compose.ui.text.font.FontStyle.Italic else androidx.compose.ui.text.font.FontStyle.Normal,
                        color = MiuixTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        stringResource(R.string.settings_global_font_preview_mixed),
                        fontSize = (14 * fontSizeScale / 100f).sp,
                        fontFamily = previewFontFamily,
                        fontWeight = FontWeight(fontWeight),
                        fontStyle = if (fontItalic) androidx.compose.ui.text.font.FontStyle.Italic else androidx.compose.ui.text.font.FontStyle.Normal,
                        color = MiuixTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        SettingsCard {
            SectionHeader(stringResource(R.string.settings_font_weight))
            val weightLabel = when {
                fontWeight < 200 -> "Thin"
                fontWeight < 300 -> "ExtraLight"
                fontWeight < 350 -> "Light"
                fontWeight < 400 -> "Regular"
                fontWeight < 500 -> "Medium"
                fontWeight < 600 -> "Demibold"
                fontWeight < 700 -> "Semibold"
                else -> "Bold"
            }
            Text(
                stringResource(R.string.settings_font_weight_value, weightLabel, fontWeight),
                fontSize = 14.sp,
                color = MiuixTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp)
            )
            SliderPreference(
                title = stringResource(R.string.settings_font_weight),
                summary = null,
                valueText = stringResource(R.string.settings_font_weight_value, weightLabel, fontWeight),
                value = fontWeight.toFloat(),
                onValueChange = {
                    fontWeight = it.toInt()
                    AppPreferences.UI.fontWeight = fontWeight
                    applyFontSettings()
                },
                valueRange = 150f..700f,
                steps = 10,
                hapticEffect = SliderDefaults.SliderHapticEffect.Step
            )
        }

        Spacer(Modifier.height(12.dp))

        SettingsCard {
            SectionHeader(stringResource(R.string.settings_font_size))
            Text(
                stringResource(R.string.settings_percent_value, fontSizeScale),
                fontSize = 14.sp,
                color = MiuixTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp)
            )
            SliderPreference(
                title = stringResource(R.string.settings_font_size),
                summary = null,
                valueText = stringResource(R.string.settings_percent_value, fontSizeScale),
                value = fontSizeScale.toFloat(),
                onValueChange = {
                    fontSizeScale = it.toInt()
                    AppPreferences.UI.fontSizeScale = fontSizeScale
                    applyFontSettings()
                },
                valueRange = 80f..130f,
                steps = 9,
                hapticEffect = SliderDefaults.SliderHapticEffect.Step
            )
        }

        Spacer(Modifier.height(12.dp))

        SettingsCard {
            SwitchRow(
                label = stringResource(R.string.settings_font_italic),
                checked = fontItalic,
                onCheckedChange = {
                    fontItalic = it
                    AppPreferences.UI.fontItalic = fontItalic
                    applyFontSettings()
                }
            )
        }
    }
}
