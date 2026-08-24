package com.rawsmusic.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.rawsmusic.R
import com.rawsmusic.module.data.prefs.PersonalizationPreferences
import com.rawsmusic.module.data.prefs.TopChromeStyle
import com.rawsmusic.core.ui.widget.RawWindowDropdownPreference
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
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
    val topChromeStyle by PersonalizationPreferences.topChromeStyle.collectAsState()
    val topChromeHazeTitle = stringResource(R.string.settings_top_chrome_style_haze)
    val topChromeHazeSummary = stringResource(R.string.settings_top_chrome_style_haze_summary)
    val topChromeTransparentTitle = stringResource(R.string.settings_top_chrome_style_transparent)
    val topChromeTransparentSummary = stringResource(R.string.settings_top_chrome_style_transparent_summary)
    val topChromeFloatingTitle = stringResource(R.string.settings_top_chrome_style_floating)
    val topChromeFloatingSummary = stringResource(R.string.settings_top_chrome_style_floating_summary)
    val topChromeFloatingVerticalTitle = stringResource(R.string.settings_top_chrome_style_floating_vertical)
    val topChromeFloatingVerticalSummary = stringResource(R.string.settings_top_chrome_style_floating_vertical_summary)
    val topChromeStyleEntry = remember(topChromeStyle) {
        DropdownEntry(
            items = listOf(
                DropdownItem(
                    text = topChromeHazeTitle,
                    summary = topChromeHazeSummary,
                    selected = topChromeStyle == TopChromeStyle.HAZE,
                    onClick = { PersonalizationPreferences.topChromeStyleValue = TopChromeStyle.HAZE },
                ),
                DropdownItem(
                    text = topChromeTransparentTitle,
                    summary = topChromeTransparentSummary,
                    selected = topChromeStyle == TopChromeStyle.TRANSPARENT,
                    onClick = { PersonalizationPreferences.topChromeStyleValue = TopChromeStyle.TRANSPARENT },
                ),
                DropdownItem(
                    text = topChromeFloatingTitle,
                    summary = topChromeFloatingSummary,
                    selected = topChromeStyle == TopChromeStyle.FLOATING,
                    onClick = { PersonalizationPreferences.topChromeStyleValue = TopChromeStyle.FLOATING },
                ),
                DropdownItem(
                    text = topChromeFloatingVerticalTitle,
                    summary = topChromeFloatingVerticalSummary,
                    selected = topChromeStyle == TopChromeStyle.FLOATING_VERTICAL,
                    onClick = { PersonalizationPreferences.topChromeStyleValue = TopChromeStyle.FLOATING_VERTICAL },
                ),
            )
        )
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
            RawWindowDropdownPreference(
                entry = topChromeStyleEntry,
                title = stringResource(R.string.settings_top_chrome_style_title),
                summary = stringResource(R.string.settings_top_chrome_style_summary),
                maxHeight = 380.dp,
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
