package com.rawsmusic.ui.settings

import android.os.Build
import android.os.Bundle
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.rawsmusic.core.ui.R
import com.rawsmusic.core.common.ui.AppNoticeBus
import com.rawsmusic.module.data.prefs.HdrPreferences
import com.rawsmusic.module.data.prefs.HdrScene
import top.yukonga.miuix.kmp.basic.Slider
import java.util.Locale

class HdrSettingsActivity : BaseSettingsActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val settings by HdrPreferences.settings.collectAsState()
            val status = com.rawsmusic.core.ui.widget.bitmaps.rememberHdrDisplayStatus()
            val supported = status.supported
            val canAdjust = supported && Build.VERSION.SDK_INT >= 35
            val automatic = settings.requestedHeadroom(35) == 0f
            var draft by remember(settings.desiredHeadroom) {
                mutableFloatStateOf(settings.requestedHeadroom(35).takeIf { it > 0f } ?: 2f)
            }
            fun formatRatio(value: Float): String = String.format(Locale.getDefault(), "%.1f×", value)
            val enabledNotice = stringResource(R.string.settings_hdr_enabled_notice)
            SettingsPage(title = stringResource(R.string.settings_hdr_title), onBack = { finish() }) {
                SettingsCard {
                    SettingsInfoEntry(
                        title = stringResource(R.string.settings_hdr_title),
                        description = if (supported) stringResource(R.string.settings_hdr_supported, Build.VERSION.SDK_INT)
                            else stringResource(R.string.settings_hdr_unsupported),
                    )
                    SwitchRow(stringResource(R.string.settings_hdr_master), settings.enabled, enabled = supported || settings.enabled) {
                        HdrPreferences.setEnabled(it)
                        if (it) AppNoticeBus.post(enabledNotice)
                    }
                }
                SectionHeader(stringResource(R.string.settings_hdr_headroom_title))
                SettingsCard {
                    SettingsInfoEntry(
                        stringResource(R.string.settings_hdr_expected),
                        if (Build.VERSION.SDK_INT < 35 || automatic) stringResource(R.string.settings_hdr_automatic)
                        else formatRatio(draft),
                    )
                    SwitchRow(stringResource(R.string.settings_hdr_automatic), automatic, enabled = canAdjust) {
                        HdrPreferences.setHeadroom(if (it) 0f else draft)
                    }
                    if (!automatic && Build.VERSION.SDK_INT >= 35) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                            Slider(
                                value = draft,
                                valueRange = 1f..8f,
                                enabled = canAdjust,
                                onValueChange = { draft = (it * 10f).toInt() / 10f },
                                onValueChangeFinished = { HdrPreferences.setHeadroom(draft) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    SettingsInfoEntry(
                        stringResource(R.string.settings_hdr_actual),
                        status.actualRatio?.let(::formatRatio) ?: stringResource(R.string.settings_hdr_ratio_unavailable),
                    )
                    SettingsInfoEntry(
                        stringResource(R.string.settings_hdr_current_window),
                        stringResource(if (status.windowHdr) R.string.settings_hdr_window_active else R.string.settings_hdr_window_normal),
                    )
                    SettingsInfoEntry(
                        stringResource(R.string.settings_hdr_headroom_title),
                        stringResource(if (Build.VERSION.SDK_INT >= 35) R.string.settings_hdr_headroom_help
                            else R.string.settings_hdr_headroom_old_api),
                    )
                }
                SectionHeader(stringResource(R.string.settings_hdr_scenes))
                SettingsCard {
                    val labels = listOf(
                        HdrScene.HOME to R.string.settings_hdr_home,
                        HdrScene.LIBRARY to R.string.settings_hdr_library,
                        HdrScene.PLAYER to R.string.settings_hdr_player,
                        HdrScene.LYRICS to R.string.settings_hdr_lyrics,
                        HdrScene.SETTINGS to R.string.settings_hdr_settings,
                    )
                    labels.forEach { (scene, label) ->
                        SwitchRow(stringResource(label), scene in settings.scenes) { HdrPreferences.setScene(scene, it) }
                    }
                }
                SettingsCard {
                    SettingsInfoEntry(stringResource(R.string.settings_hdr_impacts_title), stringResource(R.string.settings_hdr_impacts))
                }
            }
        }
    }
}
