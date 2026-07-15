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
fun LiquidGlassStatusBarLyricScreen(
    onBack: () -> Unit
) {
    val lyricsPrefs = AppPreferences.Lyrics

    var tickerEnabled by remember { mutableStateOf(lyricsPrefs.tickerEnabled) }
    var tickerHideNotification by remember { mutableStateOf(lyricsPrefs.tickerHideNotification) }
    var tickerHeadsUpLyrics by remember { mutableStateOf(lyricsPrefs.tickerHeadsUpLyrics) }
    var samsungFloatingLyricTranslation by remember { mutableStateOf(lyricsPrefs.samsungFloatingLyricTranslation) }
    var lyricGetterEnabled by remember { mutableStateOf(lyricsPrefs.lyricGetterEnabled) }
    var bluetoothLyricEnabled by remember { mutableStateOf(lyricsPrefs.bluetoothLyricEnabled) }
    var bluetoothLyricTranslation by remember { mutableStateOf(lyricsPrefs.bluetoothLyricTranslation) }

    SettingsPage(title = stringResource(R.string.settings_status_bar_lyric_title), onBack = onBack) {
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
}
