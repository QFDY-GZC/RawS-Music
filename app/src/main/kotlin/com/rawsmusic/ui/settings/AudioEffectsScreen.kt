package com.rawsmusic.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.module.data.prefs.AppPreferences
import androidx.compose.ui.res.stringResource
import com.rawsmusic.R

@Composable
fun LiquidGlassAudioEffectsScreen(
    onNavigateToPEQ: () -> Unit,
    onNavigateToGraphicEQ: () -> Unit,
    onNavigateToSpatialSound: () -> Unit,
    onNavigateToCompressor: () -> Unit,
    onNavigateToBassTreble: () -> Unit,
    onNavigateToSurround360: () -> Unit,
    onNavigateToPanoramic360: () -> Unit,
    onTogglePEQ: (Boolean) -> Unit,
    onToggleCompressor: (Boolean) -> Unit,
    onToggleSurround360: (Boolean) -> Unit,
    onTogglePanoramic360: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    var peqEnabled by remember { mutableStateOf(AppPreferences.PEQ.isEnabled) }
    var compressorEnabled by remember { mutableStateOf(AppPreferences.Compressor.isEnabled) }
    SettingsPage(title = stringResource(R.string.settings_audio_effects_title), onBack = onBack) {
        SettingsSection(stringResource(R.string.settings_effects_eq)) {
            SwitchRow(stringResource(R.string.settings_effects_enable_peq), peqEnabled) { checked ->
                peqEnabled = checked
                onTogglePEQ(checked)
            }
            SettingsNavigationEntry(
                title = stringResource(R.string.settings_effects_peq_title),
                description = stringResource(R.string.settings_effects_peq_desc),
                onClick = onNavigateToPEQ
            )
            SettingsNavigationEntry(
                title = stringResource(R.string.settings_effects_graphic_eq_title),
                description = stringResource(R.string.settings_effects_graphic_eq_desc),
                onClick = onNavigateToGraphicEQ
            )
        }

        SettingsSection(stringResource(R.string.settings_effects_dynamic)) {
            SwitchRow(stringResource(R.string.settings_effects_enable_compressor), compressorEnabled) { checked ->
                compressorEnabled = checked
                onToggleCompressor(checked)
            }
            SettingsNavigationEntry(
                title = stringResource(R.string.settings_effects_compressor_title),
                description = stringResource(R.string.settings_effects_compressor_desc),
                onClick = onNavigateToCompressor
            )
        }

        SettingsSection(stringResource(R.string.settings_effects_frequency)) {
            SettingsNavigationEntry(
                title = stringResource(R.string.settings_effects_bass_treble_title),
                description = stringResource(R.string.settings_effects_bass_treble_desc),
                onClick = onNavigateToBassTreble
            )
        }

        SettingsSection(stringResource(R.string.settings_effects_space)) {
            SettingsNavigationEntry(
                title = stringResource(R.string.settings_effects_surround360_title),
                description = stringResource(R.string.settings_effects_surround360_desc),
                onClick = onNavigateToSurround360
            )
            SettingsNavigationEntry(
                title = stringResource(R.string.settings_effects_panoramic360_title),
                description = stringResource(R.string.settings_effects_panoramic360_desc),
                onClick = onNavigateToPanoramic360
            )
        }

        SettingsSection(stringResource(R.string.settings_effects_stereo)) {
            SettingsNavigationEntry(
                title = stringResource(R.string.settings_effects_spatial_title),
                description = stringResource(R.string.settings_effects_spatial_desc),
                onClick = onNavigateToSpatialSound
            )
        }
    }
}
