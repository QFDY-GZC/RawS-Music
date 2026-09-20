package com.rawsmusic.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.rawsmusic.R
import com.rawsmusic.core.ui.widget.RawWindowDropdownPreference
import com.rawsmusic.core.ui.widget.player.AudioInfoCapsule
import com.rawsmusic.module.data.prefs.AudioInfoCapsuleBackgroundStyle
import com.rawsmusic.module.data.prefs.AudioInfoCapsuleContentStyle
import com.rawsmusic.module.data.prefs.AudioInfoCapsulePreferences
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ColorPicker
import top.yukonga.miuix.kmp.basic.ColorSpace
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.SliderPreference
import kotlin.math.roundToInt

@Composable
internal fun AudioInfoCapsuleSettingsSection() {
    val visible by AudioInfoCapsulePreferences.visible.collectAsState()
    val contentStyle by AudioInfoCapsulePreferences.contentStyle.collectAsState()
    val backgroundStyle by AudioInfoCapsulePreferences.backgroundStyle.collectAsState()
    val customArgb by AudioInfoCapsulePreferences.customColor.collectAsState()
    val customAlphaPercent by AudioInfoCapsulePreferences.customAlphaPercent.collectAsState()
    val popupBackgroundArgb by AudioInfoCapsulePreferences.popupBackgroundColor.collectAsState()
    var showCustomColor by remember { mutableStateOf(false) }
    var showPopupBackgroundColor by remember { mutableStateOf(false) }
    var editingColor by remember(customArgb) { mutableStateOf(Color(customArgb)) }
    var editingPopupBackgroundColor by remember(popupBackgroundArgb) { mutableStateOf(Color(popupBackgroundArgb)) }

    val cycleContentTitle = stringResource(R.string.settings_audio_info_capsule_content_cycle)
    val chainContentTitle = stringResource(R.string.settings_audio_info_capsule_content_playback_chain)
    val contentSummary = when (contentStyle) {
        AudioInfoCapsuleContentStyle.CYCLE -> cycleContentTitle
        AudioInfoCapsuleContentStyle.PLAYBACK_CHAIN -> chainContentTitle
    }
    val contentEntry = DropdownEntry(
        items = listOf(
            DropdownItem(
                text = cycleContentTitle,
                summary = stringResource(R.string.settings_audio_info_capsule_content_cycle_summary),
                selected = contentStyle == AudioInfoCapsuleContentStyle.CYCLE,
                onClick = { AudioInfoCapsulePreferences.contentStyleValue = AudioInfoCapsuleContentStyle.CYCLE },
            ),
            DropdownItem(
                text = chainContentTitle,
                summary = stringResource(R.string.settings_audio_info_capsule_content_playback_chain_summary),
                selected = contentStyle == AudioInfoCapsuleContentStyle.PLAYBACK_CHAIN,
                onClick = { AudioInfoCapsulePreferences.contentStyleValue = AudioInfoCapsuleContentStyle.PLAYBACK_CHAIN },
            ),
        )
    )

    val classicTitle = stringResource(R.string.settings_audio_info_capsule_style_classic)
    val transparentTitle = stringResource(R.string.settings_audio_info_capsule_style_transparent)
    val albumTitle = stringResource(R.string.settings_audio_info_capsule_style_album)
    val themeTitle = stringResource(R.string.settings_audio_info_capsule_style_theme)
    val customTitle = stringResource(R.string.settings_audio_info_capsule_style_custom)
    val styleSummary = when (backgroundStyle) {
        AudioInfoCapsuleBackgroundStyle.CLASSIC -> classicTitle
        AudioInfoCapsuleBackgroundStyle.TRANSPARENT -> transparentTitle
        AudioInfoCapsuleBackgroundStyle.ALBUM_COLOR -> albumTitle
        AudioInfoCapsuleBackgroundStyle.THEME_COLOR -> themeTitle
        AudioInfoCapsuleBackgroundStyle.CUSTOM_COLOR -> customTitle
    }
    val styleEntry = DropdownEntry(
        items = listOf(
            DropdownItem(
                text = classicTitle,
                summary = stringResource(R.string.settings_audio_info_capsule_style_classic_summary),
                selected = backgroundStyle == AudioInfoCapsuleBackgroundStyle.CLASSIC,
                onClick = { AudioInfoCapsulePreferences.backgroundStyleValue = AudioInfoCapsuleBackgroundStyle.CLASSIC },
            ),
            DropdownItem(
                text = transparentTitle,
                summary = stringResource(R.string.settings_audio_info_capsule_style_transparent_summary),
                selected = backgroundStyle == AudioInfoCapsuleBackgroundStyle.TRANSPARENT,
                onClick = { AudioInfoCapsulePreferences.backgroundStyleValue = AudioInfoCapsuleBackgroundStyle.TRANSPARENT },
            ),
            DropdownItem(
                text = albumTitle,
                summary = stringResource(R.string.settings_audio_info_capsule_style_album_summary),
                selected = backgroundStyle == AudioInfoCapsuleBackgroundStyle.ALBUM_COLOR,
                onClick = { AudioInfoCapsulePreferences.backgroundStyleValue = AudioInfoCapsuleBackgroundStyle.ALBUM_COLOR },
            ),
            DropdownItem(
                text = themeTitle,
                summary = stringResource(R.string.settings_audio_info_capsule_style_theme_summary),
                selected = backgroundStyle == AudioInfoCapsuleBackgroundStyle.THEME_COLOR,
                onClick = { AudioInfoCapsulePreferences.backgroundStyleValue = AudioInfoCapsuleBackgroundStyle.THEME_COLOR },
            ),
            DropdownItem(
                text = customTitle,
                summary = stringResource(R.string.settings_audio_info_capsule_style_custom_summary),
                selected = backgroundStyle == AudioInfoCapsuleBackgroundStyle.CUSTOM_COLOR,
                onClick = { AudioInfoCapsulePreferences.backgroundStyleValue = AudioInfoCapsuleBackgroundStyle.CUSTOM_COLOR },
            ),
        )
    )

    SettingsSection(stringResource(R.string.settings_audio_info_capsule_section)) {
        SwitchRow(
            label = stringResource(R.string.settings_audio_info_capsule_visible),
            checked = visible,
        ) { AudioInfoCapsulePreferences.isVisible = it }
        SettingsInfoEntry(
            title = stringResource(R.string.settings_audio_info_capsule_visible),
            description = stringResource(R.string.settings_audio_info_capsule_visible_summary),
        )

        if (visible) {
            RawWindowDropdownPreference(
                entry = contentEntry,
                title = stringResource(R.string.settings_audio_info_capsule_content_style),
                summary = contentSummary,
                showValue = true,
                maxHeight = 320.dp,
                collapseOnSelection = true,
            )
            SettingsInfoEntry(
                title = stringResource(R.string.settings_audio_info_capsule_content_style),
                description = stringResource(R.string.settings_audio_info_capsule_content_style_summary),
            )

            RawWindowDropdownPreference(
                entry = styleEntry,
                title = stringResource(R.string.settings_audio_info_capsule_background_style),
                summary = styleSummary,
                showValue = true,
                maxHeight = 420.dp,
                collapseOnSelection = true,
            )
            SettingsInfoEntry(
                title = stringResource(R.string.settings_audio_info_capsule_background_style),
                description = stringResource(R.string.settings_audio_info_capsule_background_style_summary),
            )

            if (backgroundStyle == AudioInfoCapsuleBackgroundStyle.CUSTOM_COLOR) {
                SettingsActionRow(
                    title = stringResource(R.string.settings_audio_info_capsule_custom_color),
                    description = stringResource(R.string.settings_audio_info_capsule_custom_color_summary),
                    onClick = {
                        editingColor = Color(customArgb)
                        showCustomColor = true
                    },
                )
                SliderPreference(
                    title = stringResource(R.string.settings_audio_info_capsule_custom_opacity),
                    summary = stringResource(R.string.settings_audio_info_capsule_custom_opacity_summary),
                    valueText = "$customAlphaPercent%",
                    value = customAlphaPercent.toFloat(),
                    valueRange = 0f..100f,
                    onValueChange = {
                        AudioInfoCapsulePreferences.customAlphaPercentValue = it.roundToInt()
                    },
                )
            }

            SettingsActionRow(
                title = stringResource(R.string.settings_audio_info_popup_background_color),
                description = stringResource(R.string.settings_audio_info_popup_background_color_summary),
                onClick = {
                    editingPopupBackgroundColor = Color(popupBackgroundArgb)
                    showPopupBackgroundColor = true
                },
            )

            SettingsCard {
                Text(text = stringResource(R.string.settings_audio_info_capsule_preview))
                Spacer(modifier = Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    AudioInfoCapsule(
                        song = null,
                        coverPath = null,
                        text = "24 BIT  44.1 KHZ  1008 KBPS  FLAC",
                        playbackChainText = "OPENSL:44.1 KHZ  FLAC:16 BIT  44.1 KHZ  1008 KBPS",
                        onClick = {},
                        onLongClick = {},
                    )
                }
            }
        }
    }

    if (showCustomColor) {
        Dialog(onDismissRequest = { showCustomColor = false }) {
            SettingsCard {
                Text(text = stringResource(R.string.settings_audio_info_capsule_custom_color))
                Spacer(modifier = Modifier.height(12.dp))
                ColorPicker(
                    color = editingColor,
                    onColorChanged = { editingColor = it },
                    colorSpace = ColorSpace.HSV,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(14.dp))
                Button(
                    onClick = {
                        AudioInfoCapsulePreferences.customColorArgb = editingColor.toArgb()
                        showCustomColor = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = stringResource(R.string.common_confirm))
                }
            }
        }
    }

    if (showPopupBackgroundColor) {
        Dialog(onDismissRequest = { showPopupBackgroundColor = false }) {
            SettingsCard {
                Text(text = stringResource(R.string.settings_audio_info_popup_background_color))
                Spacer(modifier = Modifier.height(12.dp))
                ColorPicker(
                    color = editingPopupBackgroundColor,
                    onColorChanged = { editingPopupBackgroundColor = it },
                    colorSpace = ColorSpace.HSV,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(14.dp))
                Button(
                    onClick = {
                        AudioInfoCapsulePreferences.popupBackgroundColorArgb = editingPopupBackgroundColor.toArgb()
                        showPopupBackgroundColor = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = stringResource(R.string.common_confirm))
                }
            }
        }
    }
}
