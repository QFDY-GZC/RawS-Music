package com.rawsmusic.core.ui.scene.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.rawsmusic.core.ui.R
import com.rawsmusic.core.ui.scene.LocalRawWindowLayoutProfile
import com.rawsmusic.core.ui.scene.NavScene

@Composable
fun SettingsRootPage(
    onBack: () -> Unit,
    onNavigate: (NavScene) -> Unit
) {
    val windowLayoutProfile = LocalRawWindowLayoutProfile.current
    SettingsPage(
        title = stringResource(R.string.settings_root_title),
        onBack = onBack,
        contentMaxWidth = windowLayoutProfile.settingsRootContentMaxWidth,
        collapsingLargeTitle = true,
    ) {
        if (windowLayoutProfile.supportsSupportingPane) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    SettingsPlaybackAudioSection(onNavigate)
                    SettingsInterfaceDisplaySection(onNavigate)
                }
                Column(modifier = Modifier.weight(1f)) {
                    SettingsLyricsExtensionsSection(onNavigate)
                    SettingsLibraryDataSection(onNavigate)
                    SettingsHelpDiagnosticsSection(onNavigate)
                }
            }
        } else {
            SettingsPlaybackAudioSection(onNavigate)
            SettingsInterfaceDisplaySection(onNavigate)
            SettingsLyricsExtensionsSection(onNavigate)
            SettingsLibraryDataSection(onNavigate)
            SettingsHelpDiagnosticsSection(onNavigate)
        }
    }
}

@Composable
private fun SettingsPlaybackAudioSection(onNavigate: (NavScene) -> Unit) {
    SectionHeader(stringResource(R.string.settings_root_section_playback_audio))
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
}

@Composable
private fun SettingsInterfaceDisplaySection(onNavigate: (NavScene) -> Unit) {
    SectionHeader(stringResource(R.string.settings_root_section_interface_display))
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
            title = stringResource(R.string.settings_font_settings_title),
            description = stringResource(R.string.settings_font_settings_summary),
            onClick = { onNavigate(NavScene.GLOBAL_FONT_SETTINGS) }
        )
        SettingsNavigationEntry(
            title = stringResource(R.string.settings_hdr_title),
            description = stringResource(R.string.settings_hdr_summary),
            onClick = { onNavigate(NavScene.HDR_SETTINGS) }
        )
        SettingsNavigationEntry(
            title = stringResource(R.string.settings_personalization_title),
            description = stringResource(R.string.settings_personalization_summary),
            onClick = { onNavigate(NavScene.PERSONALIZATION_SETTINGS) }
        )
    }
}

@Composable
private fun SettingsLyricsExtensionsSection(onNavigate: (NavScene) -> Unit) {
    SectionHeader(stringResource(R.string.settings_root_section_lyrics_extensions))
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
    }
}

@Composable
private fun SettingsLibraryDataSection(onNavigate: (NavScene) -> Unit) {
    SectionHeader(stringResource(R.string.settings_root_section_library_data))
    SettingsCard {
        SettingsNavigationEntry(
            title = stringResource(R.string.settings_scan_settings_title),
            description = stringResource(R.string.settings_scan_settings_summary),
            onClick = { onNavigate(NavScene.SCAN_SETTINGS) }
        )
        SettingsNavigationEntry(
            title = stringResource(R.string.settings_webdav_backup_title),
            description = stringResource(R.string.settings_webdav_backup_summary),
            onClick = { onNavigate(NavScene.WEBDAV_BACKUP) }
        )
    }
}

@Composable
private fun SettingsHelpDiagnosticsSection(onNavigate: (NavScene) -> Unit) {
    SectionHeader(stringResource(R.string.settings_root_section_help_diagnostics))
    SettingsCard {
        SettingsNavigationEntry(
            title = stringResource(R.string.settings_log_viewer_title),
            description = stringResource(R.string.settings_log_viewer_summary),
            onClick = { onNavigate(NavScene.LOG_VIEWER) }
        )
        SettingsNavigationEntry(
            title = stringResource(R.string.settings_about_raws_music_title),
            description = stringResource(R.string.settings_about_raws_music_summary),
            onClick = { onNavigate(NavScene.ABOUT) }
        )
    }
}
