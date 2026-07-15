package com.rawsmusic.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.rawsmusic.core.ui.widget.bitmaps.DefaultAlbumArtworkPolicy
import com.rawsmusic.module.data.prefs.AppPreferences
import androidx.compose.ui.res.stringResource
import com.rawsmusic.R

@Composable
fun LiquidGlassAlbumArtSettingsScreen(
    onBack: () -> Unit
) {
    var forceArgb8888 by remember { mutableStateOf(AppPreferences.AlbumArt.forceArgb8888) }
    var useHigherRes by remember { mutableStateOf(AppPreferences.AlbumArt.useHigherRes) }
    var alwaysShowCover by remember { mutableStateOf(AppPreferences.AlbumArt.alwaysShowCover) }
    var useDefaultArtwork by remember { mutableStateOf(DefaultAlbumArtworkPolicy.enabled) }
    var coverAnimation by remember { mutableStateOf(AppPreferences.AlbumArt.coverAnimation) }

    SettingsPage(title = stringResource(R.string.settings_album_art_title), onBack = onBack) {
        SettingsSection(stringResource(R.string.settings_album_art_quality)) {
            SwitchRow(stringResource(R.string.settings_album_art_force_argb_switch), forceArgb8888) { checked ->
                forceArgb8888 = checked
                AppPreferences.AlbumArt.forceArgb8888 = checked
            }
            SettingsInfoEntry(
                title = stringResource(R.string.settings_album_art_force_argb_title),
                description = stringResource(R.string.settings_album_art_force_argb_desc)
            )
            SwitchRow(stringResource(R.string.settings_album_art_high_res_switch), useHigherRes) { checked ->
                useHigherRes = checked
                AppPreferences.AlbumArt.useHigherRes = checked
            }
            SettingsInfoEntry(
                title = stringResource(R.string.settings_album_art_high_res_title),
                description = stringResource(R.string.settings_album_art_high_res_desc)
            )
        }

        SettingsSection(stringResource(R.string.settings_album_art_display)) {
            SwitchRow(stringResource(R.string.settings_album_art_default_artwork), useDefaultArtwork) { checked ->
                useDefaultArtwork = checked
                DefaultAlbumArtworkPolicy.updateEnabled(checked)
            }
            SettingsInfoEntry(
                title = stringResource(R.string.settings_album_art_default_artwork_title),
                description = stringResource(R.string.settings_album_art_default_artwork_desc)
            )
            SwitchRow(stringResource(R.string.settings_album_art_always_show_cover), alwaysShowCover) { checked ->
                alwaysShowCover = checked
                AppPreferences.AlbumArt.alwaysShowCover = checked
            }
            SwitchRow(stringResource(R.string.settings_album_art_cover_animation), coverAnimation) { checked ->
                coverAnimation = checked
                AppPreferences.AlbumArt.coverAnimation = checked
            }
        }
    }
}
