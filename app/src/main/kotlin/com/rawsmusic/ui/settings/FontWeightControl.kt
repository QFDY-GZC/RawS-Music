package com.rawsmusic.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.R
import com.rawsmusic.module.data.prefs.FontFileInspector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun FontWeightControl(
    fontPath: String,
    weight: Int,
    experimentalEnabled: Boolean,
    revision: Any?,
    onWeightChange: (Int) -> Unit,
    onExperimentalChange: (Boolean) -> Unit,
) {
    var inspected by remember(fontPath, revision) { mutableStateOf(fontPath.isBlank()) }
    var capabilities by remember(fontPath, revision) {
        mutableStateOf<FontFileInspector.Capabilities?>(null)
    }

    LaunchedEffect(fontPath, revision) {
        if (fontPath.isBlank()) {
            capabilities = null
            inspected = true
        } else {
            inspected = false
            capabilities = withContext(Dispatchers.IO) { FontFileInspector.inspect(fontPath) }
            inspected = true
        }
    }

    val nativeRange = capabilities?.nativeWeightRange
    val hasKnownNativeRange = fontPath.isNotBlank() && nativeRange != null
    val canUseExperimental = hasKnownNativeRange &&
        (nativeRange!!.first > 100 || nativeRange.last < 900 || nativeRange.first == nativeRange.last)

    LaunchedEffect(fontPath, nativeRange, experimentalEnabled, inspected) {
        if (inspected && hasKnownNativeRange && !experimentalEnabled) {
            val clamped = weight.coerceIn(nativeRange!!.first, nativeRange.last)
            if (clamped != weight) onWeightChange(clamped)
        }
    }

    val activeRange = when {
        experimentalEnabled -> 100..900
        hasKnownNativeRange -> nativeRange!!
        else -> 100..900
    }

    SectionHeader(stringResource(R.string.settings_font_weight))

    if (activeRange.first < activeRange.last) {
        val displayWeight = weight.coerceIn(activeRange.first, activeRange.last)
        SliderPreference(
            title = stringResource(R.string.settings_font_weight),
            summary = null,
            valueText = stringResource(
                R.string.settings_font_weight_value,
                fontWeightName(displayWeight),
                displayWeight,
            ),
            value = displayWeight.toFloat(),
            onValueChange = { onWeightChange(it.toInt().coerceIn(activeRange.first, activeRange.last)) },
            valueRange = activeRange.first.toFloat()..activeRange.last.toFloat(),
            steps = weightSliderSteps(activeRange),
            hapticEffect = SliderDefaults.SliderHapticEffect.Step,
        )
    } else {
        Text(
            text = stringResource(R.string.settings_font_weight_static_native_value, activeRange.first),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            fontSize = 14.sp,
            color = MiuixTheme.colorScheme.primary,
        )
    }

    val capabilityText = when {
        fontPath.isBlank() -> stringResource(R.string.settings_font_weight_system_family_hint)
        !inspected -> stringResource(R.string.settings_font_capability_loading)
        capabilities?.hasVariableWeight == true -> {
            val range = requireNotNull(nativeRange)
            stringResource(R.string.settings_font_weight_variable_hint, range.first, range.last)
        }
        capabilities?.staticWeight != null ->
            stringResource(R.string.settings_font_weight_static_face_hint, capabilities!!.staticWeight!!)
        else -> stringResource(R.string.settings_font_weight_unknown_capability_hint)
    }
    Text(
        text = capabilityText,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        fontSize = 12.sp,
        color = MiuixTheme.colorScheme.onBackground.copy(alpha = 0.62f),
    )

    if (canUseExperimental) {
        SectionHeader(stringResource(R.string.settings_font_weight_experimental_section))
        SwitchRow(
            label = stringResource(R.string.settings_font_weight_experimental_title),
            checked = experimentalEnabled,
            onCheckedChange = onExperimentalChange,
        )
        val range = nativeRange!!
        Text(
            text = if (experimentalEnabled && weight !in range) {
                stringResource(
                    R.string.settings_font_weight_experimental_active_summary,
                    range.first,
                    range.last,
                    weight,
                )
            } else {
                stringResource(
                    R.string.settings_font_weight_experimental_summary,
                    range.first,
                    range.last,
                )
            },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onBackground.copy(alpha = 0.62f),
        )
    }
}

@Composable
internal fun fontCapabilitySummary(
    capabilities: FontFileInspector.Capabilities?,
): String? = when {
    capabilities?.hasVariableWeight == true -> {
        val range = capabilities.weightRange ?: return null
        stringResource(R.string.settings_font_capability_variable_short, range.first, range.last)
    }
    capabilities?.staticWeight != null ->
        stringResource(R.string.settings_font_capability_static_short, capabilities.staticWeight!!)
    else -> null
}

private fun weightSliderSteps(range: IntRange): Int {
    val span = range.last - range.first
    if (span < 100 || span % 50 != 0) return 0
    return (span / 50 - 1).coerceIn(0, 31)
}

private fun fontWeightName(weight: Int): String = when {
    weight < 200 -> "Thin"
    weight < 300 -> "ExtraLight"
    weight < 350 -> "Light"
    weight < 400 -> "Regular"
    weight < 500 -> "Medium"
    weight < 600 -> "Demibold"
    weight < 700 -> "Semibold"
    weight < 800 -> "Bold"
    else -> "Black"
}
