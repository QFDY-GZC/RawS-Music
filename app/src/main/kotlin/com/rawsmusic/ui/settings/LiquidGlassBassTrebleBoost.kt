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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
                Text("← 返回", color = BassTrebleUiColors.Accent, fontSize = 16.sp)
            }
            Text(
                "低音/高音增强",
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
                "低音增强",
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
                        "启用低音增强",
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
                    label = "增益",
                    value = bassGain,
                    valueRange = -12f..12f,
                    valueFormat = { String.format("%+.1f dB", it) },
                    onValueChange = { bassBoostController.setGain(it) },
                    accentColor = BassTrebleUiColors.Accent
                )
            }

            Spacer(Modifier.height(8.dp))

            CardSection {
                SliderParam(
                    label = "转折频率",
                    value = bassFrequency,
                    valueRange = 50f..500f,
                    valueFormat = { String.format("%.0f Hz", it) },
                    onValueChange = { bassBoostController.setFrequency(it) },
                    accentColor = BassTrebleUiColors.Accent
                )
            }

            Spacer(Modifier.height(24.dp))

            // 高音增强部分
            Text(
                "高音增强",
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
                        "启用高音增强",
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
                    label = "增益",
                    value = trebleGain,
                    valueRange = -12f..12f,
                    valueFormat = { String.format("%+.1f dB", it) },
                    onValueChange = { trebleBoostController.setGain(it) },
                    accentColor = BassTrebleUiColors.AccentSecondary
                )
            }

            Spacer(Modifier.height(8.dp))

            CardSection {
                SliderParam(
                    label = "转折频率",
                    value = trebleFrequency,
                    valueRange = 2000f..16000f,
                    valueFormat = { String.format("%.0f Hz", it) },
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
    valueFormat: (Float) -> String,
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
                valueFormat(value),
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