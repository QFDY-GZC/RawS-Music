package com.rawsmusic.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.R
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.player.PlayerController
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun DvcSettingsScreen(
    onBack: () -> Unit,
    controller: PlayerController?,
) {
    var enabled by remember { mutableStateOf(AppPreferences.Player.androidDvcEnabled) }
    var noDvcHeadroomDb by remember {
        mutableFloatStateOf(AppPreferences.Player.androidNoDvcHeadroomDb)
    }

    SettingsPage(title = stringResource(R.string.settings_audio_dvc), onBack = onBack) {
        SettingsCard {
            SwitchPreference(
                title = stringResource(R.string.settings_dvc_enable_title),
                summary = stringResource(R.string.settings_dvc_enable_summary),
                checked = enabled,
                onCheckedChange = { checked ->
                    enabled = checked
                    controller?.setAndroidDvcEnabled(checked)
                        ?: run { AppPreferences.Player.androidDvcEnabled = checked }
                }
            )
        }

        Spacer(Modifier.height(12.dp))

        SettingsCard {
            SectionHeader(stringResource(R.string.settings_dvc_headroom_section))
            Spacer(Modifier.height(4.dp))

            SliderPreference(
                title = stringResource(R.string.settings_dvc_no_dvc_headroom),
                summary = stringResource(R.string.settings_dvc_no_dvc_headroom_summary),
                valueText = stringResource(
                    R.string.settings_db_value_signed_one_decimal,
                    noDvcHeadroomDb,
                ),
                value = noDvcHeadroomDb,
                onValueChange = { value ->
                    noDvcHeadroomDb = value.coerceIn(-24f, 0f)
                    AppPreferences.Player.androidNoDvcHeadroomDb = noDvcHeadroomDb
                    controller?.setAndroidNoDvcHeadroomDb(noDvcHeadroomDb)
                },
                valueRange = -24f..0f,
                steps = 23,
                enabled = !enabled,
                hapticEffect = SliderDefaults.SliderHapticEffect.Step,
            )

            Text(
                text = stringResource(
                    if (enabled) {
                        R.string.settings_dvc_status_active
                    } else {
                        R.string.settings_dvc_status_no_dvc
                    },
                    noDvcHeadroomDb,
                ),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 13.sp,
            )
        }

        Spacer(Modifier.height(12.dp))

        SettingsCard {
            SectionHeader(stringResource(R.string.settings_dvc_behavior_section))
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.settings_audio_dvc_info),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 13.sp,
                lineHeight = 20.sp,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.settings_dvc_usb_notice),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 13.sp,
                lineHeight = 20.sp,
            )
        }
    }
}
