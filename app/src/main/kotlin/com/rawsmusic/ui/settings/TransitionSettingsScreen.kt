package com.rawsmusic.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.R
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.data.prefs.TransitionPreferences
import com.rawsmusic.ui.songs.PlayerHolder
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun TransitionSettingsScreen(onBack: () -> Unit) {
    var expandedCard by remember { mutableStateOf(TransitionCard.Manual) }
    var manualMode by remember { mutableStateOf(TransitionPreferences.manualTrackTransitionMode) }
    var manualDurationMs by remember { mutableStateOf(TransitionPreferences.manualTrackFadeMs) }
    var crossfadeSec by remember { mutableStateOf(AppPreferences.Player.crossfadeDuration) }
    var transportEnabled by remember { mutableStateOf(TransitionPreferences.transportFadeEnabled) }
    var transportDurationMs by remember { mutableStateOf(TransitionPreferences.transportFadeMs) }
    var seekEnabled by remember { mutableStateOf(TransitionPreferences.seekFadeEnabled) }
    var seekDurationMs by remember { mutableStateOf(TransitionPreferences.seekFadeMs) }

    fun notifyChanged() {
        PlayerHolder.controller?.applyTransitionSettingsChanged()
    }

    SettingsPage(title = stringResource(R.string.settings_transition_title), onBack = onBack) {
        SmallTitle(text = stringResource(R.string.transition_group_playback))

        ExpandableTransitionCard(
            title = stringResource(R.string.transition_manual_title),
            summary = stringResource(R.string.transition_manual_summary),
            expanded = expandedCard == TransitionCard.Manual,
            onClick = { expandedCard = expandedCard.toggle(TransitionCard.Manual) }
        ) {
            ManualModePreference(
                mode = TransitionPreferences.ManualTrackTransitionMode.NONE,
                selected = manualMode == TransitionPreferences.ManualTrackTransitionMode.NONE,
                title = stringResource(R.string.transition_manual_none_title),
                summary = stringResource(R.string.transition_manual_none_desc),
                onSelect = {
                    manualMode = it
                    TransitionPreferences.manualTrackTransitionMode = it
                    notifyChanged()
                }
            )
            ManualModePreference(
                mode = TransitionPreferences.ManualTrackTransitionMode.SHORT_FADE,
                selected = manualMode == TransitionPreferences.ManualTrackTransitionMode.SHORT_FADE,
                title = stringResource(R.string.transition_manual_short_title),
                summary = stringResource(R.string.transition_manual_short_desc),
                onSelect = {
                    manualMode = it
                    TransitionPreferences.manualTrackTransitionMode = it
                    notifyChanged()
                }
            )
            ManualModePreference(
                mode = TransitionPreferences.ManualTrackTransitionMode.CROSSFADE,
                selected = manualMode == TransitionPreferences.ManualTrackTransitionMode.CROSSFADE,
                title = stringResource(R.string.transition_manual_crossfade_title),
                summary = stringResource(R.string.transition_manual_crossfade_desc),
                onSelect = {
                    manualMode = it
                    TransitionPreferences.manualTrackTransitionMode = it
                    notifyChanged()
                }
            )
            Spacer(Modifier.height(8.dp))
            DurationSlider(
                title = stringResource(R.string.transition_manual_duration_title),
                description = stringResource(R.string.transition_manual_duration_desc),
                valueMs = manualDurationMs,
                minMs = TransitionPreferences.MANUAL_DURATION_MIN_MS,
                maxMs = TransitionPreferences.MANUAL_DURATION_MAX_MS,
                stepMs = 10,
                enabled = manualMode != TransitionPreferences.ManualTrackTransitionMode.NONE,
                onValueChange = {
                    manualDurationMs = it
                    TransitionPreferences.manualTrackFadeMs = it
                    notifyChanged()
                }
            )
        }

        ExpandableTransitionCard(
            title = stringResource(R.string.settings_audio_info_crossfade_title),
            summary = stringResource(R.string.settings_audio_info_crossfade_body),
            expanded = expandedCard == TransitionCard.Crossfade,
            onClick = { expandedCard = expandedCard.toggle(TransitionCard.Crossfade) }
        ) {
            SliderPreference(
                title = stringResource(R.string.settings_audio_info_crossfade_title),
                summary = stringResource(R.string.settings_audio_info_crossfade_body),
                valueText = if (crossfadeSec == 0) {
                    stringResource(R.string.settings_audio_crossfade_off)
                } else {
                    stringResource(R.string.settings_audio_crossfade_seconds, crossfadeSec)
                },
                value = crossfadeSec.toFloat(),
                onValueChange = { value ->
                    crossfadeSec = value.toInt()
                    AppPreferences.Player.crossfadeDuration = crossfadeSec
                },
                valueRange = 0f..12f,
                steps = 11,
                hapticEffect = SliderDefaults.SliderHapticEffect.Step
            )
        }

        ExpandableTransitionCard(
            title = stringResource(R.string.transition_transport_title),
            summary = stringResource(R.string.transition_transport_summary),
            expanded = expandedCard == TransitionCard.Transport,
            onClick = { expandedCard = expandedCard.toggle(TransitionCard.Transport) }
        ) {
            SwitchPreference(
                title = stringResource(R.string.transition_transport_enable_title),
                summary = stringResource(R.string.transition_transport_enable_desc),
                checked = transportEnabled,
                onCheckedChange = {
                    transportEnabled = it
                    TransitionPreferences.transportFadeEnabled = it
                    notifyChanged()
                }
            )
            Spacer(Modifier.height(8.dp))
            DurationSlider(
                title = stringResource(R.string.transition_transport_duration_title),
                description = stringResource(R.string.transition_transport_duration_desc),
                valueMs = transportDurationMs,
                minMs = TransitionPreferences.TRANSPORT_DURATION_MIN_MS,
                maxMs = TransitionPreferences.TRANSPORT_DURATION_MAX_MS,
                stepMs = 10,
                enabled = transportEnabled,
                onValueChange = {
                    transportDurationMs = it
                    TransitionPreferences.transportFadeMs = it
                    notifyChanged()
                }
            )
        }

        ExpandableTransitionCard(
            title = stringResource(R.string.transition_seek_title),
            summary = stringResource(R.string.transition_seek_summary),
            expanded = expandedCard == TransitionCard.Seek,
            onClick = { expandedCard = expandedCard.toggle(TransitionCard.Seek) }
        ) {
            SwitchPreference(
                title = stringResource(R.string.transition_seek_enable_title),
                summary = stringResource(R.string.transition_seek_enable_desc),
                checked = seekEnabled,
                onCheckedChange = {
                    seekEnabled = it
                    TransitionPreferences.seekFadeEnabled = it
                    notifyChanged()
                }
            )
            Spacer(Modifier.height(8.dp))
            DurationSlider(
                title = stringResource(R.string.transition_seek_duration_title),
                description = stringResource(R.string.transition_seek_duration_desc),
                valueMs = seekDurationMs,
                minMs = TransitionPreferences.SEEK_DURATION_MIN_MS,
                maxMs = TransitionPreferences.SEEK_DURATION_MAX_MS,
                stepMs = 10,
                enabled = seekEnabled,
                onValueChange = {
                    seekDurationMs = it
                    TransitionPreferences.seekFadeMs = it
                    notifyChanged()
                }
            )
        }
    }
}

