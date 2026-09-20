package com.rawsmusic.ui.settings

import android.content.Intent
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.rawsmusic.R
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.data.prefs.AppPreferences.Lyrics
import com.rawsmusic.module.player.PlayerService
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.preference.WindowSpinnerPreference

class SystemLyricDeliveryActivity : BaseSettingsActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SystemLyricDeliveryScreen(
                onBack = { finish() },
                onOpenXiaomiSettings = { navigateToSettings(XiaomiSuperIslandSettingsActivity::class.java) },
                onSettingsChanged = { refreshPlayerNotification() }
            )
        }
    }

    private fun refreshPlayerNotification() {
        runCatching {
            startService(
                Intent(this, PlayerService::class.java)
                    .setAction(PlayerService.ACTION_REFRESH_LYRIC_METADATA)
            )
        }
    }
}

@Composable
private fun SystemLyricDeliveryScreen(
    onBack: () -> Unit,
    onOpenXiaomiSettings: () -> Unit,
    onSettingsChanged: () -> Unit
) {
    var liveEnabled by remember { mutableStateOf(Lyrics.liveUpdateLyricEnabled) }
    var liveMode by remember { mutableStateOf(Lyrics.liveUpdateLyricMode) }
    var liveDisplayMode by remember { mutableStateOf(Lyrics.liveUpdateLyricDisplayMode) }
    var liveSecondary by remember { mutableStateOf(Lyrics.liveUpdateLyricSecondaryMode) }
    var colorOsBridgeEnabled by remember { mutableStateOf(Lyrics.colorOsBridgeLyricEnabled) }
    var colorOsDeliveryMode by remember { mutableStateOf(Lyrics.colorOsBridgeDeliveryMode) }
    var xiaomiEnabled by remember { mutableStateOf(Lyrics.xiaomiSuperIslandLyricEnabled) }
    var notificationButtons by remember { mutableStateOf(Lyrics.mediaNotificationButtonIds) }

    val liveContentLabels = listOf(
        stringResource(R.string.settings_live_update_lyric_original),
        stringResource(R.string.settings_live_update_lyric_translation),
        stringResource(R.string.settings_live_update_lyric_pronunciation)
    )
    val liveDisplayLabels = listOf(
        stringResource(R.string.settings_live_update_lyric_compact),
        stringResource(R.string.settings_live_update_lyric_full)
    )
    val secondaryLabels = listOf(
        stringResource(R.string.settings_live_update_lyric_secondary_song),
        stringResource(R.string.settings_live_update_lyric_translation),
        stringResource(R.string.settings_live_update_lyric_pronunciation)
    )
    val secondaryValues = listOf(
        Lyrics.LIVE_UPDATE_LYRIC_SECONDARY_MODE_SONG,
        Lyrics.LIVE_UPDATE_LYRIC_SECONDARY_MODE_TRANSLATION,
        Lyrics.LIVE_UPDATE_LYRIC_SECONDARY_MODE_PRONUNCIATION
    )
    val buttonPairs = listOf(
        listOf(Lyrics.MEDIA_NOTIFICATION_BUTTON_PLAYBACK_MODE, Lyrics.MEDIA_NOTIFICATION_BUTTON_DESKTOP_LYRIC),
        listOf(Lyrics.MEDIA_NOTIFICATION_BUTTON_PLAYBACK_MODE, Lyrics.MEDIA_NOTIFICATION_BUTTON_FAVORITE),
        listOf(Lyrics.MEDIA_NOTIFICATION_BUTTON_DESKTOP_LYRIC, Lyrics.MEDIA_NOTIFICATION_BUTTON_FAVORITE)
    )
    val buttonLabels = listOf(
        stringResource(R.string.settings_media_notification_buttons_playback_desktop),
        stringResource(R.string.settings_media_notification_buttons_playback_favorite),
        stringResource(R.string.settings_media_notification_buttons_desktop_favorite)
    )
    val colorOsDeliveryValues = listOf(
        Lyrics.COLOROS_DELIVERY_MODE_MODULE,
        Lyrics.COLOROS_DELIVERY_MODE_NON_MODULE
    )
    val colorOsDeliveryLabels = listOf(
        stringResource(R.string.settings_coloros_bridge_mode_module),
        stringResource(R.string.settings_coloros_bridge_mode_non_module)
    )

    SettingsPage(
        title = stringResource(R.string.settings_system_lyric_delivery_title),
        onBack = onBack
    ) {
        SettingsSection(stringResource(R.string.settings_live_update_lyric_section)) {
            SwitchRow(
                stringResource(R.string.settings_live_update_lyric_enabled),
                liveEnabled
            ) {
                liveEnabled = it
                Lyrics.liveUpdateLyricEnabled = it
                onSettingsChanged()
            }
            SettingsInfoEntry(
                title = stringResource(R.string.settings_live_update_lyric_enabled),
                description = stringResource(R.string.settings_live_update_lyric_enabled_summary)
            )
            WindowSpinnerPreference(
                title = stringResource(R.string.settings_live_update_lyric_content),
                summary = liveContentLabels[liveMode.coerceIn(liveContentLabels.indices)],
                items = liveContentLabels.map { DropdownItem(title = it) },
                selectedIndex = liveMode.coerceIn(liveContentLabels.indices),
                enabled = liveEnabled,
                onSelectedIndexChange = {
                    liveMode = it
                    Lyrics.liveUpdateLyricMode = it
                    onSettingsChanged()
                }
            )
            WindowSpinnerPreference(
                title = stringResource(R.string.settings_live_update_lyric_display_mode),
                summary = liveDisplayLabels[liveDisplayMode.coerceIn(liveDisplayLabels.indices)],
                items = liveDisplayLabels.map { DropdownItem(title = it) },
                selectedIndex = liveDisplayMode.coerceIn(liveDisplayLabels.indices),
                enabled = liveEnabled,
                onSelectedIndexChange = {
                    liveDisplayMode = it
                    Lyrics.liveUpdateLyricDisplayMode = it
                    onSettingsChanged()
                }
            )
            val secondaryIndex = secondaryValues.indexOf(Lyrics.liveUpdateLyricSecondaryMode).coerceIn(0, secondaryLabels.lastIndex)
            WindowSpinnerPreference(
                title = stringResource(R.string.settings_live_update_lyric_secondary),
                summary = secondaryLabels[secondaryIndex],
                items = secondaryLabels.map { DropdownItem(title = it) },
                selectedIndex = secondaryIndex,
                enabled = liveEnabled,
                onSelectedIndexChange = { index ->
                    val value = secondaryValues[index]
                    liveSecondary = value
                    Lyrics.liveUpdateLyricSecondaryMode = value
                    onSettingsChanged()
                }
            )
        }

        SettingsSection(stringResource(R.string.settings_media_notification_buttons)) {
            val selectedIndex = buttonPairs.indexOfFirst { it.toSet() == notificationButtons.toSet() }
                .takeIf { it >= 0 } ?: 1
            WindowSpinnerPreference(
                title = stringResource(R.string.settings_media_notification_buttons),
                summary = buttonLabels[selectedIndex],
                items = buttonLabels.map { DropdownItem(title = it) },
                selectedIndex = selectedIndex,
                onSelectedIndexChange = { index ->
                    notificationButtons = buttonPairs[index]
                    Lyrics.mediaNotificationButtonIds = buttonPairs[index]
                    onSettingsChanged()
                }
            )
            SettingsInfoEntry(
                title = stringResource(R.string.settings_media_notification_buttons),
                description = stringResource(R.string.settings_media_notification_buttons_summary)
            )
        }

        SettingsSection(stringResource(R.string.settings_coloros_bridge_section)) {
            SwitchRow(
                stringResource(R.string.settings_coloros_bridge_enabled),
                colorOsBridgeEnabled
            ) {
                colorOsBridgeEnabled = it
                Lyrics.colorOsBridgeLyricEnabled = it
                onSettingsChanged()
            }
            SettingsInfoEntry(
                title = stringResource(R.string.settings_coloros_bridge_enabled),
                description = stringResource(R.string.settings_coloros_bridge_enabled_summary)
            )
            val deliveryIndex = colorOsDeliveryValues.indexOf(colorOsDeliveryMode).coerceAtLeast(0)
            WindowSpinnerPreference(
                title = stringResource(R.string.settings_coloros_bridge_mode),
                summary = colorOsDeliveryLabels[deliveryIndex],
                items = colorOsDeliveryLabels.map { DropdownItem(title = it) },
                selectedIndex = deliveryIndex,
                enabled = colorOsBridgeEnabled,
                onSelectedIndexChange = { index ->
                    colorOsDeliveryMode = colorOsDeliveryValues[index]
                    Lyrics.colorOsBridgeDeliveryMode = colorOsDeliveryValues[index]
                    onSettingsChanged()
                }
            )
            SettingsInfoEntry(
                title = stringResource(R.string.settings_coloros_bridge_mode),
                description = stringResource(R.string.settings_coloros_bridge_mode_summary)
            )
        }

        SettingsSection(stringResource(R.string.settings_xiaomi_super_island_title)) {
            SwitchRow(
                stringResource(R.string.settings_xiaomi_super_island_enable),
                xiaomiEnabled
            ) {
                xiaomiEnabled = it
                Lyrics.xiaomiSuperIslandLyricEnabled = it
                onSettingsChanged()
            }
            SettingsInfoEntry(
                title = stringResource(R.string.settings_xiaomi_super_island_enable),
                description = stringResource(R.string.settings_xiaomi_super_island_enable_summary)
            )
            SettingsNavigationEntry(
                title = stringResource(R.string.settings_xiaomi_super_island_custom),
                description = stringResource(R.string.settings_xiaomi_super_island_summary),
                onClick = onOpenXiaomiSettings
            )
        }
    }
}
