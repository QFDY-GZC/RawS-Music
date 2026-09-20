package com.rawsmusic.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.rawsmusic.R
import com.rawsmusic.module.data.prefs.PersonalizationPreferences
import com.rawsmusic.module.data.prefs.TopChromeStyle
import com.rawsmusic.module.data.prefs.LibraryBottomButtonsLayout
import com.rawsmusic.module.data.prefs.LibraryBottomButtonsMode
import com.rawsmusic.core.ui.widget.RawWindowDropdownPreference
import com.rawsmusic.core.ui.scene.pages.HomeCardSettingsDialog
import com.rawsmusic.core.ui.scene.pages.rememberHomeCardLayoutState
import com.rawsmusic.core.ui.scene.pages.rememberHomeHeaderOptionsState
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
fun PersonalizationSettingsScreen(onBack: () -> Unit) {
    val activity = LocalContext.current as? BaseSettingsActivity
    val homeCardLayoutState = rememberHomeCardLayoutState()
    val homeHeaderOptions = rememberHomeHeaderOptionsState()
    var showHomeCardSettings by remember { mutableStateOf(false) }
    var predictiveBackEnabled by remember {
        mutableStateOf(PersonalizationPreferences.predictiveBackAnimationEnabled)
    }
    var performanceModeEnabled by remember {
        mutableStateOf(PersonalizationPreferences.performanceModeEnabled)
    }
    val topChromeStyle by PersonalizationPreferences.topChromeStyle.collectAsState()
    val libraryHeaderButtonsEnabled by PersonalizationPreferences.libraryHeaderButtonsEnabled.collectAsState()
    val libraryBottomButtonsMode by PersonalizationPreferences.libraryBottomButtonsMode.collectAsState()
    val libraryBottomButtonsLayout by PersonalizationPreferences.libraryBottomButtonsLayout.collectAsState()
    val alphabetIndexHidden by PersonalizationPreferences.alphabetIndexHidden.collectAsState()
    val topChromeHazeTitle = stringResource(R.string.settings_top_chrome_style_haze)
    val topChromeHazeSummary = stringResource(R.string.settings_top_chrome_style_haze_summary)
    val topChromeTransparentTitle = stringResource(R.string.settings_top_chrome_style_transparent)
    val topChromeTransparentSummary = stringResource(R.string.settings_top_chrome_style_transparent_summary)
    val topChromeFloatingTitle = stringResource(R.string.settings_top_chrome_style_floating)
    val topChromeFloatingSummary = stringResource(R.string.settings_top_chrome_style_floating_summary)
    val bottomButtonsDisabledText = stringResource(R.string.settings_library_bottom_buttons_disabled)
    val bottomButtonsSemiTransparentText = stringResource(R.string.settings_library_bottom_buttons_semi_transparent)
    val bottomButtonsEnabledText = stringResource(R.string.settings_library_bottom_buttons_enabled)
    val bottomButtonsHorizontalText = stringResource(R.string.settings_library_bottom_buttons_horizontal)
    val bottomButtonsVerticalText = stringResource(R.string.settings_library_bottom_buttons_vertical)
    val floatingChromeSelected =
        topChromeStyle == TopChromeStyle.FLOATING || topChromeStyle == TopChromeStyle.FLOATING_VERTICAL
    val topChromeStyleEntry = remember(
        topChromeStyle,
        topChromeHazeTitle,
        topChromeHazeSummary,
        topChromeTransparentTitle,
        topChromeTransparentSummary,
        topChromeFloatingTitle,
        topChromeFloatingSummary,
    ) {
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
                    selected = topChromeStyle == TopChromeStyle.FLOATING ||
                        topChromeStyle == TopChromeStyle.FLOATING_VERTICAL,
                    onClick = { PersonalizationPreferences.topChromeStyleValue = TopChromeStyle.FLOATING },
                ),
            )
        )
    }
    val bottomButtonsModeEntry = remember(
        libraryBottomButtonsMode,
        bottomButtonsDisabledText,
        bottomButtonsSemiTransparentText,
        bottomButtonsEnabledText,
    ) {
        DropdownEntry(
            items = listOf(
                DropdownItem(
                    text = bottomButtonsDisabledText,
                    selected = libraryBottomButtonsMode == LibraryBottomButtonsMode.DISABLED,
                    onClick = { PersonalizationPreferences.libraryBottomButtonsModeValue = LibraryBottomButtonsMode.DISABLED },
                ),
                DropdownItem(
                    text = bottomButtonsSemiTransparentText,
                    selected = libraryBottomButtonsMode == LibraryBottomButtonsMode.SEMI_TRANSPARENT,
                    onClick = { PersonalizationPreferences.libraryBottomButtonsModeValue = LibraryBottomButtonsMode.SEMI_TRANSPARENT },
                ),
                DropdownItem(
                    text = bottomButtonsEnabledText,
                    selected = libraryBottomButtonsMode == LibraryBottomButtonsMode.ENABLED,
                    onClick = { PersonalizationPreferences.libraryBottomButtonsModeValue = LibraryBottomButtonsMode.ENABLED },
                ),
            )
        )
    }
    val bottomButtonsLayoutEntry = remember(
        libraryBottomButtonsLayout,
        bottomButtonsHorizontalText,
        bottomButtonsVerticalText,
    ) {
        DropdownEntry(
            items = listOf(
                DropdownItem(
                    text = bottomButtonsHorizontalText,
                    selected = libraryBottomButtonsLayout == LibraryBottomButtonsLayout.HORIZONTAL,
                    onClick = { PersonalizationPreferences.libraryBottomButtonsLayoutValue = LibraryBottomButtonsLayout.HORIZONTAL },
                ),
                DropdownItem(
                    text = bottomButtonsVerticalText,
                    selected = libraryBottomButtonsLayout == LibraryBottomButtonsLayout.VERTICAL,
                    onClick = { PersonalizationPreferences.libraryBottomButtonsLayoutValue = LibraryBottomButtonsLayout.VERTICAL },
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
            SwitchPreference(
                title = stringResource(R.string.settings_library_header_buttons_title),
                summary = stringResource(R.string.settings_library_header_buttons_summary),
                checked = libraryHeaderButtonsEnabled,
                onCheckedChange = { PersonalizationPreferences.libraryHeaderButtonsEnabledValue = it },
            )
            if (floatingChromeSelected) {
                RawWindowDropdownPreference(
                    entry = bottomButtonsModeEntry,
                    title = stringResource(R.string.settings_library_bottom_buttons_title),
                    summary = stringResource(R.string.settings_library_bottom_buttons_summary),
                    maxHeight = 300.dp,
                )
                RawWindowDropdownPreference(
                    entry = bottomButtonsLayoutEntry,
                    title = stringResource(R.string.settings_library_bottom_buttons_layout_title),
                    summary = stringResource(R.string.settings_library_bottom_buttons_layout_summary),
                    maxHeight = 240.dp,
                )
            }
        }

        SmallTitle(text = stringResource(R.string.settings_personalization_layout_section))
        SettingsCardGroup {
            SwitchPreference(
                title = stringResource(com.rawsmusic.core.ui.R.string.home_header_carousel_gesture_lock_title),
                summary = stringResource(com.rawsmusic.core.ui.R.string.home_header_carousel_gesture_lock_summary),
                checked = homeHeaderOptions.carouselGestureLocked,
                onCheckedChange = homeHeaderOptions::updateCarouselGestureLocked,
            )
            SwitchPreference(
                title = stringResource(com.rawsmusic.core.ui.R.string.home_most_played_disable_title),
                summary = stringResource(com.rawsmusic.core.ui.R.string.home_most_played_disable_summary),
                checked = !homeHeaderOptions.mostPlayedVisible,
                onCheckedChange = { hidden ->
                    homeHeaderOptions.updateMostPlayedVisible(!hidden)
                },
            )
            SwitchPreference(
                title = stringResource(R.string.settings_hide_alphabet_index_title),
                summary = stringResource(R.string.settings_hide_alphabet_index_summary),
                checked = alphabetIndexHidden,
                onCheckedChange = { PersonalizationPreferences.isAlphabetIndexHidden = it },
            )
            SettingsNavigationEntry(
                title = stringResource(R.string.settings_bottom_navigation_title),
                description = stringResource(R.string.settings_bottom_navigation_summary),
                iconRes = R.drawable.ic_settings_player_ui_ali,
                onClick = {
                    activity?.navigateToSettings(BottomNavigationSettingsActivity::class.java)
                }
            )
            SettingsNavigationEntry(
                title = stringResource(com.rawsmusic.core.ui.R.string.home_card_settings_title),
                description = stringResource(com.rawsmusic.core.ui.R.string.home_card_settings_entry_summary),
                iconRes = R.drawable.ic_settings_personalization,
                onClick = { showHomeCardSettings = true },
            )
        }
    }

    HomeCardSettingsDialog(
        show = showHomeCardSettings,
        state = homeCardLayoutState,
        onDismissRequest = { showHomeCardSettings = false },
        // Settings runs in its own Activity without the app root scaffold host. Render the dialog
        // in this window instead of targeting the main-player scaffold, otherwise the row appears
        // clickable but no popup becomes visible.
        renderInRootScaffold = false,
    )
}
