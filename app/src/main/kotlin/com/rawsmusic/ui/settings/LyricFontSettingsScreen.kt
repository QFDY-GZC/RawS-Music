package com.rawsmusic.ui.settings

import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.preference.SliderPreference
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.module.data.prefs.LyricFontManager
import com.rawsmusic.module.data.prefs.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import androidx.compose.ui.res.stringResource
import com.rawsmusic.R

@Composable
fun LiquidGlassLyricFontSettingsScreen(
    onImportFont: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    
    val coroutineScope = rememberCoroutineScope()

    var selectedFontPath by remember { mutableStateOf(AppPreferences.LyricFont.fontPath) }
    var fontWeight by remember { mutableStateOf(AppPreferences.LyricFont.fontWeight) }
    var fontScale by remember { mutableStateOf(AppPreferences.LyricFont.fontScale) }

    var systemFonts by remember { mutableStateOf<List<LyricFontManager.FontInfo>>(emptyList()) }
    var importedFonts by remember { mutableStateOf<List<LyricFontManager.FontInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        coroutineScope.launch {
            val sys = withContext(Dispatchers.IO) { LyricFontManager.getSystemFonts() }
            val imp = withContext(Dispatchers.IO) { LyricFontManager.getImportedFonts(context) }
            systemFonts = sys
            importedFonts = imp
            isLoading = false
        }
    }

    val previewFontFamily = remember(selectedFontPath) {
        if (selectedFontPath.isBlank()) FontFamily.Default
        else try {
            FontFamily(Font(File(selectedFontPath), FontWeight(fontWeight)))
        } catch (_: Exception) {
            FontFamily.Default
        }
    }

    SettingsPage(title = stringResource(R.string.settings_lyric_font_title), onBack = onBack) {
        SettingsCard {
            SectionHeader(stringResource(R.string.settings_font_preview))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MiuixTheme.colorScheme.surfaceContainer)
                    .padding(16.dp)
            ) {
                Text(
                    stringResource(R.string.settings_lyric_font_preview_text),
                    fontSize = (20 * fontScale / 100f).sp,
                    fontFamily = previewFontFamily,
                    fontWeight = FontWeight(fontWeight),
                    color = MiuixTheme.colorScheme.onBackground
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        SettingsCard {
            SectionHeader(stringResource(R.string.settings_default_system_font))
            FontItemRow(
                name = stringResource(R.string.settings_default_system_font),
                isSelected = selectedFontPath.isBlank(),
                onClick = {
                    selectedFontPath = ""
                    AppPreferences.LyricFont.fontPath = ""
                    AppPreferences.LyricFont.fontName = ""
                }
            )
        }

        Spacer(Modifier.height(12.dp))

        SettingsCard {
            SectionHeader(stringResource(R.string.settings_font_weight))
            Text(
                fontWeight.toString(),
                fontSize = 14.sp,
                color = MiuixTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp)
            )
            SliderPreference(
                title = stringResource(R.string.settings_font_weight),
                summary = null,
                valueText = fontWeight.toString(),
                value = fontWeight.toFloat(),
                onValueChange = {
                    fontWeight = it.toInt()
                    AppPreferences.LyricFont.fontWeight = fontWeight
                },
                valueRange = 100f..900f,
                steps = 15,
                hapticEffect = SliderDefaults.SliderHapticEffect.Step
            )
        }

        Spacer(Modifier.height(12.dp))

        SettingsCard {
            SectionHeader(stringResource(R.string.settings_lyric_font_scale))
            Text(
                stringResource(R.string.settings_percent_value, fontScale),
                fontSize = 14.sp,
                color = MiuixTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp)
            )
            SliderPreference(
                title = stringResource(R.string.settings_lyric_font_scale),
                summary = null,
                valueText = stringResource(R.string.settings_percent_value, fontScale),
                value = fontScale.toFloat(),
                onValueChange = {
                    fontScale = it.toInt()
                    AppPreferences.LyricFont.fontScale = fontScale
                },
                valueRange = 75f..130f,
                steps = 10,
                hapticEffect = SliderDefaults.SliderHapticEffect.Step
            )
        }

        Spacer(Modifier.height(12.dp))

        SettingsCard {
            SectionHeader(stringResource(R.string.settings_lyric_system_font))
            Text(
                stringResource(R.string.settings_lyric_system_font_desc),
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 2.dp),
                fontFamily = appFontFamily()
            )
            Spacer(Modifier.height(8.dp))

            if (isLoading) {
                Text(stringResource(R.string.settings_lyric_loading), fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontFamily = appFontFamily())
            } else {
                systemFonts.forEach { font ->
                    FontItemRow(
                        name = font.name,
                        isSelected = selectedFontPath == font.path,
                        onClick = {
                            selectedFontPath = font.path
                            AppPreferences.LyricFont.fontPath = font.path
                            AppPreferences.LyricFont.fontName = font.name
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        SettingsCard {
            SectionHeader(stringResource(R.string.settings_lyric_import_font))
            Text(
                stringResource(R.string.settings_lyric_import_font_desc),
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 2.dp),
                fontFamily = appFontFamily()
            )
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onImportFont) {
                Text(stringResource(R.string.settings_lyric_import_font_file), color = MiuixTheme.colorScheme.primary, fontSize = 14.sp, fontFamily = appFontFamily())
            }

            if (importedFonts.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                importedFonts.forEach { font ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FontItemRow(
                            name = font.name,
                            isSelected = selectedFontPath == font.path,
                            onClick = {
                                selectedFontPath = font.path
                                AppPreferences.LyricFont.fontPath = font.path
                                AppPreferences.LyricFont.fontName = font.name
                            },
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = {
                            LyricFontManager.deleteImportedFont(context, font.path)
                            if (selectedFontPath == font.path) {
                                selectedFontPath = ""
                            }
                            coroutineScope.launch {
                                importedFonts = withContext(Dispatchers.IO) {
                                    LyricFontManager.getImportedFonts(context)
                                }
                            }
                        }) {
                            Text(stringResource(R.string.settings_delete), color = Color.White, fontSize = 13.sp, fontFamily = appFontFamily())
                        }
                    }
                }
            }
        }
    }
}


@Composable
private fun FontItemRow(
    name: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (isSelected) MiuixTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            name,
            fontSize = 14.sp,
            color = if (isSelected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onBackgroundVariant,
            maxLines = 1,
            modifier = Modifier.weight(1f)
        )
        if (isSelected) {
            Text(
                "✓",
                fontSize = 16.sp,
                color = MiuixTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
