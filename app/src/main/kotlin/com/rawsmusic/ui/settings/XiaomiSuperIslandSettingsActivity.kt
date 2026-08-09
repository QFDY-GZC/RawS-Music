package com.rawsmusic.ui.settings

import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.res.stringResource
import com.rawsmusic.R
import com.rawsmusic.helper.LogExportHelper
import com.rawsmusic.module.data.prefs.AppPreferences.Lyrics
import com.rawsmusic.module.data.prefs.XiaomiSuperIslandSettings
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ColorPicker
import top.yukonga.miuix.kmp.basic.ColorSpace
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.WindowSpinnerPreference
import androidx.compose.ui.platform.LocalContext

class XiaomiSuperIslandSettingsActivity : BaseSettingsActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { XiaomiSuperIslandSettingsScreen(onBack = { finish() }) }
    }
}

@Composable
private fun XiaomiSuperIslandSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var settings by remember { mutableStateOf(Lyrics.xiaomiSuperIslandSettings) }
    var showCustomColor by remember { mutableStateOf(false) }
    var customColor by remember(settings.customColor) { mutableStateOf(Color(settings.customColor)) }
    fun update(next: XiaomiSuperIslandSettings) {
        settings = next.sanitized()
        Lyrics.xiaomiSuperIslandSettings = settings
    }

    val contentLabels = listOf(
        stringResource(R.string.settings_live_update_lyric_original),
        stringResource(R.string.settings_live_update_lyric_translation),
        stringResource(R.string.settings_live_update_lyric_pronunciation)
    )
    val modeLabels = listOf(
        stringResource(R.string.settings_xiaomi_super_island_mode_standard),
        stringResource(R.string.settings_xiaomi_super_island_mode_full)
    )
    val colorLabels = listOf(
        stringResource(R.string.settings_xiaomi_super_island_color_album),
        stringResource(R.string.settings_xiaomi_super_island_color_custom)
    )
    val actionLabels = listOf(
        stringResource(R.string.settings_xiaomi_super_island_actions_off),
        stringResource(R.string.settings_xiaomi_super_island_actions_media)
    )
    val notificationLabels = listOf(
        stringResource(R.string.settings_xiaomi_super_island_style_standard),
        stringResource(R.string.settings_xiaomi_super_island_style_advanced)
    )
    val buttonLabels = listOf(
        stringResource(R.string.settings_xiaomi_super_island_buttons_two),
        stringResource(R.string.settings_xiaomi_super_island_buttons_three)
    )
    val clickLabels = listOf(
        stringResource(R.string.settings_xiaomi_super_island_click_default),
        stringResource(R.string.settings_xiaomi_super_island_click_player),
        stringResource(R.string.settings_xiaomi_super_island_click_app)
    )
    val shareLabels = listOf(
        stringResource(R.string.settings_xiaomi_super_island_share_format_one),
        stringResource(R.string.settings_xiaomi_super_island_share_format_two),
        stringResource(R.string.settings_xiaomi_super_island_share_format_three)
    )
    val xmsfLabels = listOf(
        stringResource(R.string.settings_xiaomi_super_island_xmsf_disabled),
        stringResource(R.string.settings_xiaomi_super_island_xmsf_standard),
        stringResource(R.string.settings_xiaomi_super_island_xmsf_custom),
        stringResource(R.string.settings_xiaomi_super_island_xmsf_aggressive)
    )
    val xmsfSummaries = listOf(
        stringResource(R.string.settings_xiaomi_super_island_xmsf_disabled_summary),
        stringResource(R.string.settings_xiaomi_super_island_xmsf_standard_summary),
        stringResource(R.string.settings_xiaomi_super_island_xmsf_custom_summary),
        stringResource(R.string.settings_xiaomi_super_island_xmsf_aggressive_summary)
    )
    val dismissValues = XiaomiSuperIslandSettings.DISMISS_DELAYS_MS
    val dismissLabels = listOf(
        stringResource(R.string.settings_xiaomi_super_island_dismiss_immediate),
        stringResource(R.string.settings_xiaomi_super_island_dismiss_one),
        stringResource(R.string.settings_xiaomi_super_island_dismiss_three),
        stringResource(R.string.settings_xiaomi_super_island_dismiss_five)
    )

    SettingsPage(title = stringResource(R.string.settings_xiaomi_super_island_title), onBack = onBack) {
        SettingsSection(stringResource(R.string.settings_xiaomi_super_island_display_section)) {
            IslandSpinner(
                title = stringResource(R.string.settings_live_update_lyric_content),
                labels = contentLabels,
                selected = settings.lyricTextMode,
                onSelected = { update(settings.copy(lyricTextMode = it)) }
            )
            IslandSpinner(
                title = stringResource(R.string.settings_xiaomi_super_island_lyric_mode),
                labels = modeLabels,
                selected = settings.lyricMode,
                onSelected = { update(settings.copy(lyricMode = it)) }
            )
            if (settings.lyricMode == XiaomiSuperIslandSettings.LYRIC_MODE_FULL) {
                SwitchRow(
                    stringResource(R.string.settings_xiaomi_super_island_left_cover),
                    settings.fullLyricShowLeftCover
                ) { update(settings.copy(fullLyricShowLeftCover = it)) }
            }
            SwitchRow(
                stringResource(R.string.settings_xiaomi_super_island_scrolling),
                settings.scrollingEnabled,
                enabled = settings.lyricMode == XiaomiSuperIslandSettings.LYRIC_MODE_STANDARD
            ) { update(settings.copy(scrollingEnabled = it)) }
            IslandSlider(
                stringResource(R.string.settings_xiaomi_super_island_right_limit),
                settings.rightTextChars.toFloat(),
                6f..14f
            ) { update(settings.copy(rightTextChars = it.toInt())) }
            if (settings.lyricMode == XiaomiSuperIslandSettings.LYRIC_MODE_FULL) {
                val left = if (settings.fullLyricShowLeftCover) settings.leftWithCoverTextChars else settings.leftWithoutCoverTextChars
                val range = if (settings.fullLyricShowLeftCover) 4f..10f else 6f..14f
                IslandSlider(
                    stringResource(R.string.settings_xiaomi_super_island_left_limit),
                    left.toFloat(),
                    range
                ) { value ->
                    update(if (settings.fullLyricShowLeftCover) settings.copy(leftWithCoverTextChars = value.toInt()) else settings.copy(leftWithoutCoverTextChars = value.toInt()))
                }
            }
            SwitchRow(
                stringResource(R.string.settings_xiaomi_super_island_colorize),
                settings.textColorEnabled
            ) { update(settings.copy(textColorEnabled = it)) }
            if (settings.textColorEnabled) {
                IslandSpinner(
                    title = stringResource(R.string.settings_xiaomi_super_island_color_source),
                    labels = colorLabels,
                    selected = settings.colorSource,
                    onSelected = { update(settings.copy(colorSource = it)) }
                )
                if (settings.colorSource == XiaomiSuperIslandSettings.COLOR_SOURCE_CUSTOM) {
                    Button(
                        onClick = { showCustomColor = true },
                        modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth()
                    ) {
                        Text(text = stringResource(R.string.settings_xiaomi_super_island_custom_color))
                    }
                }
            }
        }

        SettingsSection(stringResource(R.string.settings_xiaomi_super_island_notification_section)) {
            SwitchRow(
                stringResource(R.string.settings_xiaomi_super_island_progress_color),
                settings.progressColorEnabled
            ) { update(settings.copy(progressColorEnabled = it)) }
            IslandSpinner(
                title = stringResource(R.string.settings_xiaomi_super_island_actions),
                labels = actionLabels,
                selected = settings.actionStyle,
                onSelected = { update(settings.copy(actionStyle = it)) }
            )
            if (settings.actionStyle == XiaomiSuperIslandSettings.ACTION_STYLE_MEDIA_CONTROLS) {
                IslandSpinner(
                    title = stringResource(R.string.settings_xiaomi_super_island_notification_style),
                    labels = notificationLabels,
                    selected = settings.notificationStyle,
                    onSelected = { update(settings.copy(notificationStyle = it)) }
                )
                if (settings.notificationStyle == XiaomiSuperIslandSettings.NOTIFICATION_STYLE_STANDARD) {
                    IslandSpinner(
                        title = stringResource(R.string.settings_xiaomi_super_island_button_layout),
                        labels = buttonLabels,
                        selected = settings.mediaButtonLayout,
                        onSelected = { update(settings.copy(mediaButtonLayout = it)) }
                    )
                }
            }
            IslandSpinner(
                title = stringResource(R.string.settings_xiaomi_super_island_click),
                labels = clickLabels,
                selected = settings.clickStyle,
                onSelected = { update(settings.copy(clickStyle = it)) }
            )
            SwitchRow(
                stringResource(R.string.settings_xiaomi_super_island_share),
                settings.shareEnabled
            ) { update(settings.copy(shareEnabled = it)) }
            if (settings.shareEnabled) {
                IslandSpinner(
                    title = stringResource(R.string.settings_xiaomi_super_island_share_format),
                    labels = shareLabels,
                    selected = settings.shareFormat,
                    onSelected = { update(settings.copy(shareFormat = it)) }
                )
            }
        }

        SettingsSection(stringResource(R.string.settings_xiaomi_super_island_compat_section)) {
            val mode = settings.xmsfBypassMode.coerceIn(xmsfLabels.indices)
            WindowSpinnerPreference(
                title = stringResource(R.string.settings_xiaomi_super_island_xmsf_mode),
                summary = xmsfSummaries[mode],
                items = xmsfLabels.mapIndexed { index, label -> DropdownItem(title = label, summary = xmsfSummaries[index]) },
                selectedIndex = mode,
                onSelectedIndexChange = { update(settings.copy(xmsfBypassMode = it)) }
            )
            if (settings.xmsfBypassMode == XiaomiSuperIslandSettings.XMSF_MODE_CUSTOM) {
                IslandSlider(
                    stringResource(R.string.settings_xiaomi_super_island_xmsf_duration),
                    (settings.xmsfCustomDurationMs / 50).toFloat(),
                    2f..10f
                ) { update(settings.copy(xmsfCustomDurationMs = it.toInt() * 50)) }
            }
            val dismissIndex = dismissValues.indexOf(settings.dismissDelayMs).coerceAtLeast(0)
            IslandSpinner(
                title = stringResource(R.string.settings_xiaomi_super_island_dismiss_delay),
                labels = dismissLabels,
                selected = dismissIndex,
                onSelected = { update(settings.copy(dismissDelayMs = dismissValues[it])) }
            )
        }

        SettingsSection(stringResource(R.string.settings_xiaomi_super_island_diagnostics_section)) {
            SettingsNavigationEntry(
                title = stringResource(R.string.settings_xiaomi_super_island_export_log),
                description = stringResource(R.string.settings_xiaomi_super_island_export_log_summary),
                onClick = { LogExportHelper(context).shareSuperIslandLog() },
            )
        }
    }

    if (showCustomColor) {
        Dialog(onDismissRequest = { showCustomColor = false }) {
            SettingsCard {
                Text(text = stringResource(R.string.settings_xiaomi_super_island_custom_color))
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
                        update(settings.copy(customColor = customColor.toArgb()))
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
private fun IslandSpinner(title: String, labels: List<String>, selected: Int, onSelected: (Int) -> Unit) {
    val safe = selected.coerceIn(labels.indices)
    WindowSpinnerPreference(
        title = title,
        summary = labels[safe],
        items = labels.map { DropdownItem(title = it) },
        selectedIndex = safe,
        onSelectedIndexChange = onSelected
    )
}

@Composable
private fun IslandSlider(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    SliderPreference(
        title = title,
        summary = null,
        valueText = value.toInt().toString(),
        value = value,
        valueRange = range,
        onValueChange = onValueChange
    )
}
