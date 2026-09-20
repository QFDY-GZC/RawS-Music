package com.rawsmusic.ui.settings

import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.rawsmusic.R
import top.yukonga.miuix.kmp.basic.SmallTitle

class LyricSettingsActivity : BaseSettingsActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LyricSettingsScreen(
                onBack = { finish() },
                onNavigateToManagement = {
                    navigateToSettings(LyricManagementActivity::class.java)
                },
                onNavigateToFont = {
                    navigateToSettings(LyricFontSettingsActivity::class.java)
                },
                onNavigateToStatusBar = {
                    navigateToSettings(StatusBarLyricActivity::class.java)
                },
                onNavigateToSystemDelivery = {
                    navigateToSettings(SystemLyricDeliveryActivity::class.java)
                },
            )
        }
    }
}

@Composable
private fun LyricSettingsScreen(
    onBack: () -> Unit,
    onNavigateToManagement: () -> Unit,
    onNavigateToFont: () -> Unit,
    onNavigateToStatusBar: () -> Unit,
    onNavigateToSystemDelivery: () -> Unit,
) {
    SettingsPage(
        title = stringResource(R.string.settings_lyrics_title),
        onBack = onBack,
    ) {
        SmallTitle(text = stringResource(R.string.settings_lyrics_content_section))
        SettingsCardGroup {
            SettingsNavigationEntry(
                title = stringResource(R.string.settings_lyric_management_title),
                description = stringResource(R.string.settings_lyric_management_summary),
                iconRes = R.drawable.ic_settings_lyrics_ali,
                onClick = onNavigateToManagement,
            )
            SettingsNavigationEntry(
                title = stringResource(R.string.settings_lyric_font_title),
                description = stringResource(R.string.settings_lyric_font_summary),
                iconRes = R.drawable.ic_settings_font,
                onClick = onNavigateToFont,
            )
            SettingsNavigationEntry(
                title = stringResource(R.string.settings_status_bar_lyric_title),
                description = stringResource(R.string.settings_status_bar_lyric_summary),
                onClick = onNavigateToStatusBar,
            )
            SettingsNavigationEntry(
                title = stringResource(R.string.settings_system_lyric_delivery_title),
                description = stringResource(R.string.settings_system_lyric_delivery_summary),
                onClick = onNavigateToSystemDelivery,
            )
        }
    }
}
