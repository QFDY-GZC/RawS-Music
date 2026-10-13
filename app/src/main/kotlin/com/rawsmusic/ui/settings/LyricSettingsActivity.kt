package com.rawsmusic.ui.settings

import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.rawsmusic.R
import com.rawsmusic.module.data.prefs.AppPreferences
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TextField

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
                title = stringResource(R.string.settings_font_settings_title),
                description = stringResource(R.string.settings_font_settings_summary),
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
        SmallTitle(text = stringResource(R.string.settings_lyric_export_section))
        SettingsCardGroup {
            var signature by remember { mutableStateOf(AppPreferences.Lyrics.exportSignature) }
            TextField(
                value = signature,
                onValueChange = {
                    signature = it.take(40)
                    AppPreferences.Lyrics.exportSignature = signature
                },
                label = stringResource(R.string.settings_lyric_export_signature),
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
        }
    }
}