@Composable
private fun ExpandableTransitionCard(
    title: String,
    summary: String,
    expanded: Boolean,
    onClick: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    SettingsCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onBackground
                )
                Text(
                    text = summary,
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Text(
                text = if (expanded) stringResource(R.string.transition_card_collapse) else stringResource(R.string.transition_card_expand),
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.primary
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
            ) {
                content()
            }
        }
    }
}

@Composable
private fun ManualModePreference(
    mode: TransitionPreferences.ManualTrackTransitionMode,
    selected: Boolean,
    title: String,
    summary: String,
    onSelect: (TransitionPreferences.ManualTrackTransitionMode) -> Unit
) {
    RadioButtonPreference(
        title = title,
        summary = summary,
        selected = selected,
        onClick = { onSelect(mode) }
    )
}

@Composable
private fun DurationSlider(
    title: String,
    description: String,
    valueMs: Int,
    minMs: Int,
    maxMs: Int,
    stepMs: Int,
    enabled: Boolean,
    onValueChange: (Int) -> Unit
) {
    val sliderSteps = ((maxMs - minMs) / stepMs - 1).coerceAtLeast(0)
    val keyPoints = remember(minMs, maxMs, stepMs) {
        buildList {
            add(minMs.toFloat())
            add(((minMs + maxMs) / 2).toFloat())
            add(maxMs.toFloat())
        }
    }
    SliderPreference(
        value = valueMs.toFloat(),
        onValueChange = { raw ->
            val rounded = ((raw.roundToInt() / stepMs) * stepMs).coerceIn(minMs, maxMs)
            onValueChange(rounded)
        },
        title = title,
        summary = description + "\n" + stringResource(R.string.transition_duration_range, minMs, maxMs),
        valueText = stringResource(R.string.transition_duration_ms_value, valueMs),
        enabled = enabled,
        valueRange = minMs.toFloat()..maxMs.toFloat(),
        steps = sliderSteps,
        hapticEffect = SliderDefaults.SliderHapticEffect.Step,
        showKeyPoints = true,
        keyPoints = keyPoints
    )
}

private enum class TransitionCard {
    Manual,
    Crossfade,
    Transport,
    Seek
}

private fun TransitionCard.toggle(target: TransitionCard): TransitionCard =
    if (this == target) this else target
