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
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.preference.SwitchPreference
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import com.rawsmusic.core.ui.widget.flow.RawBackgroundStyle
import com.rawsmusic.core.ui.widget.flow.RawFlowTuningState
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.text.style.TextAlign
import com.rawsmusic.core.ui.widget.text.LongTextMotionState
import com.rawsmusic.locale.AppLocaleManager
import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.rawsmusic.core.ui.widget.background.CustomMediaBackgroundState
import com.rawsmusic.module.data.prefs.PersonalizationPreferences
import com.rawsmusic.module.data.prefs.BottomBarMaterial
import com.rawsmusic.module.data.prefs.LibraryBottomButtonsSurface
import com.rawsmusic.module.data.prefs.SettingsSurfaceStyle

@Composable
fun LiquidGlassAppearanceScreen(
    onBack: () -> Unit
) {
    
    val fontFamily = appFontFamily()
    val context = LocalContext.current
    RawFlowTuningState.ensureInitialized(context)
    CustomMediaBackgroundState.ensureInitialized(context)
    @Suppress("UNUSED_VARIABLE")
    val customBackgroundRevision = CustomMediaBackgroundState.revision
    val customBackgroundPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            CustomMediaBackgroundState.setSource(
                context = context,
                uri = uri,
                mimeType = context.contentResolver.getType(uri),
            )
        }
    }

    val themeRuntimeVersion = RawThemeRuntimeState.version
    var currentTheme by remember(themeRuntimeVersion) { mutableStateOf(ThemeManager.getCurrentTheme()) }
    var animateLongLabels by remember { mutableStateOf(LongTextMotionState.enabled) }
    var animateLongLabelsEverywhere by remember {
        mutableStateOf(LongTextMotionState.enabledEverywhere)
    }
    var applicationLanguage by remember {
        mutableStateOf(AppLocaleManager.currentLanguage(context))
    }
    val globalGlass by PersonalizationPreferences.globalLiquidGlassSettings.collectAsState()
    val settingsSurfaceStyle by PersonalizationPreferences.settingsSurfaceStyle.collectAsState()
    val noticeSurfaceStyle by PersonalizationPreferences.noticeSurfaceStyle.collectAsState()
    val bottomBarMaterial by PersonalizationPreferences.bottomBarMaterial.collectAsState()
    val mainButtonsSurface by PersonalizationPreferences.libraryBottomButtonsSurface.collectAsState()
    val playbackMaterial = bottomBarMaterial.asSurfaceStyle()
    val mainButtonsMaterial = mainButtonsSurface.asSurfaceStyle()

    SettingsPage(title = stringResource(R.string.settings_appearance_title), onBack = onBack) {
        SettingsCard {
            SectionHeader(stringResource(R.string.settings_language_title))
            Text(
                text = stringResource(R.string.settings_language_summary),
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontFamily = fontFamily
            )
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    AppLocaleManager.SYSTEM to stringResource(R.string.settings_language_system),
                    AppLocaleManager.SIMPLIFIED_CHINESE to stringResource(R.string.settings_language_chinese),
                    AppLocaleManager.ENGLISH to stringResource(R.string.settings_language_english)
                ).forEach { (language, label) ->
                    val selected = applicationLanguage == language
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (selected) MiuixTheme.colorScheme.primary
                                else MiuixTheme.colorScheme.surfaceContainer
                            )
                            .clickable {
                                if (applicationLanguage != language) {
                                    applicationLanguage = language
                                    AppLocaleManager.setLanguage(context, language)
                                    (context as? Activity)?.recreate()
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            color = if (selected) MiuixTheme.colorScheme.onPrimary
                            else MiuixTheme.colorScheme.onBackgroundVariant,
                            fontSize = 14.sp,
                            fontFamily = fontFamily
                        )
                    }
                }
            }
        }

        SettingsCard {
            SectionHeader(stringResource(R.string.settings_long_text_title))
            SwitchPreference(
                title = stringResource(R.string.settings_long_text_animate),
                summary = stringResource(R.string.settings_long_text_animate_summary),
                checked = animateLongLabels,
                onCheckedChange = { checked ->
                    animateLongLabels = checked
                    LongTextMotionState.updateEnabled(checked)
                    if (!checked) {
                        animateLongLabelsEverywhere = false
                        LongTextMotionState.updateEnabledEverywhere(false)
                    }
                }
            )
            SwitchPreference(
                title = stringResource(R.string.settings_long_text_everywhere),
                summary = stringResource(R.string.settings_long_text_everywhere_summary),
                checked = animateLongLabelsEverywhere,
                enabled = animateLongLabels,
                onCheckedChange = { checked ->
                    animateLongLabelsEverywhere = checked
                    LongTextMotionState.updateEnabledEverywhere(checked)
                }
            )
        }

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

        SettingsCard {
            SectionHeader(stringResource(R.string.settings_surface_style_title))
            Text(
                text = stringResource(R.string.settings_material_assignment_summary),
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
                fontFamily = fontFamily,
            )
            val assignments = mapOf(
                MaterialTarget.PLAYBACK_BAR to playbackMaterial,
                MaterialTarget.MAIN_BUTTONS to mainButtonsMaterial,
                MaterialTarget.SETTINGS_PAGE to settingsSurfaceStyle,
                MaterialTarget.POPUP_NOTICE to noticeSurfaceStyle,
            )
            SettingsSurfaceStyle.entries.forEach { material ->
                MaterialAssignmentRow(
                    material = material,
                    assignments = assignments,
                    onApplyAll = { applyMaterialToAll(material) },
                    onTargetClick = { target ->
                        // Material ownership is exclusive, but switching is direct: selecting a
                        // different row immediately transfers this target to the new material.
                        // Users never have to bounce through SOLID first.
                        applyMaterialTarget(target = target, material = material)
                    },
                )
                Spacer(Modifier.height(8.dp))
            }

            val anyTranslucentMaterial = assignments.values.any { it != SettingsSurfaceStyle.SOLID }
            val anyLiquidGlass = assignments.values.any { it == SettingsSurfaceStyle.LIQUID_GLASS }
            if (anyTranslucentMaterial) {
                FlowTuningSlider(
                    title = stringResource(R.string.settings_liquid_glass_blur),
                    value = globalGlass.blurRadiusDp,
                    valueRange = 0f..32f,
                    valueText = "${globalGlass.blurRadiusDp.toInt()}dp",
                    onValueChange = { value ->
                        PersonalizationPreferences.updateGlobalLiquidGlassSettings { it.copy(blurRadiusDp = value) }
                    },
                )
            }
            if (anyLiquidGlass) {
                FlowTuningSlider(
                    title = stringResource(R.string.settings_liquid_glass_refraction_height),
                    value = globalGlass.refractionHeightFraction,
                    valueRange = 0f..1f,
                    valueText = "${(globalGlass.refractionHeightFraction * 100f).toInt()}%",
                    onValueChange = { value ->
                        PersonalizationPreferences.updateGlobalLiquidGlassSettings { it.copy(refractionHeightFraction = value) }
                    },
                )
                FlowTuningSlider(
                    title = stringResource(R.string.settings_liquid_glass_refraction_amount),
                    value = globalGlass.refractionAmountFraction,
                    valueRange = 0f..1f,
                    valueText = "${(globalGlass.refractionAmountFraction * 100f).toInt()}%",
                    onValueChange = { value ->
                        PersonalizationPreferences.updateGlobalLiquidGlassSettings { it.copy(refractionAmountFraction = value) }
                    },
                )
                SwitchPreference(
                    title = stringResource(R.string.settings_liquid_glass_chromatic),
                    summary = stringResource(R.string.settings_liquid_glass_chromatic_summary),
                    checked = globalGlass.chromaticAberration > 0.001f,
                    onCheckedChange = { enabled ->
                        PersonalizationPreferences.updateGlobalLiquidGlassSettings {
                            it.copy(chromaticAberration = if (enabled) 1f else 0f)
                        }
                    },
                )
                FlowTuningSlider(
                    title = stringResource(R.string.settings_liquid_glass_vibrancy),
                    value = globalGlass.vibrancyStrength,
                    valueRange = 0f..2f,
                    valueText = String.format("%.2f×", globalGlass.vibrancyStrength),
                    onValueChange = { value ->
                        PersonalizationPreferences.updateGlobalLiquidGlassSettings { it.copy(vibrancyStrength = value) }
                    },
                )
                FlowTuningSlider(
                    title = stringResource(R.string.settings_liquid_glass_highlight),
                    value = globalGlass.highlightStrength,
                    valueRange = 0f..1.5f,
                    valueText = "${(globalGlass.highlightStrength * 100f).toInt()}%",
                    onValueChange = { value ->
                        PersonalizationPreferences.updateGlobalLiquidGlassSettings { it.copy(highlightStrength = value) }
                    },
                )
                FlowTuningSlider(
                    title = stringResource(R.string.settings_liquid_glass_shadow),
                    value = globalGlass.shadowStrength,
                    valueRange = 0f..1.5f,
                    valueText = "${(globalGlass.shadowStrength * 100f).toInt()}%",
                    onValueChange = { value ->
                        PersonalizationPreferences.updateGlobalLiquidGlassSettings { it.copy(shadowStrength = value) }
                    },
                )
                SettingsActionRow(
                    title = stringResource(R.string.settings_liquid_glass_reset),
                    description = stringResource(R.string.settings_liquid_glass_reset_summary),
                    onClick = { PersonalizationPreferences.resetGlobalLiquidGlassSettings() },
                )
            }
        }

        SettingsCard {
            SectionHeader(stringResource(R.string.settings_background_style))
            Text(
                stringResource(R.string.settings_background_style_desc),
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
                listOf(
                    RawBackgroundStyle.FLOW to stringResource(R.string.settings_background_style_flow),
                    RawBackgroundStyle.STATIC to stringResource(R.string.settings_background_style_static),
                    RawBackgroundStyle.SIMPLE to stringResource(R.string.settings_background_style_simple)
                ).forEach { (style, label) ->
                    val selected = RawFlowTuningState.style == style
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (selected) MiuixTheme.colorScheme.primary
                                else MiuixTheme.colorScheme.surfaceContainer
                            )
                            .clickable {
                                RawFlowTuningState.setStyle(context, style)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            color = if (selected) {
                                MiuixTheme.colorScheme.onPrimary
                            } else {
                                MiuixTheme.colorScheme.onBackgroundVariant
                            },
                            fontSize = 14.sp,
                            fontFamily = fontFamily
                        )
                    }
                }
            }

            FlowTuningSlider(
                title = stringResource(R.string.settings_background_speed),
                value = RawFlowTuningState.speed,
                valueRange = 0.5f..4f,
                valueText = stringResource(
                    R.string.settings_background_speed_value,
                    RawFlowTuningState.speed
                ),
                enabled = RawFlowTuningState.style == RawBackgroundStyle.FLOW,
                onValueChange = { RawFlowTuningState.setSpeed(context, it) }
            )
            FlowTuningSlider(
                title = stringResource(R.string.settings_background_saturation),
                value = RawFlowTuningState.saturation,
                valueRange = 0.5f..1.6f,
                valueText = "${(RawFlowTuningState.saturation * 100).toInt()}%",
                onValueChange = { RawFlowTuningState.setSaturation(context, it) }
            )
            FlowTuningSlider(
                title = stringResource(R.string.settings_background_brightness),
                value = RawFlowTuningState.brightness,
                valueRange = 0.65f..1.35f,
                valueText = "${(RawFlowTuningState.brightness * 100).toInt()}%",
                onValueChange = { RawFlowTuningState.setBrightness(context, it) }
            )
        }

        SettingsCard {
            SectionHeader(stringResource(R.string.settings_custom_background_title))
            Text(
                text = stringResource(R.string.settings_custom_background_summary),
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontFamily = fontFamily,
            )
            Spacer(Modifier.height(8.dp))
            SettingsActionRow(
                title = stringResource(
                    if (CustomMediaBackgroundState.sourceUri.isBlank()) {
                        R.string.settings_custom_background_select
                    } else {
                        R.string.settings_custom_background_replace
                    }
                ),
                description = CustomMediaBackgroundState.sourceUri
                    .takeIf { it.isNotBlank() }
                    ?.let { android.net.Uri.parse(it).lastPathSegment },
                onClick = { customBackgroundPicker.launch(arrayOf("image/*", "video/*")) },
            )
            SwitchPreference(
                title = stringResource(R.string.settings_custom_background_enabled),
                summary = stringResource(R.string.settings_custom_background_enabled_summary),
                checked = CustomMediaBackgroundState.enabled,
                enabled = CustomMediaBackgroundState.sourceUri.isNotBlank(),
                onCheckedChange = { CustomMediaBackgroundState.setEnabled(context, it) },
            )
            SwitchPreference(
                title = stringResource(R.string.settings_custom_background_player),
                summary = stringResource(R.string.settings_custom_background_player_summary),
                checked = CustomMediaBackgroundState.showOnPlayer,
                enabled = CustomMediaBackgroundState.enabled,
                onCheckedChange = { CustomMediaBackgroundState.setShowOnPlayer(context, it) },
            )
            SwitchPreference(
                title = stringResource(R.string.settings_custom_background_settings),
                summary = stringResource(R.string.settings_custom_background_settings_summary),
                checked = CustomMediaBackgroundState.showOnSettings,
                enabled = CustomMediaBackgroundState.enabled,
                onCheckedChange = { CustomMediaBackgroundState.setShowOnSettings(context, it) },
            )
            if (CustomMediaBackgroundState.sourceUri.isNotBlank()) {
                SettingsActionRow(
                    title = stringResource(R.string.settings_custom_background_clear),
                    onClick = { CustomMediaBackgroundState.clear(context) },
                )
            }
        }
    }
}

