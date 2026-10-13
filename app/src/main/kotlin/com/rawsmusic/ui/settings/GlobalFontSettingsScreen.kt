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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.rawsmusic.R
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.core.ui.theme.RawThemeRuntimeState
import com.rawsmusic.core.ui.theme.FontScaleLayoutReporter
import com.rawsmusic.core.ui.theme.rawGlobalFontFamily
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
    SettingsPage(
        title = stringResource(R.string.settings_font_settings_title),
        onBack = onBack,
    ) {
        GlobalFontSettingsContent(
            onApply = onApply,
            onImportFont = onImportFont,
        )
    }
}

@Composable
internal fun GlobalFontSettingsContent(
    onApply: () -> Unit,
    onImportFont: () -> Unit,
) {
    var fontWeight by remember { mutableStateOf(AppPreferences.UI.fontWeight) }
    var experimentalWeight by remember {
        mutableStateOf(AppPreferences.UI.experimentalFontWeightEnabled)
    }
    var fontSizeScale by remember { mutableStateOf(AppPreferences.UI.fontSizeScale) }
    var fontItalic by remember { mutableStateOf(AppPreferences.UI.fontItalic) }
    var letterSpacing by remember { mutableStateOf(AppPreferences.UI.fontLetterSpacingEm) }
    var selectedFontPath by remember { mutableStateOf(AppPreferences.UI.customFontPath) }
    var systemFonts by remember { mutableStateOf<List<LyricFontManager.FontInfo>>(emptyList()) }
    var importedFonts by remember { mutableStateOf<List<LyricFontManager.FontInfo>>(emptyList()) }
    var showFontCatalog by remember { mutableStateOf(false) }
    val fontRevision by LyricFontManager.revision.collectAsState()
    val context = LocalContext.current
    val fontDensity = LocalDensity.current
    val previewLayoutReporter = remember {
        FontScaleLayoutReporter("settings_preview") { AppLogger.i("FontScale", it) }
    }

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
    val previewFontFamily = remember(selectedFontPath, fontWeight, experimentalWeight, fontRevision) {
        runCatching {
            rawGlobalFontFamily(
                path = selectedFontPath,
                globalWeight = fontWeight,
                allowOutOfRange = experimentalWeight,
            )
        }
            .getOrDefault(FontFamily.Default)
    }
    val previewFontStyle = if (fontItalic) FontStyle.Italic else FontStyle.Normal

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
                Column {
                    Text(
                        stringResource(R.string.settings_global_font_preview_title),
                        // GlobalFontScope already applies the selected percentage through
                        // LocalDensity; keeping the preview in base SP prevents double scaling.
                        fontSize = 22.sp,
                        fontFamily = previewFontFamily,
                        fontWeight = FontWeight.Normal,
                        fontStyle = previewFontStyle,
                        letterSpacing = letterSpacing.em,
                        color = MiuixTheme.colorScheme.onBackground,
                        onTextLayout = { layout ->
                            previewLayoutReporter.report(
                                fontSizeScale,
                                selectedFontPath.takeIf(String::isNotBlank)?.let { File(it).name }
                                    ?: "system_default",
                                fontDensity.fontScale,
                                with(fontDensity) { 22.sp.toPx() },
                                layout.size.width,
                                layout.size.height,
                            )
                        },
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.settings_global_font_preview_alphabet),
                        fontSize = 14.sp,
                        fontFamily = previewFontFamily,
                        fontWeight = FontWeight.Normal,
                        fontStyle = previewFontStyle,
                        letterSpacing = letterSpacing.em,
                        color = MiuixTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        stringResource(R.string.settings_global_font_preview_mixed),
                        fontSize = 14.sp,
                        fontFamily = previewFontFamily,
                        fontWeight = FontWeight.Normal,
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
            FontWeightControl(
                fontPath = selectedFontPath,
                weight = fontWeight,
                experimentalEnabled = experimentalWeight,
                revision = fontRevision,
                onWeightChange = { value ->
                    fontWeight = value
                    AppPreferences.UI.fontWeight = value
                    applyFontSettings()
                },
                onExperimentalChange = { enabled ->
                    experimentalWeight = enabled
                    AppPreferences.UI.experimentalFontWeightEnabled = enabled
                    applyFontSettings()
                },
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
                    AppLogger.i(
                        "FontScale",
                        "settings_write requested=${fontSizeScale}% persisted=${AppPreferences.UI.fontSizeScale}% " +
                            "font=${selectedFontPath.takeIf(String::isNotBlank)?.let { File(it).name } ?: "system_default"}",
                    )
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
                experimentalWeight = false
                AppPreferences.UI.experimentalFontWeightEnabled = false
                FontManager.selectFont(context, font)
                applyFontSettings()
            },
            onImport = onImportFont,
        )
    }
}
