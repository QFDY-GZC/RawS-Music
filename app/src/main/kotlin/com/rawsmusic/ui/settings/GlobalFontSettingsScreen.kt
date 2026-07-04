package com.rawsmusic.ui.settings

import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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

@Composable
fun GlobalFontSettingsScreen(
    onBack: () -> Unit,
    onApply: () -> Unit
) {
    

    var fontWeight by remember { mutableStateOf(AppPreferences.UI.fontWeight) }
    var fontSizeScale by remember { mutableStateOf(AppPreferences.UI.fontSizeScale) }
    var fontItalic by remember { mutableStateOf(AppPreferences.UI.fontItalic) }

    val context = LocalContext.current
    val previewFontFamily = remember(fontWeight) {
        try {
            val tf = android.graphics.Typeface.Builder(context.assets, "fonts/MiSansLatinVF.ttf")
                .setFontVariationSettings("wght $fontWeight")
                .build()
            FontFamily(tf)
        } catch (_: Exception) {
            FontFamily.Default
        }
    }

    SettingsPage(title = "全局字体", onBack = onBack) {
        SettingsCard {
            SectionHeader("预览")
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MiuixTheme.colorScheme.surfaceContainer)
                    .padding(16.dp)
            ) {
                Column {
                    Text(
                        "RawS Music",
                        fontSize = (22 * fontSizeScale / 100f).sp,
                        fontFamily = previewFontFamily,
                        fontWeight = FontWeight(fontWeight),
                        fontStyle = if (fontItalic) androidx.compose.ui.text.font.FontStyle.Italic else androidx.compose.ui.text.font.FontStyle.Normal,
                        color = MiuixTheme.colorScheme.onBackground
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "ABCDEFGHIJKLMNOPQRSTUVWXYZ",
                        fontSize = (14 * fontSizeScale / 100f).sp,
                        fontFamily = previewFontFamily,
                        fontWeight = FontWeight(fontWeight),
                        fontStyle = if (fontItalic) androidx.compose.ui.text.font.FontStyle.Italic else androidx.compose.ui.text.font.FontStyle.Normal,
                        color = MiuixTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "0123456789 こんにちは 你好",
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
            SectionHeader("字重")
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
                "$weightLabel ($fontWeight)",
                fontSize = 14.sp,
                color = MiuixTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp)
            )
            Slider(
                value = fontWeight.toFloat(),
                onValueChange = {
                    fontWeight = it.toInt()
                    AppPreferences.UI.fontWeight = fontWeight
                },
                valueRange = 150f..700f,
                steps = 10,
                colors = SliderDefaults.colors(thumbColor = MiuixTheme.colorScheme.primary, activeTrackColor = MiuixTheme.colorScheme.primary)
            )
        }

        Spacer(Modifier.height(12.dp))

        SettingsCard {
            SectionHeader("字体大小")
            Text(
                "$fontSizeScale%",
                fontSize = 14.sp,
                color = MiuixTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp)
            )
            Slider(
                value = fontSizeScale.toFloat(),
                onValueChange = {
                    fontSizeScale = it.toInt()
                    AppPreferences.UI.fontSizeScale = fontSizeScale
                },
                valueRange = 80f..130f,
                steps = 9,
                colors = SliderDefaults.colors(thumbColor = MiuixTheme.colorScheme.primary, activeTrackColor = MiuixTheme.colorScheme.primary)
            )
        }

        Spacer(Modifier.height(12.dp))

        SettingsCard {
            SwitchRow(
                label = "斜体",
                checked = fontItalic,
                onCheckedChange = {
                    fontItalic = it
                    AppPreferences.UI.fontItalic = fontItalic
                }
            )
        }
    }
}