private enum class MaterialTarget {
    PLAYBACK_BAR,
    MAIN_BUTTONS,
    SETTINGS_PAGE,
    POPUP_NOTICE,
}

private fun BottomBarMaterial.asSurfaceStyle(): SettingsSurfaceStyle = when (this) {
    BottomBarMaterial.SOLID -> SettingsSurfaceStyle.SOLID
    BottomBarMaterial.ACRYLIC -> SettingsSurfaceStyle.ACRYLIC
    BottomBarMaterial.LIQUID_GLASS -> SettingsSurfaceStyle.LIQUID_GLASS
}

private fun LibraryBottomButtonsSurface.asSurfaceStyle(): SettingsSurfaceStyle = when (this) {
    LibraryBottomButtonsSurface.SOLID -> SettingsSurfaceStyle.SOLID
    LibraryBottomButtonsSurface.FROSTED -> SettingsSurfaceStyle.ACRYLIC
    LibraryBottomButtonsSurface.LIQUID_GLASS -> SettingsSurfaceStyle.LIQUID_GLASS
}

private fun SettingsSurfaceStyle.asBottomBarMaterial(): BottomBarMaterial = when (this) {
    SettingsSurfaceStyle.SOLID -> BottomBarMaterial.SOLID
    SettingsSurfaceStyle.ACRYLIC -> BottomBarMaterial.ACRYLIC
    SettingsSurfaceStyle.LIQUID_GLASS -> BottomBarMaterial.LIQUID_GLASS
}

