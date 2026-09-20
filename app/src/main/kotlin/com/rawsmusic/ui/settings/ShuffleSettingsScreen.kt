package com.rawsmusic.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.rawsmusic.R
import com.rawsmusic.module.data.prefs.AppPreferences
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import kotlin.math.roundToInt

@Composable
fun ShuffleSettingsScreen(onBack: () -> Unit) {
    var samplingStep by remember {
        mutableFloatStateOf((AppPreferences.Player.shuffleRandomFactor * 6f).roundToInt().toFloat())
    }
    var noReshuffle by remember { mutableStateOf(AppPreferences.Player.noReshuffle) }
    var noReshuffleLarge by remember { mutableStateOf(AppPreferences.Player.noReshuffleForLargeLists) }
    var categoryMode by remember { mutableIntStateOf(AppPreferences.Player.categoryShuffleMode) }
    var entireHierarchy by remember { mutableStateOf(AppPreferences.Player.shuffleEntireFolderHierarchy) }

    SettingsPage(title = stringResource(R.string.settings_shuffle_title), onBack = onBack) {
        SmallTitle(text = stringResource(R.string.settings_shuffle_randomization_section))
        SettingsCardGroup {
            SliderPreference(
                title = stringResource(R.string.settings_shuffle_randomization_title),
                summary = stringResource(R.string.settings_shuffle_randomization_summary),
                valueText = "${(samplingStep / 6f * 100f).roundToInt()}%",
                value = samplingStep,
                onValueChange = { value ->
                    samplingStep = value.roundToInt().coerceIn(0, 6).toFloat()
                    AppPreferences.Player.shuffleRandomFactor = samplingStep / 6f
                },
                valueRange = 0f..6f,
                steps = 5,
                hapticEffect = SliderDefaults.SliderHapticEffect.Step,
            )
            SettingsInfoEntry(
                title = stringResource(R.string.settings_shuffle_less_random),
                description = stringResource(R.string.settings_shuffle_less_random_hint),
            )
            SettingsInfoEntry(
                title = stringResource(R.string.settings_shuffle_full_random),
                description = stringResource(R.string.settings_shuffle_full_random_hint),
            )
            SettingsInfoEntry(
                title = stringResource(R.string.settings_shuffle_next_session_title),
                description = stringResource(R.string.settings_shuffle_next_session_hint),
            )
        }

        SmallTitle(text = stringResource(R.string.settings_shuffle_session_section))
        SettingsCardGroup {
            SwitchPreference(
                title = stringResource(R.string.settings_shuffle_no_reshuffle_title),
                summary = stringResource(R.string.settings_shuffle_no_reshuffle_summary),
                checked = noReshuffle,
                onCheckedChange = {
                    noReshuffle = it
                    AppPreferences.Player.noReshuffle = it
                },
            )
            SwitchPreference(
                title = stringResource(R.string.settings_shuffle_large_title),
                summary = stringResource(R.string.settings_shuffle_large_summary),
                checked = noReshuffleLarge,
                onCheckedChange = {
                    noReshuffleLarge = it
                    AppPreferences.Player.noReshuffleForLargeLists = it
                },
            )
        }

        SmallTitle(text = stringResource(R.string.settings_shuffle_category_section))
        SettingsCardGroup {
            RadioButtonPreference(
                title = stringResource(R.string.settings_shuffle_songs_categories),
                summary = stringResource(R.string.settings_shuffle_songs_categories_summary),
                selected = categoryMode == 4,
                onClick = { categoryMode = 4; AppPreferences.Player.categoryShuffleMode = 4 },
            )
            RadioButtonPreference(
                title = stringResource(R.string.settings_shuffle_categories_only),
                summary = stringResource(R.string.settings_shuffle_categories_only_summary),
                selected = categoryMode == 3,
                onClick = { categoryMode = 3; AppPreferences.Player.categoryShuffleMode = 3 },
            )
            RadioButtonPreference(
                title = stringResource(R.string.settings_shuffle_songs_only),
                summary = stringResource(R.string.settings_shuffle_songs_only_summary),
                selected = categoryMode == 2,
                onClick = { categoryMode = 2; AppPreferences.Player.categoryShuffleMode = 2 },
            )
            SettingsInfoEntry(
                title = stringResource(R.string.settings_shuffle_category_note_title),
                description = stringResource(R.string.settings_shuffle_category_note),
            )
        }

        SmallTitle(text = stringResource(R.string.settings_shuffle_folder_section))
        SettingsCardGroup {
            SwitchPreference(
                title = stringResource(R.string.settings_shuffle_hierarchy_title),
                summary = stringResource(R.string.settings_shuffle_hierarchy_summary),
                checked = entireHierarchy,
                onCheckedChange = {
                    entireHierarchy = it
                    AppPreferences.Player.shuffleEntireFolderHierarchy = it
                },
            )
        }

        SmallTitle(text = stringResource(R.string.settings_shuffle_reset_section))
        SettingsCardGroup {
            SettingsNavigationEntry(
                title = stringResource(R.string.settings_shuffle_reset_title),
                description = stringResource(R.string.settings_shuffle_reset_summary),
                onClick = {
                    samplingStep = 3f
                    noReshuffle = false
                    noReshuffleLarge = true
                    categoryMode = 4
                    entireHierarchy = true
                    AppPreferences.Player.shuffleRandomFactor = 0.5f
                    AppPreferences.Player.noReshuffle = false
                    AppPreferences.Player.noReshuffleForLargeLists = true
                    AppPreferences.Player.categoryShuffleMode = 4
                    AppPreferences.Player.shuffleEntireFolderHierarchy = true
                },
            )
        }
    }
}
