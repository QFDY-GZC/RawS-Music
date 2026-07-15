package com.rawsmusic.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.rawsmusic.module.data.prefs.AppPreferences
import androidx.compose.ui.res.stringResource
import com.rawsmusic.R

@Composable
fun LiquidGlassPlayerInterfaceScreen(
    onBack: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    var defaultBackgroundEnabled by remember { mutableStateOf(AppPreferences.UI.isDefaultBackgroundEnabled) }
    var immersiveEnabled by remember { mutableStateOf(AppPreferences.UI.isImmersiveEnabled) }
    var audioVisualizerEnabled by remember { mutableStateOf(AppPreferences.UI.isAudioVisualizerEnabled) }
    var miniCoverEnabled by remember { mutableStateOf(AppPreferences.UI.isMiniCoverEnabled) }
    var playPageMemoryEnabled by remember { mutableStateOf(AppPreferences.UI.isPlayPageMemoryEnabled) }

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
            SwitchRow(stringResource(R.string.settings_player_visualizer), audioVisualizerEnabled) { checked ->
                audioVisualizerEnabled = checked
                AppPreferences.UI.isAudioVisualizerEnabled = checked
                android.content.Intent("com.rawsmusic.action.AUDIO_VISUALIZER_SETTING_CHANGED").also {
                    it.setPackage(context.packageName)
                    context.sendBroadcast(it)
                }
            }
            SettingsInfoEntry(
                title = stringResource(R.string.settings_player_visualizer),
                description = stringResource(R.string.settings_player_visualizer_desc)
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
