package com.rawsmusic.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import com.rawsmusic.R
import com.rawsmusic.module.data.prefs.PersonalizationPreferences
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
fun PersonalizationSettingsScreen(onBack: () -> Unit) {
    val activity = LocalContext.current as? BaseSettingsActivity
    var predictiveBackEnabled by remember {
        mutableStateOf(PersonalizationPreferences.predictiveBackAnimationEnabled)
    }
    var performanceModeEnabled by remember {
        mutableStateOf(PersonalizationPreferences.performanceModeEnabled)
    }

    SettingsPage(title = stringResource(R.string.settings_personalization_title), onBack = onBack) {
        SmallTitle(text = stringResource(R.string.settings_personalization_motion_section))
        SettingsCardGroup {
            SwitchPreference(
                title = stringResource(R.string.settings_predictive_back_title),
                summary = stringResource(R.string.settings_predictive_back_summary),
                checked = predictiveBackEnabled,
                onCheckedChange = {
                    predictiveBackEnabled = it
                    PersonalizationPreferences.predictiveBackAnimationEnabled = it
                    activity?.refreshPredictiveBackPreference()
                }
            )
            SwitchPreference(
                title = stringResource(R.string.settings_performance_mode_title),
                summary = stringResource(R.string.settings_performance_mode_summary),
                checked = performanceModeEnabled,
                onCheckedChange = {
                    performanceModeEnabled = it
                    PersonalizationPreferences.performanceModeEnabled = it
                }
            )
        }

        SmallTitle(text = stringResource(R.string.settings_personalization_layout_section))
        SettingsCardGroup {
            SettingsNavigationEntry(
                title = stringResource(R.string.settings_bottom_navigation_title),
                description = stringResource(R.string.settings_bottom_navigation_summary),
                onClick = {
                    activity?.navigateToSettings(BottomNavigationSettingsActivity::class.java)
                }
            )
        }
    }
}
