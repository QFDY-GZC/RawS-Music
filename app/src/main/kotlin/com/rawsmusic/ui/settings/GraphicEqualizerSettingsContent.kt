package com.rawsmusic.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.R
import com.rawsmusic.module.player.dsp.GraphicEQController
import com.rawsmusic.module.player.dsp.PEQFilter
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.VerticalSlider
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs
import kotlin.math.roundToInt

private val graphicEqExpandEnter = expandVertically() + fadeIn()
private val graphicEqExpandExit = shrinkVertically() + fadeOut()
private val graphicEqBandCounts = listOf(10, 31, 40)
private const val GRAPHIC_EQ_MIN_GAIN = -12f
private const val GRAPHIC_EQ_MAX_GAIN = 12f

/**
 * MIUIX graphic equalizer content shared by the workspace and the legacy route.
 *
 * Band-count and preset selectors use window-level MIUIX dropdown preferences so
 * they work inside the pager workspace without a MIUIX Scaffold overlay host. The
 * frequency bands remain one horizontally scrollable bank of vertical sliders.
 */
@Composable
internal fun GraphicEqualizerSettingsContent(
    controller: GraphicEQController?,
    showSectionHeader: Boolean = true
) {
    if (showSectionHeader) {
        SectionHeader(stringResource(R.string.settings_effects_graphic_eq_title))
    }

    if (controller == null) {
        SettingsCard {
            SwitchPreference(
                title = stringResource(R.string.settings_effects_graphic_eq_title),
                summary = stringResource(R.string.settings_graphic_eq_engine_unavailable),
                checked = false,
                enabled = false,
                onCheckedChange = {}
            )
        }
        return
    }

    val enabled by controller.isEnabled.collectAsState()
    val bandCount by controller.bandCount.collectAsState()
    val preampDb by controller.preamp.collectAsState()
    val gains by controller.gains.collectAsState()
    val presetName by controller.presetName.collectAsState()
    val presets by controller.presets.collectAsState()
    val frequencies = PEQFilter.defaultFreqsForCount(bandCount).toList()

    LaunchedEffect(controller, bandCount) {
        controller.refreshFromParametricState()
    }

    SettingsCard {
        SwitchPreference(
            title = stringResource(R.string.settings_effects_graphic_eq_title),
            summary = stringResource(
                R.string.settings_graphic_eq_summary,
                bandCount,
                localizedGraphicPresetName(presetName)
            ),
            checked = enabled,
            onCheckedChange = controller::setEnabled
        )

        AnimatedVisibility(
            visible = enabled,
            enter = graphicEqExpandEnter,
            exit = graphicEqExpandExit
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
            ) {
                SliderPreference(
                    title = stringResource(R.string.settings_peq_preamp),
                    summary = stringResource(R.string.settings_graphic_eq_preamp_desc),
                    valueText = stringResource(
                        R.string.settings_db_value_signed_one_decimal,
                        preampDb
                    ),
                    value = preampDb.coerceIn(GRAPHIC_EQ_MIN_GAIN, GRAPHIC_EQ_MAX_GAIN),
                    onValueChange = controller::setPreamp,
                    valueRange = GRAPHIC_EQ_MIN_GAIN..GRAPHIC_EQ_MAX_GAIN,
                    steps = 47,
                    hapticEffect = SliderDefaults.SliderHapticEffect.Step
                )

                GraphicEqDropdownPreference(
                    title = stringResource(R.string.settings_peq_band_count),
                    summary = stringResource(R.string.settings_graphic_eq_band_count_dropdown_desc),
                    entries = graphicEqBandCounts.map { count ->
                        GraphicEqDropdownEntry(
                            key = "bands-$count",
                            title = stringResource(R.string.settings_peq_band_count_value, count),
                            selected = bandCount == count,
                            onClick = { controller.setBandCount(count) }
                        )
                    }
                )

                GraphicEqDropdownPreference(
                    title = stringResource(R.string.settings_graphic_eq_presets),
                    summary = stringResource(
                        R.string.settings_graphic_eq_preset_dropdown_desc,
                        localizedGraphicPresetName(presetName)
                    ),
                    entries = presets.map { preset ->
                        GraphicEqDropdownEntry(
                            key = "preset-${preset.name}",
                            title = localizedGraphicPresetName(preset.name),
                            summary = stringResource(
                                R.string.settings_peq_band_count_value,
                                preset.bandCount
                            ),
                            selected = presetName == preset.name,
                            onClick = { controller.applyPreset(preset) }
                        )
                    }
                )

                SectionHeader(stringResource(R.string.settings_graphic_eq_frequency_bands))
                GraphicEqVerticalBandBank(
                    frequencies = frequencies,
                    gains = gains,
                    onGainChange = controller::setGain,
                    onResetBand = controller::resetBand
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        text = stringResource(R.string.settings_graphic_eq_reset_flat),
                        onClick = controller::resetToFlat
                    )
                }
            }
        }
    }
}

private data class GraphicEqDropdownEntry(
    val key: String,
    val title: String,
    val summary: String? = null,
    val selected: Boolean = false,
    val onClick: () -> Unit
)

