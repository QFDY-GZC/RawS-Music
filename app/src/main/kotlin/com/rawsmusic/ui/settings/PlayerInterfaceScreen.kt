package com.rawsmusic.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.rawsmusic.module.data.prefs.AppPreferences
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.rawsmusic.R
import com.rawsmusic.core.ui.widget.RawWindowDropdownPreference
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem

@Composable
fun LiquidGlassPlayerInterfaceScreen(
    onBack: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    var defaultBackgroundEnabled by remember { mutableStateOf(AppPreferences.UI.isDefaultBackgroundEnabled) }
    var immersiveEnabled by remember { mutableStateOf(AppPreferences.UI.isImmersiveEnabled) }
    var audioVisualizerEnabled by remember { mutableStateOf(AppPreferences.UI.isAudioVisualizerEnabled) }
    var statusBarHidden by remember { mutableStateOf(AppPreferences.UI.isStatusBarHidden) }
    var miniCoverEnabled by remember { mutableStateOf(AppPreferences.UI.isMiniCoverEnabled) }
    var playPageMemoryEnabled by remember { mutableStateOf(AppPreferences.UI.isPlayPageMemoryEnabled) }
    var playerTitleAlignment by remember { mutableStateOf(AppPreferences.UI.playerTitleAlignment) }
    var miniLyricAlignment by remember { mutableStateOf(AppPreferences.UI.miniLyricAlignment) }
    fun persistAudioVisualizer(enabled: Boolean) {
        audioVisualizerEnabled = enabled
        AppPreferences.UI.isAudioVisualizerEnabled = enabled
        android.content.Intent("com.rawsmusic.action.AUDIO_VISUALIZER_SETTING_CHANGED").also {
            it.setPackage(context.packageName)
            context.sendBroadcast(it)
        }
    }
    val alignmentItems = listOf(
        0 to R.string.desktop_lyric_left,
        1 to R.string.desktop_lyric_center,
        2 to R.string.desktop_lyric_right,
    )
    val playerTitleAlignmentEntry = DropdownEntry(
        items = alignmentItems.map { (value, labelRes) ->
            DropdownItem(
                text = stringResource(labelRes),
                selected = playerTitleAlignment == value,
                onClick = {
                    playerTitleAlignment = value
                    AppPreferences.UI.playerTitleAlignment = value
                }
            )
        }
    )
    val miniLyricAlignmentEntry = DropdownEntry(
        items = alignmentItems.map { (value, labelRes) ->
            DropdownItem(
                text = stringResource(labelRes),
                selected = miniLyricAlignment == value,
                onClick = {
                    miniLyricAlignment = value
                    AppPreferences.UI.miniLyricAlignment = value
                }
            )
        }
    )

    val visualizerPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        persistAudioVisualizer(granted)
        if (!granted) {
            Toast.makeText(context, R.string.permission_denied, Toast.LENGTH_SHORT).show()
        }
    }

    SettingsPage(title = stringResource(R.string.settings_player_interface_title), onBack = onBack) {
        SettingsSection(stringResource(R.string.settings_player_bg_section)) {
            SwitchRow(stringResource(R.string.settings_player_default_bg), defaultBackgroundEnabled) { checked ->
                defaultBackgroundEnabled = checked
                AppPreferences.UI.isDefaultBackgroundEnabled = checked
                android.content.Intent("com.rawsmusic.action.DEFAULT_BACKGROUND_SETTING_CHANGED").also {
                    it.setPackage(context.packageName)
                    context.sendBroadcast(it)
                }
            }
            SettingsInfoEntry(
                title = stringResource(R.string.settings_player_default_bg_rule_title),
                description = stringResource(R.string.settings_player_default_bg_rule_desc)
            )
        }

        SettingsSection(stringResource(R.string.settings_player_play_page_section)) {
            SwitchRow(stringResource(R.string.settings_player_immersive), immersiveEnabled) { checked ->
                immersiveEnabled = checked
                AppPreferences.UI.isImmersiveEnabled = checked
                android.content.Intent("com.rawsmusic.action.IMMERSIVE_SETTING_CHANGED").also {
                    it.setPackage(context.packageName)
                    context.sendBroadcast(it)
                }
            }
            SettingsInfoEntry(
                title = stringResource(R.string.settings_player_immersive),
                description = stringResource(R.string.settings_player_immersive_desc)
            )
            SwitchRow(stringResource(R.string.settings_player_hide_status_bar), statusBarHidden) { checked ->
                statusBarHidden = checked
                AppPreferences.UI.isStatusBarHidden = checked
            }
            SettingsInfoEntry(
                title = stringResource(R.string.settings_player_hide_status_bar),
                description = stringResource(R.string.settings_player_hide_status_bar_desc)
            )
            SwitchRow(stringResource(R.string.settings_player_visualizer), audioVisualizerEnabled) { checked ->
                if (checked && ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.RECORD_AUDIO
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    visualizerPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                } else {
                    persistAudioVisualizer(checked)
                }
            }
            SettingsInfoEntry(
                title = stringResource(R.string.settings_player_visualizer),
                description = stringResource(R.string.settings_player_visualizer_desc)
            )
            RawWindowDropdownPreference(
                entry = playerTitleAlignmentEntry,
                title = stringResource(R.string.settings_player_title_alignment),
                summary = stringResource(
                    when (playerTitleAlignment) {
                        1 -> R.string.desktop_lyric_center
                        2 -> R.string.desktop_lyric_right
                        else -> R.string.desktop_lyric_left
                    }
                ),
                showValue = true,
                maxHeight = 260.dp,
                collapseOnSelection = true,
            )
            SettingsInfoEntry(
                title = stringResource(R.string.settings_player_title_alignment),
                description = stringResource(R.string.settings_player_title_alignment_desc)
            )
            RawWindowDropdownPreference(
                entry = miniLyricAlignmentEntry,
                title = stringResource(R.string.settings_mini_lyric_alignment),
                summary = stringResource(
                    when (miniLyricAlignment) {
                        1 -> R.string.desktop_lyric_center
                        2 -> R.string.desktop_lyric_right
                        else -> R.string.desktop_lyric_left
                    }
                ),
                showValue = true,
                maxHeight = 260.dp,
                collapseOnSelection = true,
            )
            SettingsInfoEntry(
                title = stringResource(R.string.settings_mini_lyric_alignment),
                description = stringResource(R.string.settings_mini_lyric_alignment_desc)
            )
        }

        SettingsSection(stringResource(R.string.settings_player_main_section)) {
            SwitchRow(stringResource(R.string.settings_player_mini_cover), miniCoverEnabled) { checked ->
                miniCoverEnabled = checked
                AppPreferences.UI.isMiniCoverEnabled = checked
                android.content.Intent("com.rawsmusic.action.MINI_COVER_SETTING_CHANGED").also {
                    it.setPackage(context.packageName)
                    context.sendBroadcast(it)
                }
            }
            SettingsInfoEntry(
                title = stringResource(R.string.settings_player_mini_cover),
                description = stringResource(R.string.settings_player_mini_cover_desc)
            )
            SwitchRow(stringResource(R.string.settings_player_page_memory), playPageMemoryEnabled) { checked ->
                playPageMemoryEnabled = checked
                AppPreferences.UI.isPlayPageMemoryEnabled = checked
            }
            SettingsInfoEntry(
                title = stringResource(R.string.settings_player_page_memory),
                description = stringResource(R.string.settings_player_page_memory_desc)
            )
        }
    }
}
