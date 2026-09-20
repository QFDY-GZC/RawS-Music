package com.rawsmusic.ui.settings

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.rawsmusic.R
import com.rawsmusic.core.common.artwork.ArtworkResolutionPolicy
import com.rawsmusic.core.common.ui.AppNoticeBus
import com.rawsmusic.core.common.ui.AppNoticeIcon
import com.rawsmusic.core.ui.widget.bitmaps.BitmapProvider
import com.rawsmusic.core.ui.widget.bitmaps.DefaultAlbumArtworkPolicy
import com.rawsmusic.module.data.prefs.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LiquidGlassAlbumArtSettingsScreen(
    onBack: () -> Unit
) {
    var forceArgb8888 by remember { mutableStateOf(AppPreferences.AlbumArt.forceArgb8888) }
    var useHigherRes by remember { mutableStateOf(AppPreferences.AlbumArt.useHigherRes) }
    var sendHighResolutionArtwork by remember {
        mutableStateOf(AppPreferences.AlbumArt.sendHighResolutionArtwork)
    }
    var alwaysShowCover by remember { mutableStateOf(AppPreferences.AlbumArt.alwaysShowCover) }
    var useDefaultArtwork by remember { mutableStateOf(DefaultAlbumArtworkPolicy.enabled) }
    var coverAnimation by remember { mutableStateOf(AppPreferences.AlbumArt.coverAnimation) }
    var artistBiographyEnabled by remember { mutableStateOf(AppPreferences.AlbumArt.artistBiographyEnabled) }
    var artistArtworkViewerEnabled by remember { mutableStateOf(AppPreferences.AlbumArt.artistArtworkViewerEnabled) }
    val qualityFeatureSupported = remember {
        ArtworkResolutionPolicy.qualityFeatureSupported(Runtime.getRuntime().maxMemory())
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cacheClearing by remember { mutableStateOf(false) }

    SettingsPage(title = stringResource(R.string.settings_album_art_title), onBack = onBack) {
        SettingsSection(stringResource(R.string.settings_album_art_quality)) {
            SwitchRow(
                stringResource(R.string.settings_album_art_force_argb_switch),
                forceArgb8888,
                enabled = qualityFeatureSupported,
            ) { checked ->
                forceArgb8888 = checked
                AppPreferences.AlbumArt.forceArgb8888 = checked
                // Decode configuration changed: drop reconstructable memory wrappers so the next
                // provider flight uses the new pixel config instead of reusing the old bitmap.
                BitmapProvider.trimMemory()
            }
            SettingsInfoEntry(
                title = stringResource(R.string.settings_album_art_force_argb_title),
                description = stringResource(R.string.settings_album_art_force_argb_desc)
            )
            SwitchRow(
                stringResource(R.string.settings_album_art_send_high_res_switch),
                sendHighResolutionArtwork,
            ) { checked ->
                sendHighResolutionArtwork = checked
                AppPreferences.AlbumArt.sendHighResolutionArtwork = checked
            }
            SettingsInfoEntry(
                title = stringResource(R.string.settings_album_art_send_high_res_title),
                description = stringResource(R.string.settings_album_art_send_high_res_desc),
            )
        }

        SettingsSection(stringResource(R.string.settings_album_art_labs)) {
            SwitchRow(
                stringResource(R.string.settings_album_art_high_res_switch),
                useHigherRes,
                enabled = qualityFeatureSupported,
            ) { checked ->
                useHigherRes = checked
                AppPreferences.AlbumArt.useHigherRes = checked
                // Resolution-tier changes invalidate reconstructable wrappers so future requests
                // use the new high tier without retaining stale lower-resolution owners.
                BitmapProvider.trimMemory()
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

        SettingsSection(stringResource(R.string.settings_artist_features)) {
            SwitchRow(
                stringResource(R.string.settings_artist_biography_switch),
                artistBiographyEnabled,
            ) { checked ->
                artistBiographyEnabled = checked
                AppPreferences.AlbumArt.artistBiographyEnabled = checked
            }
            SettingsInfoEntry(
                title = stringResource(R.string.settings_artist_biography_switch),
                description = stringResource(R.string.settings_artist_biography_switch_summary),
            )
            SwitchRow(
                stringResource(R.string.settings_artist_artwork_viewer_switch),
                artistArtworkViewerEnabled,
            ) { checked ->
                artistArtworkViewerEnabled = checked
                AppPreferences.AlbumArt.artistArtworkViewerEnabled = checked
            }
            SettingsInfoEntry(
                title = stringResource(R.string.settings_artist_artwork_viewer_switch),
                description = stringResource(R.string.settings_artist_artwork_viewer_switch_summary),
            )
        }

        SettingsSection(stringResource(R.string.settings_album_art_cache)) {
            SettingsActionRow(
                title = if (cacheClearing) {
                    stringResource(R.string.settings_album_art_clearing_cache)
                } else {
                    stringResource(R.string.settings_album_art_clear_cache)
                },
                description = stringResource(R.string.settings_album_art_clear_cache_desc),
            ) {
                if (!cacheClearing) {
                    cacheClearing = true
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            BitmapProvider.clearArtworkCaches(context)
                        }
                        cacheClearing = false
                        AppNoticeBus.post(
                            message = context.getString(R.string.settings_album_art_cache_cleared),
                            icon = AppNoticeIcon.ARTWORK,
                        )
                    }
                }
            }
        }
    }
}