@Composable
private fun GraphicEqDropdownPreference(
    title: String,
    summary: String,
    entries: List<GraphicEqDropdownEntry>
) {
    val dropdownEntry = remember(entries) {
        DropdownEntry(
            items = entries.map { item ->
                DropdownItem(
                    text = item.title,
                    summary = item.summary,
                    selected = item.selected,
                    onClick = item.onClick
                )
            }
        )
    }

    WindowDropdownPreference(
        entry = dropdownEntry,
        title = title,
        summary = summary,
        enabled = entries.isNotEmpty(),
        showValue = entries.any { it.selected },
        maxHeight = 430.dp,
        collapseOnSelection = true
    )
}

@Composable
private fun GraphicEqVerticalBandBank(
    frequencies: List<Float>,
    gains: List<Float>,
    onGainChange: (Int, Float) -> Unit,
    onResetBand: (Int) -> Unit
) {
    val colors = MiuixTheme.colorScheme
    val guideColor = colors.outline.copy(alpha = 0.18f)

    // Keep the slider bank directly on the page instead of wrapping it in another
    // surface card. This leaves more horizontal room for bands while retaining the
    // shared dB scale and zero-reference guide.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(278.dp)
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(204.dp)
                .padding(start = 34.dp, end = 4.dp, top = 47.dp)
        ) {
            drawLine(
                color = guideColor,
                start = Offset(0f, size.height / 2f),
                end = Offset(size.width, size.height / 2f),
                strokeWidth = 1.dp.toPx()
            )
        }

        Row(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .width(34.dp)
                    .height(238.dp)
                    .padding(top = 41.dp, bottom = 17.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.End
            ) {
                Text(
                    text = "+12",
                    color = colors.onSurfaceVariantSummary,
                    fontSize = 9.sp,
                    modifier = Modifier.padding(end = 2.dp)
                )
                Text(
                    text = "0",
                    color = colors.onSurfaceVariantSummary,
                    fontSize = 9.sp,
                    modifier = Modifier.padding(end = 2.dp)
                )
                Text(
                    text = "-12",
                    color = colors.onSurfaceVariantSummary,
                    fontSize = 9.sp,
                    modifier = Modifier.padding(end = 2.dp)
                )
            }

            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(278.dp),
                contentPadding = PaddingValues(start = 0.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(0.dp),
                verticalAlignment = Alignment.Top
            ) {
                itemsIndexed(
                    items = gains,
                    key = { index, _ -> index }
                ) { index, gain ->
                    GraphicEqVerticalBand(
                        frequency = frequencies.getOrElse(index) { 1000f },
                        gain = gain,
                        onGainChange = { onGainChange(index, it) },
                        onReset = { onResetBand(index) }
                    )
                }
            }
        }
    }
}

@Composable
private fun GraphicEqVerticalBand(
    frequency: Float,
    gain: Float,
    onGainChange: (Float) -> Unit,
    onReset: () -> Unit
) {
    val colors = MiuixTheme.colorScheme

    Column(
        modifier = Modifier.width(44.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.settings_db_value_signed_one_decimal, gain),
            color = if (abs(gain) >= 0.05f) colors.primary else colors.onSurfaceVariantSummary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onReset)
                .padding(vertical = 4.dp)
        )

        VerticalSlider(
            value = gain.coerceIn(GRAPHIC_EQ_MIN_GAIN, GRAPHIC_EQ_MAX_GAIN),
            onValueChange = onGainChange,
            modifier = Modifier.height(196.dp),
            valueRange = GRAPHIC_EQ_MIN_GAIN..GRAPHIC_EQ_MAX_GAIN,
            steps = 47,
            width = 26.dp,
            hapticEffect = SliderDefaults.SliderHapticEffect.Step,
            showKeyPoints = false
        )

        Text(
            text = graphicFrequencyLabel(frequency),
            color = colors.onSurfaceVariantSummary,
            fontSize = 10.sp,
            lineHeight = 12.sp,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
        )
    }
}

@Composable
private fun localizedGraphicPresetName(name: String): String = when (name) {
    "Normal" -> stringResource(R.string.settings_graphic_eq_preset_normal)
    "Pop" -> stringResource(R.string.settings_graphic_eq_preset_pop)
    "Rock" -> stringResource(R.string.settings_graphic_eq_preset_rock)
    "Jazz" -> stringResource(R.string.settings_graphic_eq_preset_jazz)
    "Classical" -> stringResource(R.string.settings_graphic_eq_preset_classical)
    "Bass Boost" -> stringResource(R.string.settings_graphic_eq_preset_bass_boost)
    "Treble Boost" -> stringResource(R.string.settings_graphic_eq_preset_treble_boost)
    "Vocal" -> stringResource(R.string.settings_graphic_eq_preset_vocal)
    "Electronic" -> stringResource(R.string.settings_graphic_eq_preset_electronic)
    "Acoustic" -> stringResource(R.string.settings_graphic_eq_preset_acoustic)
    "Custom" -> stringResource(R.string.settings_graphic_eq_preset_custom)
    else -> name
}

@Composable
private fun graphicFrequencyLabel(frequency: Float): String {
    return if (frequency >= 1000f) {
        val khz = frequency / 1000f
        if (abs(khz - khz.roundToInt()) < 0.05f) {
            stringResource(R.string.settings_graphic_eq_frequency_khz_integer, khz.roundToInt())
        } else {
            stringResource(R.string.settings_graphic_eq_frequency_khz_decimal, khz)
        }
    } else {
        stringResource(R.string.settings_hz_value, frequency.roundToInt())
    }
}
