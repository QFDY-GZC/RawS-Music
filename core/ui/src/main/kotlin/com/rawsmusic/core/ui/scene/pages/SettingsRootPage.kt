package com.rawsmusic.core.ui.scene.pages

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.rawsmusic.core.ui.R
import com.rawsmusic.core.ui.scene.NavScene

@Composable
fun SettingsRootPage(
    onBack: () -> Unit,
    onNavigate: (NavScene) -> Unit
) {
    SettingsPage(title = stringResource(R.string.settings_root_title), onBack = onBack) {
        SectionHeader(stringResource(R.string.settings_root_section_library))
        SettingsCard {
            SettingsNavigationEntry(
                title = stringResource(R.string.settings_scan_settings_title),
                description = stringResource(R.string.settings_scan_settings_summary),
                onClick = { onNavigate(NavScene.SCAN_SETTINGS) }
            )
        }

        SectionHeader(stringResource(R.string.settings_root_section_appearance))
        SettingsCard {
            SettingsNavigationEntry(
                title = stringResource(R.string.settings_appearance_title),
                description = stringResource(R.string.settings_appearance_summary),
                onClick = { onNavigate(NavScene.APPEARANCE) }
            )
            SettingsNavigationEntry(
                title = stringResource(R.string.settings_player_interface_title),
                description = stringResource(R.string.settings_player_interface_summary),
                onClick = { onNavigate(NavScene.PLAYER_INTERFACE) }
            )
            SettingsNavigationEntry(
                title = stringResource(R.string.settings_album_art_title),
                description = stringResource(R.string.settings_album_art_summary),
                onClick = { onNavigate(NavScene.ALBUM_ART_SETTINGS) }
            )
            SettingsNavigationEntry(
                title = stringResource(R.string.settings_global_font_title),
                description = stringResource(R.string.settings_global_font_summary),
                onClick = { onNavigate(NavScene.GLOBAL_FONT_SETTINGS) }
            )
        }

        SectionHeader(stringResource(R.string.settings_root_section_audio))
        SettingsCard {
            SettingsNavigationEntry(
                title = stringResource(R.string.settings_audio_settings_title),
                description = stringResource(R.string.settings_audio_settings_summary),
                onClick = { onNavigate(NavScene.AUDIO_SETTINGS) }
            )
            SettingsNavigationEntry(
                title = stringResource(R.string.settings_transition_settings_title),
                description = stringResource(R.string.settings_transition_settings_summary),
                onClick = { onNavigate(NavScene.TRANSITION_SETTINGS) }
            )
            SettingsNavigationEntry(
                title = stringResource(R.string.settings_audio_effects_title),
                description = stringResource(R.string.settings_audio_effects_summary),
                onClick = { onNavigate(NavScene.AUDIO_EFFECTS) }
            )
            SettingsNavigationEntry(
                title = stringResource(R.string.settings_usb_dac_title),
                description = stringResource(R.string.settings_usb_dac_summary),
                onClick = { onNavigate(NavScene.USB_DAC_SETTINGS) }
            )
        }

        SectionHeader(stringResource(R.string.settings_root_section_lyric))
        SettingsCard {
            SettingsNavigationEntry(
                title = stringResource(R.string.settings_lyric_management_title),
                description = stringResource(R.string.settings_lyric_management_summary),
                onClick = { onNavigate(NavScene.LYRIC_MANAGEMENT) }
            )
            SettingsNavigationEntry(
                title = stringResource(R.string.settings_status_bar_lyric_title),
                description = stringResource(R.string.settings_status_bar_lyric_summary),
                onClick = { onNavigate(NavScene.STATUS_BAR_LYRIC) }
            )
            SettingsNavigationEntry(
                title = stringResource(R.string.settings_lyric_font_title),
                description = stringResource(R.string.settings_lyric_font_summary),
                onClick = { onNavigate(NavScene.LYRIC_FONT_SETTINGS) }
            )
        }

        SectionHeader(stringResource(R.string.settings_root_section_data))
        SettingsCard {
            SettingsNavigationEntry(
                title = stringResource(R.string.settings_webdav_backup_title),
                description = stringResource(R.string.settings_webdav_backup_summary),
                onClick = { onNavigate(NavScene.WEBDAV_BACKUP) }
            )
            SettingsNavigationEntry(
                title = stringResource(R.string.settings_log_viewer_title),
                description = stringResource(R.string.settings_log_viewer_summary),
                onClick = { onNavigate(NavScene.LOG_VIEWER) }
            )
        }

        SectionHeader(stringResource(R.string.settings_root_section_other))
        SettingsCard {
            SettingsNavigationEntry(
                title = stringResource(R.string.settings_about_raws_music_title),
                description = stringResource(R.string.settings_about_raws_music_summary),
                onClick = { onNavigate(NavScene.ABOUT) }
            )
        }
    }
}
