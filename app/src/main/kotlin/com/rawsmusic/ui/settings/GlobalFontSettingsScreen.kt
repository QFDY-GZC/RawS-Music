package com.rawsmusic.ui.settings

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.rawsmusic.R
import com.rawsmusic.core.ui.theme.RawThemeRuntimeState
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.data.prefs.FontManager
import com.rawsmusic.module.data.prefs.LyricFontManager
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun GlobalFontSettingsScreen(
    onBack: () -> Unit,
    onApply: () -> Unit,
    onImportFont: () -> Unit,
) {
    var fontWeight by remember { mutableStateOf(AppPreferences.UI.fontWeight) }
    var fontSizeScale by remember { mutableStateOf(AppPreferences.UI.fontSizeScale) }
    var fontItalic by remember { mutableStateOf(AppPreferences.UI.fontItalic) }
    var letterSpacing by remember { mutableStateOf(AppPreferences.UI.fontLetterSpacingEm) }
    var selectedFontPath by remember { mutableStateOf(AppPreferences.UI.customFontPath) }
    var systemFonts by remember { mutableStateOf<List<LyricFontManager.FontInfo>>(emptyList()) }
    var importedFonts by remember { mutableStateOf<List<LyricFontManager.FontInfo>>(emptyList()) }
    var showFontCatalog by remember { mutableStateOf(false) }
    val fontRevision by LyricFontManager.revision.collectAsState()
    val context = LocalContext.current

    fun applyFontSettings() {
        FontManager.rebuildTypeface(context)
        FontManager.clearScaledCache()
        RawThemeRuntimeState.invalidate()
        onApply()
    }

    LaunchedEffect(Unit) {
        systemFonts = withContext(Dispatchers.IO) { FontManager.getSystemFonts() }
    }
    LaunchedEffect(fontRevision) {
        importedFonts = withContext(Dispatchers.IO) { FontManager.getImportedFonts(context) }
        selectedFontPath = AppPreferences.UI.customFontPath
    }

    val previewFontFamily = remember(selectedFontPath, fontWeight, fontRevision) {
        if (selectedFontPath.isBlank()) FontFamily.Default
        else runCatching {
            // Keep the concrete source face at Normal. Relabelling the same file as the
            // requested slider weight makes Compose treat it as an exact match, so 400/800
            // resolve to the same glyph outlines and the weight control appears ineffective.
            FontFamily(Font(File(selectedFontPath), FontWeight.Normal))
        }.getOrDefault(FontFamily.Default)
    }
    val previewFontStyle = if (fontItalic) FontStyle.Italic else FontStyle.Normal

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
                        fontStyle = previewFontStyle,
                        letterSpacing = letterSpacing.em,
                        color = MiuixTheme.colorScheme.onBackground,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.settings_global_font_preview_alphabet),
                        fontSize = (14 * fontSizeScale / 100f).sp,
                        fontFamily = previewFontFamily,
                        fontWeight = FontWeight(fontWeight),
                        fontStyle = previewFontStyle,
                        letterSpacing = letterSpacing.em,
                        color = MiuixTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        stringResource(R.string.settings_global_font_preview_mixed),
                        fontSize = (14 * fontSizeScale / 100f).sp,
                        fontFamily = previewFontFamily,
                        fontWeight = FontWeight(fontWeight),
                        fontStyle = previewFontStyle,
                        letterSpacing = letterSpacing.em,
                        color = MiuixTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        SettingsCard {
            SettingsNavigationEntry(
                title = stringResource(R.string.settings_font_family),
                description = selectedFontPath
                    .takeIf(String::isNotBlank)
                    ?.let { File(it).nameWithoutExtension }
                    ?: stringResource(R.string.settings_default_system_font),
                onClick = { showFontCatalog = true },
            )
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
                fontWeight < 800 -> "Bold"
                else -> "Black"
            }
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
                valueRange = 100f..900f,
                steps = 15,
                hapticEffect = SliderDefaults.SliderHapticEffect.Step,
            )
        }

        Spacer(Modifier.height(12.dp))

        SettingsCard {
            SectionHeader(stringResource(R.string.settings_font_size))
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
                valueRange = 70f..160f,
                steps = 17,
                hapticEffect = SliderDefaults.SliderHapticEffect.Step,
            )
        }

        Spacer(Modifier.height(12.dp))

        SettingsCard {
            SectionHeader(stringResource(R.string.settings_font_letter_spacing))
            SliderPreference(
                title = stringResource(R.string.settings_font_letter_spacing),
                summary = null,
                valueText = String.format("%+.2f em", letterSpacing),
                value = letterSpacing,
                onValueChange = {
                    letterSpacing = it
                    AppPreferences.UI.fontLetterSpacingEm = it
                    applyFontSettings()
                },
                valueRange = -0.05f..0.20f,
                steps = 24,
                hapticEffect = SliderDefaults.SliderHapticEffect.Step,
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
                },
            )
        }
    }

    if (showFontCatalog) {
        FontCatalogDialog(
            title = stringResource(R.string.settings_font_family),
            selectedPath = selectedFontPath,
            systemFonts = systemFonts,
            importedFonts = importedFonts,
            onDismiss = { showFontCatalog = false },
            onSelect = { font ->
                selectedFontPath = font?.path.orEmpty()
                FontManager.selectFont(context, font)
                applyFontSettings()
            },
            onImport = onImportFont,
        )
    }
}
