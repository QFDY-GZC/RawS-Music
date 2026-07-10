package com.rawsmusic.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.R
import com.rawsmusic.module.player.dsp.BassBoostController
import com.rawsmusic.module.player.dsp.TrebleBoostController

/**
 * 低音/高音增强UI颜色
 */
private object BassTrebleUiColors {
    val Background = Color(0xFF0D0D1A)
    val CardBackground = Color(0xFF1A1A2E)
    val Accent = Color(0xFF4CAF50)  // 绿色主题
    val AccentDim = Color(0xFF2E7D32)
    val AccentSecondary = Color(0xFF2196F3)  // 蓝色主题（高音）
    val AccentSecondaryDim = Color(0xFF1565C0)
    val TextPrimary = Color(0xFFEEEEFF)
    val TextSecondary = Color(0xFF8A8AAA)
    val SliderTrack = Color(0xFF2A2A4A)
}

/**
 * 低音/高音增强界面
 */
@Composable
fun LiquidGlassBassTrebleBoostScreen(
    bassBoostController: BassBoostController,
    trebleBoostController: TrebleBoostController,
    onBack: () -> Unit
) {
    val bassEnabled by bassBoostController.isEnabled.collectAsState()
    val bassGain by bassBoostController.gainDB.collectAsState()
    val bassFrequency by bassBoostController.frequency.collectAsState()

    val trebleEnabled by trebleBoostController.isEnabled.collectAsState()
    val trebleGain by trebleBoostController.gainDB.collectAsState()
    val trebleFrequency by trebleBoostController.frequency.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .background(BassTrebleUiColors.Background)
            .padding(horizontal = 16.dp)
    ) {
        // 顶部栏
        Spacer(Modifier.height(16.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) {
                Text(stringResource(R.string.settings_back_with_arrow), color = BassTrebleUiColors.Accent, fontSize = 16.sp)
            }
            Text(
                stringResource(R.string.settings_bass_treble_title),
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                color = BassTrebleUiColors.TextPrimary
            )
            Spacer(Modifier.width(64.dp))
        }

        Spacer(Modifier.height(16.dp))

        Column(
            Modifier
                .verticalScroll(rememberScrollState())
        ) {
            // 低音增强部分
            Text(
                stringResource(R.string.settings_bass_title),
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                color = BassTrebleUiColors.Accent,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            CardSection {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.settings_bass_enable),
                        fontSize = 16.sp,
                        color = BassTrebleUiColors.TextPrimary
                    )
                    Switch(
                        checked = bassEnabled,
                        onCheckedChange = { bassBoostController.setEnabled(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = BassTrebleUiColors.Accent,
                            checkedTrackColor = BassTrebleUiColors.AccentDim
                        )
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            CardSection {
                SliderParam(
                    label = stringResource(R.string.settings_effect_gain),
                    value = bassGain,
                    valueRange = -12f..12f,
                    valueText = stringResource(R.string.settings_db_value_signed_one_decimal, bassGain),
                    onValueChange = { bassBoostController.setGain(it) },
                    accentColor = BassTrebleUiColors.Accent
                )
            }

            Spacer(Modifier.height(8.dp))

            CardSection {
                SliderParam(
                    label = stringResource(R.string.settings_effect_corner_frequency),
                    value = bassFrequency,
                    valueRange = 50f..500f,
                    valueText = stringResource(R.string.settings_hz_value, bassFrequency.toInt()),
                    onValueChange = { bassBoostController.setFrequency(it) },
                    accentColor = BassTrebleUiColors.Accent
                )
            }

            Spacer(Modifier.height(24.dp))

            // 高音增强部分
            Text(
                stringResource(R.string.settings_treble_title),
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                color = BassTrebleUiColors.AccentSecondary,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            CardSection {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.settings_treble_enable),
                        fontSize = 16.sp,
                        color = BassTrebleUiColors.TextPrimary
                    )
                    Switch(
                        checked = trebleEnabled,
                        onCheckedChange = { trebleBoostController.setEnabled(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = BassTrebleUiColors.AccentSecondary,
                            checkedTrackColor = BassTrebleUiColors.AccentSecondaryDim
                        )
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            CardSection {
                SliderParam(
                    label = stringResource(R.string.settings_effect_gain),
                    value = trebleGain,
                    valueRange = -12f..12f,
                    valueText = stringResource(R.string.settings_db_value_signed_one_decimal, trebleGain),
                    onValueChange = { trebleBoostController.setGain(it) },
                    accentColor = BassTrebleUiColors.AccentSecondary
                )
            }

            Spacer(Modifier.height(8.dp))

            CardSection {
                SliderParam(
                    label = stringResource(R.string.settings_effect_corner_frequency),
                    value = trebleFrequency,
                    valueRange = 2000f..16000f,
                    valueText = stringResource(R.string.settings_hz_value, trebleFrequency.toInt()),
                    onValueChange = { trebleBoostController.setFrequency(it) },
                    accentColor = BassTrebleUiColors.AccentSecondary
                )
            }

            Spacer(Modifier.height(300.dp))
        }
    }
}

@Composable
private fun CardSection(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(BassTrebleUiColors.CardBackground)
    ) {
        content()
    }
}

@Composable
private fun SliderParam(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    valueText: String,
    onValueChange: (Float) -> Unit,
    accentColor: Color = BassTrebleUiColors.Accent
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                label,
                fontSize = 14.sp,
                color = BassTrebleUiColors.TextSecondary
            )
            Text(
                valueText,
                fontSize = 14.sp,
                color = accentColor
            )
        }
        Spacer(Modifier.height(4.dp))
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = accentColor,
                activeTrackColor = accentColor,
                inactiveTrackColor = BassTrebleUiColors.SliderTrack
            )
        )
    }
}