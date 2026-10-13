package com.rawsmusic.ui.settings

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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.preference.SliderPreference
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.module.data.prefs.LyricFontManager
import com.rawsmusic.module.data.prefs.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import androidx.compose.ui.res.stringResource
import com.rawsmusic.R
import com.rawsmusic.core.ui.theme.rawFileFontFamily

@Composable
fun LiquidGlassLyricFontSettingsScreen(
    onImportFont: () -> Unit,
    onBack: () -> Unit
) {
    SettingsPage(
        title = stringResource(R.string.settings_font_settings_title),
        onBack = onBack,
    ) {
        LyricFontSettingsContent(onImportFont = onImportFont)
    }
}

@Composable
internal fun LyricFontSettingsContent(
    onImportFont: () -> Unit,
) {
    val context = LocalContext.current
    
    val fontRevision by LyricFontManager.revision.collectAsState()

    var selectedFontPath by remember { mutableStateOf(AppPreferences.LyricFont.fontPath) }
    var fontWeight by remember { mutableStateOf(AppPreferences.LyricFont.fontWeight) }
    var experimentalWeight by remember {
        mutableStateOf(AppPreferences.LyricFont.experimentalFontWeightEnabled)
    }
    var fontScale by remember { mutableStateOf(AppPreferences.LyricFont.fontScale) }

    var systemFonts by remember { mutableStateOf<List<LyricFontManager.FontInfo>>(emptyList()) }
    var importedFonts by remember { mutableStateOf<List<LyricFontManager.FontInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var showFontCatalog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        systemFonts = withContext(Dispatchers.IO) { LyricFontManager.getSystemFonts() }
        isLoading = false
    }

    LaunchedEffect(fontRevision) {
        selectedFontPath = AppPreferences.LyricFont.fontPath
        importedFonts = withContext(Dispatchers.IO) { LyricFontManager.getImportedFonts(context) }
    }

    val previewFontFamily = remember(selectedFontPath, fontWeight, experimentalWeight, fontRevision) {
        if (selectedFontPath.isBlank()) FontFamily.Default
        else runCatching {
            rawFileFontFamily(
                path = selectedFontPath,
                allowOutOfRange = experimentalWeight,
            )
        }
            .getOrDefault(FontFamily.Default)
    }

    Column {
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
            SettingsNavigationEntry(
                title = stringResource(R.string.settings_font_family),
                description = when {
                    selectedFontPath.isBlank() -> stringResource(R.string.settings_default_system_font)
                    else -> File(selectedFontPath).nameWithoutExtension
                },
                onClick = { showFontCatalog = true },
            )
        }

        Spacer(Modifier.height(12.dp))

        SettingsCard {
            FontWeightControl(
                fontPath = selectedFontPath,
                weight = fontWeight,
                experimentalEnabled = experimentalWeight,
                revision = fontRevision,
                onWeightChange = { value ->
                    fontWeight = value
                    LyricFontManager.setFontWeight(value)
                },
                onExperimentalChange = { enabled ->
                    experimentalWeight = enabled
                    AppPreferences.LyricFont.experimentalFontWeightEnabled = enabled
                    LyricFontManager.setFontWeight(fontWeight)
                },
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
                    LyricFontManager.setFontScale(fontScale)
                },
                valueRange = 60f..180f,
                steps = 23,
                hapticEffect = SliderDefaults.SliderHapticEffect.Step
            )
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
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.settings_font_imported_count, importedFonts.size),
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontFamily = appFontFamily(),
                )
            }
        }
    }

    if (showFontCatalog) {
        FontCatalogDialog(
            title = stringResource(R.string.settings_font_family),
            selectedPath = selectedFontPath,
            systemFonts = if (isLoading) emptyList() else systemFonts,
            importedFonts = importedFonts,
            onDismiss = { showFontCatalog = false },
            onSelect = { font ->
                selectedFontPath = font?.path.orEmpty()
                experimentalWeight = false
                AppPreferences.LyricFont.experimentalFontWeightEnabled = false
                LyricFontManager.selectFont(font)
            },
            onImport = onImportFont,
        )
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
