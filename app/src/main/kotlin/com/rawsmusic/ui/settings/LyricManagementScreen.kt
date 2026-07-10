package com.rawsmusic.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.player.LyriconProviderManager
import androidx.compose.ui.res.stringResource
import com.rawsmusic.R

@Composable
fun LiquidGlassLyricManagementScreen(
    onNavigateToLyricFontSettings: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val lyriconPrefs = AppPreferences.Lyricon
    var lyriconEnabled by remember { mutableStateOf(lyriconPrefs.enabled) }
    var lyriconTranslation by remember { mutableStateOf(lyriconPrefs.displayTranslation) }
    var lyriconRoma by remember { mutableStateOf(lyriconPrefs.displayRoma) }
    var lyriconStatus by remember {
        mutableStateOf(
            if (!lyriconEnabled) context.getString(R.string.settings_status_disabled)
            else if (LyriconProviderManager.isConnected()) context.getString(R.string.settings_status_connected)
            else context.getString(R.string.settings_status_disconnected)
        )
    }

    LaunchedEffect(lyriconEnabled) {
        if (lyriconEnabled) {
            LyriconProviderManager.onConnectionStatusChanged = { status ->
                lyriconStatus = when {
                    !AppPreferences.Lyricon.enabled -> context.getString(R.string.settings_status_disabled)
                    status == io.github.proify.lyricon.provider.ConnectionStatus.CONNECTED -> context.getString(R.string.settings_status_connected)
                    status == io.github.proify.lyricon.provider.ConnectionStatus.CONNECTING -> context.getString(R.string.settings_status_connecting)
                    else -> context.getString(R.string.settings_status_disconnected)
                }
            }
        } else {
            LyriconProviderManager.onConnectionStatusChanged = null
        }
    }

    SettingsPage(title = stringResource(R.string.settings_lyric_management_title), onBack = onBack) {
        SettingsSection(stringResource(R.string.settings_lyricon_section)) {
            SettingsInfoEntry(
                title = stringResource(R.string.settings_lyricon_status_title),
                description = stringResource(R.string.settings_lyricon_status_desc, lyriconStatus)
            )
            SwitchRow(stringResource(R.string.settings_lyricon_enable), lyriconEnabled) { checked ->
                lyriconEnabled = checked
                lyriconPrefs.enabled = checked
                if (checked) {
                    LyriconProviderManager.init(context.applicationContext, com.rawsmusic.R.mipmap.ic_launcher)
                    lyriconStatus = if (LyriconProviderManager.isConnected()) context.getString(R.string.settings_status_connected) else context.getString(R.string.settings_status_disconnected)
                } else {
                    LyriconProviderManager.stopPositionSync()
                    LyriconProviderManager.destroy()
                    lyriconStatus = context.getString(R.string.settings_status_disabled)
                }
            }
            SwitchRow(stringResource(R.string.settings_lyricon_show_translation), lyriconTranslation, enabled = lyriconEnabled) { checked ->
                lyriconTranslation = checked
                LyriconProviderManager.setDisplayTranslation(checked)
            }
            SwitchRow(stringResource(R.string.settings_lyricon_show_roma), lyriconRoma, enabled = lyriconEnabled) { checked ->
                lyriconRoma = checked
                LyriconProviderManager.setDisplayRoma(checked)
            }
        }

        SettingsSection(stringResource(R.string.settings_album_art_display)) {
            SettingsNavigationEntry(
                title = stringResource(R.string.settings_lyric_font_entry_title),
                description = stringResource(R.string.settings_lyric_font_entry_desc),
                onClick = onNavigateToLyricFontSettings
            )
        }
    }
}
