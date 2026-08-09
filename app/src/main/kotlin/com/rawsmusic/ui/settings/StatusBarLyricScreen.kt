package com.rawsmusic.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.rawsmusic.R
import com.rawsmusic.lyric.DesktopLyricService
import com.rawsmusic.module.data.prefs.AppPreferences
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ColorPicker
import top.yukonga.miuix.kmp.basic.ColorSpace
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.WindowSpinnerPreference
import kotlin.math.roundToInt

@Composable
fun LiquidGlassStatusBarLyricScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val lyricsPrefs = AppPreferences.Lyrics

    var desktopEnabled by remember { mutableStateOf(lyricsPrefs.desktopLyricEnabled) }
    var desktopLocked by remember { mutableStateOf(lyricsPrefs.desktopLyricLocked) }
    var desktopStatusBarMode by remember { mutableStateOf(lyricsPrefs.desktopLyricStatusBarMode) }
    var desktopHidePaused by remember { mutableStateOf(lyricsPrefs.desktopLyricHideWhenPaused) }
    var desktopHideLandscape by remember { mutableStateOf(lyricsPrefs.desktopLyricHideInLandscape) }
    var desktopTranslation by remember { mutableStateOf(lyricsPrefs.desktopLyricShowTranslation) }
    var desktopRomanization by remember { mutableStateOf(lyricsPrefs.desktopLyricShowRomanization) }
    var desktopBackground by remember { mutableStateOf(lyricsPrefs.desktopLyricShowBackground) }
    var desktopUseLyricFont by remember { mutableStateOf(lyricsPrefs.desktopLyricUseLyricFont) }
    var desktopWidth by remember { mutableFloatStateOf(lyricsPrefs.desktopLyricWidthPercent.toFloat()) }
    var desktopFontScale by remember { mutableFloatStateOf(lyricsPrefs.desktopLyricFontScale.toFloat()) }
    var desktopSecondaryScale by remember { mutableFloatStateOf(lyricsPrefs.desktopLyricSecondaryScale.toFloat()) }
    var desktopOpacity by remember { mutableFloatStateOf(lyricsPrefs.desktopLyricOpacity.toFloat()) }
    var statusHidePaused by remember { mutableStateOf(lyricsPrefs.desktopLyricStatusHideWhenPaused) }
    var statusHideLandscape by remember { mutableStateOf(lyricsPrefs.desktopLyricStatusHideInLandscape) }
    var statusTopOffset by remember { mutableFloatStateOf(lyricsPrefs.desktopLyricStatusTopOffset.toFloat()) }
    var statusPosition by remember { mutableStateOf(lyricsPrefs.desktopLyricStatusPosition) }
    var statusWidth by remember { mutableFloatStateOf(lyricsPrefs.desktopLyricStatusWidthPercent.toFloat()) }
    var statusXOffset by remember { mutableFloatStateOf(lyricsPrefs.desktopLyricStatusXOffset.toFloat()) }
    var statusTextAlign by remember { mutableStateOf(lyricsPrefs.desktopLyricStatusTextAlign) }
    var statusVerticalAlign by remember { mutableStateOf(lyricsPrefs.desktopLyricStatusVerticalAlign) }
    var statusSecondary by remember { mutableStateOf(lyricsPrefs.desktopLyricStatusSecondary) }
    var statusSecondaryOpacity by remember {
        mutableFloatStateOf(lyricsPrefs.desktopLyricStatusSecondaryOpacity.toFloat())
    }
    var statusMergeSecondary by remember { mutableStateOf(lyricsPrefs.desktopLyricStatusMergeSecondary) }
    var statusFontScale by remember {
        mutableFloatStateOf(lyricsPrefs.desktopLyricStatusFontScale.toFloat())
    }
    var statusSecondaryScale by remember {
        mutableFloatStateOf(lyricsPrefs.desktopLyricStatusSecondaryScale.toFloat())
    }
    var statusOpacity by remember {
        mutableFloatStateOf(lyricsPrefs.desktopLyricStatusOpacity.toFloat())
    }
    var desktopTextColor by remember { mutableStateOf(lyricsPrefs.desktopLyricTextColor) }
    var statusTextColor by remember { mutableStateOf(lyricsPrefs.desktopLyricStatusTextColor) }
    var showCustomColor by remember { mutableStateOf(false) }
    var customColor by remember { mutableStateOf(Color(desktopTextColor)) }

    var tickerEnabled by remember { mutableStateOf(lyricsPrefs.tickerEnabled) }
    var tickerHideNotification by remember { mutableStateOf(lyricsPrefs.tickerHideNotification) }
    var tickerHeadsUpLyrics by remember { mutableStateOf(lyricsPrefs.tickerHeadsUpLyrics) }
    var samsungFloatingLyricTranslation by remember { mutableStateOf(lyricsPrefs.samsungFloatingLyricTranslation) }
    var lyricGetterEnabled by remember { mutableStateOf(lyricsPrefs.lyricGetterEnabled) }
    var bluetoothLyricEnabled by remember { mutableStateOf(lyricsPrefs.bluetoothLyricEnabled) }
    var bluetoothLyricTranslation by remember { mutableStateOf(lyricsPrefs.bluetoothLyricTranslation) }

    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val granted = DesktopLyricService.canDraw(context)
        desktopEnabled = granted
        lyricsPrefs.desktopLyricEnabled = granted
        DesktopLyricService.sync(context)
    }

    fun applyDesktopSettings() {
        DesktopLyricService.applySettings(context)
    }

    val horizontalLabels = listOf(
        stringResource(R.string.desktop_lyric_left),
        stringResource(R.string.desktop_lyric_center),
        stringResource(R.string.desktop_lyric_right)
    )
    val verticalLabels = listOf(
        stringResource(R.string.desktop_lyric_top),
        stringResource(R.string.desktop_lyric_center),
        stringResource(R.string.desktop_lyric_bottom)
    )
    val secondaryLabels = listOf(
        stringResource(R.string.desktop_lyric_secondary_off),
        stringResource(R.string.desktop_lyric_translation),
        stringResource(R.string.desktop_lyric_romanization)
    )
    val colorValues = listOf(
        0xFFFFFFFF.toInt(),
        0xFFBFBFBF.toInt(),
        0xFF91CDFF.toInt(),
        0xFFA6EBCB.toInt(),
        0xFFFFBCD6.toInt(),
        0xFFB388FF.toInt(),
        0xFFFFE096.toInt()
    )
    val colorLabels = listOf(
        stringResource(R.string.desktop_lyric_color_white),
        stringResource(R.string.desktop_lyric_color_silver),
        stringResource(R.string.desktop_lyric_color_blue),
        stringResource(R.string.desktop_lyric_color_mint),
        stringResource(R.string.desktop_lyric_color_pink),
        stringResource(R.string.desktop_lyric_color_purple),
        stringResource(R.string.desktop_lyric_color_yellow)
    )
    val horizontalEntries = horizontalLabels.map { DropdownItem(title = it) }
    val verticalEntries = verticalLabels.map { DropdownItem(title = it) }
    val secondaryEntries = secondaryLabels.map { DropdownItem(title = it) }
    val colorEntries = colorLabels.map { DropdownItem(title = it) }

    SettingsPage(title = stringResource(R.string.settings_status_bar_lyric_title), onBack = onBack) {
        SettingsSection(stringResource(R.string.desktop_lyric_section)) {
            SettingsInfoEntry(
                title = stringResource(R.string.desktop_lyric_title),
                description = stringResource(R.string.desktop_lyric_description)
            )
            SwitchRow(stringResource(R.string.desktop_lyric_enable), desktopEnabled) { checked ->
                if (checked && !DesktopLyricService.canDraw(context)) {
                    overlayPermissionLauncher.launch(DesktopLyricService.permissionIntent(context))
                } else {
                    desktopEnabled = checked
                    lyricsPrefs.desktopLyricEnabled = checked
                    DesktopLyricService.sync(context)
                }
            }
            SwitchRow(
                stringResource(R.string.desktop_lyric_status_bar_mode),
                desktopStatusBarMode,
                enabled = desktopEnabled
            ) {
                desktopStatusBarMode = it
                lyricsPrefs.desktopLyricStatusBarMode = it
                applyDesktopSettings()
            }
            if (desktopStatusBarMode) {
            SwitchRow(
                stringResource(R.string.desktop_lyric_status_hide_paused),
                statusHidePaused,
                enabled = desktopEnabled && desktopStatusBarMode
            ) {
                statusHidePaused = it
                lyricsPrefs.desktopLyricStatusHideWhenPaused = it
                applyDesktopSettings()
            }
            SwitchRow(
                stringResource(R.string.desktop_lyric_status_hide_landscape),
                statusHideLandscape,
                enabled = desktopEnabled && desktopStatusBarMode
            ) {
                statusHideLandscape = it
                lyricsPrefs.desktopLyricStatusHideInLandscape = it
                applyDesktopSettings()
            }
            DesktopSliderRow(
                label = stringResource(R.string.desktop_lyric_status_top_offset),
                value = statusTopOffset,
                valueRange = 0f..120f,
                valueText = "${statusTopOffset.roundToInt()}dp",
                enabled = desktopEnabled && desktopStatusBarMode,
                onValueChange = {
                    statusTopOffset = it
                    lyricsPrefs.desktopLyricStatusTopOffset = it.roundToInt()
                    applyDesktopSettings()
                }
            )
            WindowSpinnerPreference(
                title = stringResource(R.string.desktop_lyric_status_position),
                summary = horizontalLabels[statusPosition],
                items = horizontalEntries,
                selectedIndex = statusPosition,
                enabled = desktopEnabled && desktopStatusBarMode,
                onSelectedIndexChange = {
                    statusPosition = it
                    lyricsPrefs.desktopLyricStatusPosition = it
                    applyDesktopSettings()
                }
            )
            DesktopSliderRow(
                label = stringResource(R.string.desktop_lyric_status_width),
                value = statusWidth,
                valueRange = 40f..100f,
                valueText = "${statusWidth.roundToInt()}%",
                enabled = desktopEnabled && desktopStatusBarMode,
                onValueChange = {
                    statusWidth = it
                    lyricsPrefs.desktopLyricStatusWidthPercent = it.roundToInt()
                    applyDesktopSettings()
                }
            )
            DesktopSliderRow(
                label = stringResource(R.string.desktop_lyric_status_x_offset),
                value = statusXOffset,
                valueRange = -640f..640f,
                valueText = "${statusXOffset.roundToInt()}dp",
                enabled = desktopEnabled && desktopStatusBarMode,
                onValueChange = {
                    statusXOffset = it
                    lyricsPrefs.desktopLyricStatusXOffset = it.roundToInt()
                    applyDesktopSettings()
                }
            )
            WindowSpinnerPreference(
                title = stringResource(R.string.desktop_lyric_text_alignment),
                summary = horizontalLabels[statusTextAlign],
                items = horizontalEntries,
                selectedIndex = statusTextAlign,
                enabled = desktopEnabled && desktopStatusBarMode,
                onSelectedIndexChange = {
                    statusTextAlign = it
                    lyricsPrefs.desktopLyricStatusTextAlign = it
                    applyDesktopSettings()
                }
            )
            WindowSpinnerPreference(
                title = stringResource(R.string.desktop_lyric_vertical_alignment),
                summary = verticalLabels[statusVerticalAlign],
                items = verticalEntries,
                selectedIndex = statusVerticalAlign,
                enabled = desktopEnabled && desktopStatusBarMode,
                onSelectedIndexChange = {
                    statusVerticalAlign = it
                    lyricsPrefs.desktopLyricStatusVerticalAlign = it
                    applyDesktopSettings()
                }
            )
            WindowSpinnerPreference(
                title = stringResource(R.string.desktop_lyric_status_secondary),
                summary = secondaryLabels[statusSecondary],
                items = secondaryEntries,
                selectedIndex = statusSecondary,
                enabled = desktopEnabled && desktopStatusBarMode,
                onSelectedIndexChange = {
                    statusSecondary = it
                    lyricsPrefs.desktopLyricStatusSecondary = it
                    applyDesktopSettings()
                }
            )
            DesktopSliderRow(
                label = stringResource(R.string.desktop_lyric_status_secondary_opacity),
                value = statusSecondaryOpacity,
                valueRange = 20f..100f,
                valueText = "${statusSecondaryOpacity.roundToInt()}%",
                enabled = desktopEnabled && desktopStatusBarMode && statusSecondary != 0,
                onValueChange = {
                    statusSecondaryOpacity = it
                    lyricsPrefs.desktopLyricStatusSecondaryOpacity = it.roundToInt()
                    applyDesktopSettings()
                }
            )
            SwitchRow(
                stringResource(R.string.desktop_lyric_status_merge_secondary),
                statusMergeSecondary,
                enabled = desktopEnabled && desktopStatusBarMode && statusSecondary != 0
            ) {
                statusMergeSecondary = it
                lyricsPrefs.desktopLyricStatusMergeSecondary = it
                applyDesktopSettings()
            }
            }
            if (!desktopStatusBarMode) {
            SwitchRow(
                stringResource(R.string.desktop_lyric_lock),
                desktopLocked,
                enabled = desktopEnabled && !desktopStatusBarMode
            ) {
                desktopLocked = it
                lyricsPrefs.desktopLyricLocked = it
                applyDesktopSettings()
            }
            SwitchRow(
                stringResource(R.string.desktop_lyric_hide_paused),
                desktopHidePaused,
                enabled = desktopEnabled && !desktopStatusBarMode
            ) {
                desktopHidePaused = it
                lyricsPrefs.desktopLyricHideWhenPaused = it
                applyDesktopSettings()
            }
            SwitchRow(
                stringResource(R.string.desktop_lyric_hide_landscape),
                desktopHideLandscape,
                enabled = desktopEnabled && !desktopStatusBarMode
            ) {
                desktopHideLandscape = it
                lyricsPrefs.desktopLyricHideInLandscape = it
                applyDesktopSettings()
            }
            DesktopSliderRow(
                label = stringResource(R.string.desktop_lyric_width),
                value = desktopWidth,
                valueRange = 40f..100f,
                valueText = "${desktopWidth.roundToInt()}%",
                enabled = desktopEnabled,
                onValueChange = {
                    desktopWidth = it
                    lyricsPrefs.desktopLyricWidthPercent = it.roundToInt()
                    applyDesktopSettings()
                }
            )
            }
            SwitchRow(
                stringResource(R.string.desktop_lyric_translation),
                desktopTranslation,
                enabled = desktopEnabled
            ) {
                desktopTranslation = it
                lyricsPrefs.desktopLyricShowTranslation = it
                applyDesktopSettings()
            }
            SwitchRow(
                stringResource(R.string.desktop_lyric_romanization),
                desktopRomanization,
                enabled = desktopEnabled
            ) {
                desktopRomanization = it
                lyricsPrefs.desktopLyricShowRomanization = it
                applyDesktopSettings()
            }
            SwitchRow(
                stringResource(R.string.desktop_lyric_background_vocal),
                desktopBackground,
                enabled = desktopEnabled
            ) {
                desktopBackground = it
                lyricsPrefs.desktopLyricShowBackground = it
                applyDesktopSettings()
            }
            SwitchRow(
                stringResource(R.string.desktop_lyric_use_lyric_font),
                desktopUseLyricFont,
                enabled = desktopEnabled
            ) {
                desktopUseLyricFont = it
                lyricsPrefs.desktopLyricUseLyricFont = it
                applyDesktopSettings()
            }
            val activeColor = if (desktopStatusBarMode) statusTextColor else desktopTextColor
            val selectedColor = colorValues.indexOf(activeColor).takeIf { it >= 0 } ?: 0
            WindowSpinnerPreference(
                title = stringResource(R.string.desktop_lyric_color),
                summary = colorLabels[selectedColor],
                items = colorEntries,
                selectedIndex = selectedColor,
                enabled = desktopEnabled,
                onSelectedIndexChange = { index ->
                    val color = colorValues[index]
                    if (desktopStatusBarMode) {
                        statusTextColor = color
                        lyricsPrefs.desktopLyricStatusTextColor = color
                    } else {
                        desktopTextColor = color
                        lyricsPrefs.desktopLyricTextColor = color
                    }
                    applyDesktopSettings()
                }
            )
            SettingsNavigationEntry(
                title = stringResource(R.string.desktop_lyric_custom_color),
                description = String.format("#%06X", 0xFFFFFF and activeColor)
            ) {
                customColor = Color(activeColor)
                showCustomColor = true
            }
            DesktopSliderRow(
                label = stringResource(R.string.desktop_lyric_font_size),
                value = if (desktopStatusBarMode) statusFontScale else desktopFontScale,
                valueRange = 80f..220f,
                valueText = "${
                    (if (desktopStatusBarMode) statusFontScale else desktopFontScale).roundToInt()
                }%",
                enabled = desktopEnabled,
                onValueChange = {
                    if (desktopStatusBarMode) {
                        statusFontScale = it
                        lyricsPrefs.desktopLyricStatusFontScale = it.roundToInt()
                    } else {
                        desktopFontScale = it
                        lyricsPrefs.desktopLyricFontScale = it.roundToInt()
                    }
                    applyDesktopSettings()
                }
            )
            DesktopSliderRow(
                label = stringResource(R.string.desktop_lyric_secondary_size),
                value = if (desktopStatusBarMode) statusSecondaryScale else desktopSecondaryScale,
                valueRange = 70f..180f,
                valueText = "${
                    (if (desktopStatusBarMode) statusSecondaryScale else desktopSecondaryScale)
                        .roundToInt()
                }%",
                enabled = desktopEnabled,
                onValueChange = {
                    if (desktopStatusBarMode) {
                        statusSecondaryScale = it
                        lyricsPrefs.desktopLyricStatusSecondaryScale = it.roundToInt()
                    } else {
                        desktopSecondaryScale = it
                        lyricsPrefs.desktopLyricSecondaryScale = it.roundToInt()
                    }
                    applyDesktopSettings()
                }
            )
            DesktopSliderRow(
                label = stringResource(R.string.desktop_lyric_opacity),
                value = if (desktopStatusBarMode) statusOpacity else desktopOpacity,
                valueRange = 35f..100f,
                valueText = "${
                    (if (desktopStatusBarMode) statusOpacity else desktopOpacity).roundToInt()
                }%",
                enabled = desktopEnabled,
                onValueChange = {
                    if (desktopStatusBarMode) {
                        statusOpacity = it
                        lyricsPrefs.desktopLyricStatusOpacity = it.roundToInt()
                    } else {
                        desktopOpacity = it
                        lyricsPrefs.desktopLyricOpacity = it.roundToInt()
                    }
                    applyDesktopSettings()
                }
            )
            SettingsNavigationEntry(
                title = stringResource(R.string.desktop_lyric_reset_position),
                description = stringResource(R.string.desktop_lyric_reset_position_desc)
            ) {
                lyricsPrefs.desktopLyricX = Int.MIN_VALUE
                lyricsPrefs.desktopLyricY = Int.MIN_VALUE
                DesktopLyricService.resetPosition(context)
            }
        }

        SettingsSection("Flyme") {
            SettingsInfoEntry(
                title = stringResource(R.string.settings_status_bar_lyric_title),
                description = stringResource(R.string.settings_status_bar_flyme_desc)
            )
            SwitchRow(stringResource(R.string.settings_flyme_status_bar_lyric), tickerEnabled) { checked ->
                tickerEnabled = checked
                lyricsPrefs.tickerEnabled = checked
            }
            SwitchRow(stringResource(R.string.settings_hide_standalone_lyric_notification), tickerHideNotification, enabled = tickerEnabled) { checked ->
                tickerHideNotification = checked
                lyricsPrefs.tickerHideNotification = checked
            }
            SwitchRow(stringResource(R.string.settings_heads_up_lyric), tickerHeadsUpLyrics, enabled = tickerEnabled) { checked ->
                tickerHeadsUpLyrics = checked
                lyricsPrefs.tickerHeadsUpLyrics = checked
            }
        }

        SettingsSection(stringResource(R.string.settings_samsung)) {
            SwitchRow(stringResource(R.string.settings_samsung_floating_lyric_translation), samsungFloatingLyricTranslation, enabled = tickerEnabled) { checked ->
                samsungFloatingLyricTranslation = checked
                lyricsPrefs.samsungFloatingLyricTranslation = checked
            }
        }

        SettingsSection("Lyric Getter") {
            SettingsInfoEntry(
                title = stringResource(R.string.settings_external_lyrics),
                description = stringResource(R.string.settings_external_lyrics_desc)
            )
            SwitchRow(stringResource(R.string.settings_lyric_getter_lyrics), lyricGetterEnabled) { checked ->
                lyricGetterEnabled = checked
                lyricsPrefs.lyricGetterEnabled = checked
            }
        }

        SettingsSection(stringResource(R.string.settings_bluetooth)) {
            SettingsInfoEntry(
                title = stringResource(R.string.settings_car_lyrics),
                description = stringResource(R.string.settings_car_lyrics_desc)
            )
            SwitchRow(stringResource(R.string.settings_bluetooth_car_lyrics), bluetoothLyricEnabled) { checked ->
                bluetoothLyricEnabled = checked
                lyricsPrefs.bluetoothLyricEnabled = checked
            }
            SwitchRow(stringResource(R.string.settings_car_lyrics_translation), bluetoothLyricTranslation, enabled = bluetoothLyricEnabled) { checked ->
                bluetoothLyricTranslation = checked
                lyricsPrefs.bluetoothLyricTranslation = checked
            }
        }
    }

    if (showCustomColor) {
        Dialog(onDismissRequest = { showCustomColor = false }) {
            SettingsCard {
                Text(text = stringResource(R.string.desktop_lyric_custom_color))
                Spacer(modifier = Modifier.height(12.dp))
                ColorPicker(
                    color = customColor,
                    onColorChanged = { customColor = it },
                    colorSpace = ColorSpace.HSV,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(14.dp))
                Button(
                    onClick = {
                        val color = customColor.toArgb()
                        if (desktopStatusBarMode) {
                            statusTextColor = color
                            lyricsPrefs.desktopLyricStatusTextColor = color
                        } else {
                            desktopTextColor = color
                            lyricsPrefs.desktopLyricTextColor = color
                        }
                        applyDesktopSettings()
                        showCustomColor = false
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = stringResource(R.string.common_confirm))
                }
            }
        }
    }
}

@Composable
private fun DesktopSliderRow(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    valueText: String,
    enabled: Boolean,
    onValueChange: (Float) -> Unit
) {
    SliderPreference(
        title = label,
        summary = null,
        valueText = valueText,
        value = value,
        onValueChange = onValueChange,
        valueRange = valueRange,
        enabled = enabled
    )
}