private fun SettingsSurfaceStyle.asMainButtonsSurface(): LibraryBottomButtonsSurface = when (this) {
    SettingsSurfaceStyle.SOLID -> LibraryBottomButtonsSurface.SOLID
    SettingsSurfaceStyle.ACRYLIC -> LibraryBottomButtonsSurface.FROSTED
    SettingsSurfaceStyle.LIQUID_GLASS -> LibraryBottomButtonsSurface.LIQUID_GLASS
}

private fun applyMaterialTarget(target: MaterialTarget, material: SettingsSurfaceStyle) {
    when (target) {
        MaterialTarget.PLAYBACK_BAR ->
            PersonalizationPreferences.bottomBarMaterialValue = material.asBottomBarMaterial()
        MaterialTarget.MAIN_BUTTONS ->
            PersonalizationPreferences.libraryBottomButtonsSurfaceValue = material.asMainButtonsSurface()
        MaterialTarget.SETTINGS_PAGE ->
            PersonalizationPreferences.settingsSurfaceStyleValue = material
        MaterialTarget.POPUP_NOTICE ->
            PersonalizationPreferences.noticeSurfaceStyleValue = material
    }
}

private fun applyMaterialToAll(material: SettingsSurfaceStyle) {
    MaterialTarget.entries.forEach { applyMaterialTarget(it, material) }
}

