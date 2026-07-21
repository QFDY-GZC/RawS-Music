package com.rawsmusic.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.R
import com.rawsmusic.module.player.dsp.Panoramic360Controller
import com.rawsmusic.module.player.dsp.Surround360Controller
import kotlin.math.cos
import kotlin.math.sin
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * MIUIX version of the existing Surround 360 screen. The controller remains
 * the only owner of state, persistence and the rotation coroutine.
 */
@Composable
internal fun Surround360SettingsContent(
    controller: Surround360Controller?,
    showSectionHeader: Boolean = true
) {
    if (showSectionHeader) {
        SectionHeader(stringResource(R.string.settings_surround360_title))
    }

    if (controller == null) {
        SettingsCard {
            SwitchPreference(
                title = stringResource(R.string.settings_surround360_title),
                summary = stringResource(R.string.settings_surround360_subtitle),
                checked = false,
                enabled = false,
                onCheckedChange = {}
            )
        }
        return
    }

    val enabled by controller.isEnabled.collectAsState()
    val intensity by controller.intensity.collectAsState()
    val rotationSpeed by controller.rotationSpeed.collectAsState()

    SettingsCard {
        SwitchPreference(
            title = stringResource(R.string.settings_surround360_title),
            summary = stringResource(R.string.settings_surround360_subtitle),
            checked = enabled,
            onCheckedChange = controller::setEnabled
        )

        ExpandableEffectContent(enabled = enabled) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
            ) {
                Text(
                    text = stringResource(R.string.settings_surround360_azimuth),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )
                SurroundAzimuthIndicator(
                    controller = controller,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(156.dp)
                        .padding(horizontal = 12.dp)
                )

                SliderPreference(
                    title = stringResource(R.string.settings_surround360_rotation_speed),
                    summary = if (rotationSpeed <= 0f) {
                        stringResource(R.string.settings_surround360_rotation_static)
                    } else {
                        stringResource(R.string.settings_surround360_rotation_period, 360f / rotationSpeed)
                    },
                    valueText = if (rotationSpeed <= 0f) {
                        stringResource(R.string.settings_effect_static)
                    } else {
                        stringResource(R.string.settings_degree_per_second_value, rotationSpeed.toInt())
                    },
                    value = rotationSpeed.coerceIn(0f, 360f),
                    onValueChange = controller::setRotationSpeed,
                    valueRange = 0f..360f
                )

                SliderPreference(
                    title = stringResource(R.string.settings_effect_intensity),
                    summary = null,
                    valueText = stringResource(R.string.settings_percent_value, intensity.toInt()),
                    value = intensity.coerceIn(0f, 100f),
                    onValueChange = controller::setIntensity,
                    valueRange = 0f..100f
                )
            }
        }
    }
}

@Composable
private fun SurroundAzimuthIndicator(
    controller: Surround360Controller,
    modifier: Modifier = Modifier
) {
    // Keep the 20 fps azimuth StateFlow local to the indicator so the sliders
    // and the rest of the card do not recompose on every rotation tick.
    val azimuthDeg by controller.azimuthDeg.collectAsState()
    val azimuthRad = Math.toRadians(azimuthDeg.toDouble())
    val radius = 48f
    val dotX = (sin(azimuthRad) * radius).toFloat()
    val dotY = (-cos(azimuthRad) * radius).toFloat()

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MiuixTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(R.string.settings_direction_front),
            fontSize = 10.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 8.dp)
        )
        Text(
            text = stringResource(R.string.settings_direction_right),
            fontSize = 10.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 8.dp)
        )
        Text(
            text = stringResource(R.string.settings_direction_back),
            fontSize = 10.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp)
        )
        Text(
            text = stringResource(R.string.settings_direction_left),
            fontSize = 10.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 8.dp)
        )

        Box(
            modifier = Modifier
                .size(108.dp)
                .clip(CircleShape)
                .background(MiuixTheme.colorScheme.surfaceContainerHigh)
        )

        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .offset(x = dotX.dp, y = dotY.dp)
                .size(16.dp)
                .clip(CircleShape)
                .background(MiuixTheme.colorScheme.primary)
        )

        Text(
            text = stringResource(R.string.settings_degree_value, azimuthDeg.toInt()),
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = MiuixTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.Center)
        )
    }
}

/**
 * MIUIX version of the existing Panoramic 360 screen. It intentionally exposes
 * the same user-facing controls as the old page: enabled and intensity only.
 */
@Composable
internal fun Panoramic360SettingsContent(
    controller: Panoramic360Controller?,
    showSectionHeader: Boolean = true
) {
    if (showSectionHeader) {
        SectionHeader(stringResource(R.string.settings_panoramic360_title))
    }

    if (controller == null) {
        SettingsCard {
            SwitchPreference(
                title = stringResource(R.string.settings_panoramic360_title),
                summary = stringResource(R.string.settings_panoramic360_subtitle),
                checked = false,
                enabled = false,
                onCheckedChange = {}
            )
        }
        return
    }

    val enabled by controller.isEnabled.collectAsState()
    val intensity by controller.intensity.collectAsState()

    SettingsCard {
        SwitchPreference(
            title = stringResource(R.string.settings_panoramic360_title),
            summary = stringResource(R.string.settings_panoramic360_subtitle),
            checked = enabled,
            onCheckedChange = controller::setEnabled
        )

        ExpandableEffectContent(enabled = enabled) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
            ) {
                Text(
                    text = stringResource(R.string.settings_panoramic360_description),
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                SliderPreference(
                    title = stringResource(R.string.settings_effect_intensity),
                    summary = null,
                    valueText = stringResource(R.string.settings_percent_value, intensity.toInt()),
                    value = intensity.coerceIn(0f, 100f),
                    onValueChange = controller::setIntensity,
                    valueRange = 0f..100f
                )
            }
        }
    }
}