@Composable
private fun MaterialAssignmentRow(
    material: SettingsSurfaceStyle,
    assignments: Map<MaterialTarget, SettingsSurfaceStyle>,
    onApplyAll: () -> Unit,
    onTargetClick: (MaterialTarget) -> Unit,
) {
    val materialName = when (material) {
        SettingsSurfaceStyle.SOLID -> stringResource(R.string.settings_surface_solid)
        SettingsSurfaceStyle.ACRYLIC -> stringResource(R.string.settings_surface_acrylic)
        SettingsSurfaceStyle.LIQUID_GLASS -> stringResource(R.string.settings_surface_liquid_glass)
    }
    val allSelected = MaterialTarget.entries.all { assignments[it] == material }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 0.72f))
            .padding(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = materialName,
                color = MiuixTheme.colorScheme.onBackground,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (allSelected) MiuixTheme.colorScheme.primary
                        else MiuixTheme.colorScheme.surfaceContainer,
                    )
                    .clickable(onClick = onApplyAll)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_material_apply_all),
                    color = if (allSelected) MiuixTheme.colorScheme.onPrimary
                    else MiuixTheme.colorScheme.onBackgroundVariant,
                    fontSize = 12.sp,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        MaterialTarget.entries.chunked(2).forEach { rowTargets ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                rowTargets.forEach { target ->
                    val current = assignments[target] ?: SettingsSurfaceStyle.SOLID
                    val selected = current == material
                    val label = when (target) {
                        MaterialTarget.PLAYBACK_BAR -> stringResource(R.string.settings_material_target_playback)
                        MaterialTarget.MAIN_BUTTONS -> stringResource(R.string.settings_material_target_main_buttons)
                        MaterialTarget.SETTINGS_PAGE -> stringResource(R.string.settings_material_target_settings)
                        MaterialTarget.POPUP_NOTICE -> stringResource(R.string.settings_material_target_notice)
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (selected) MiuixTheme.colorScheme.primary
                                else MiuixTheme.colorScheme.surfaceContainer,
                            )
                            .clickable { onTargetClick(target) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = label,
                            color = when {
                                selected -> MiuixTheme.colorScheme.onPrimary
                                else -> MiuixTheme.colorScheme.onBackgroundVariant
                            },
                            fontSize = 11.sp,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                if (rowTargets.size == 1) Spacer(Modifier.weight(1f))
            }
            if (rowTargets.last() != MaterialTarget.entries.last()) {
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun FlowTuningSlider(
    title: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    valueText: String,
    enabled: Boolean = true,
    onValueChange: (Float) -> Unit
) {
    val fontFamily = appFontFamily()
    Column(modifier = Modifier.padding(top = 14.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                color = MiuixTheme.colorScheme.onSurface,
                fontSize = 14.sp,
                fontFamily = fontFamily,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = valueText,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 12.sp,
                textAlign = TextAlign.End,
                fontFamily = fontFamily
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
