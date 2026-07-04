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
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.module.player.dsp.CompressorController

/**
 * 压限器UI颜色
 */
private object CompressorUiColors {
    val Background = Color(0xFF0D0D1A)
    val CardBackground = Color(0xFF1A1A2E)
    val RowBackground = Color(0xFF12122A)
    val Accent = Color(0xFF4A90D9)  // 蓝色主题
    val AccentDim = Color(0xFF2D5A8A)
    val TextPrimary = Color(0xFFEEEEFF)
    val TextSecondary = Color(0xFF8A8AAA)
    val SliderTrack = Color(0xFF2A2A4A)
    val GRMeter = Color(0xFF4A90D9)  // 增益衰减指示器颜色
}

/**
 * 压限器界面
 */
@Composable
fun LiquidGlassCompressorScreen(
    compressorController: CompressorController,
    onBack: () -> Unit
) {
    val isEnabled by compressorController.isEnabled.collectAsState()
    val thresholdDB by compressorController.thresholdDB.collectAsState()
    val ratio by compressorController.ratio.collectAsState()
    val attackMs by compressorController.attackMs.collectAsState()
    val releaseMs by compressorController.releaseMs.collectAsState()
    val makeupGainDB by compressorController.makeupGainDB.collectAsState()
    val kneeWidthDB by compressorController.kneeWidthDB.collectAsState()
    val detectionMode by compressorController.detectionMode.collectAsState()
    val currentGR by compressorController.currentGR.collectAsState()

    LaunchedEffect(isEnabled) {
        while (isEnabled) {
            compressorController.updateGR()
            kotlinx.coroutines.delay(100)
        }
        compressorController.updateGR()
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(CompressorUiColors.Background)
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
                Text("← 返回", color = CompressorUiColors.Accent, fontSize = 16.sp)
            }
            Text(
                "压限器",
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                color = CompressorUiColors.TextPrimary
            )
            Spacer(Modifier.width(64.dp))
        }

        Spacer(Modifier.height(16.dp))

        // 启用开关
        CardSection {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "启用压限器",
                    fontSize = 16.sp,
                    color = CompressorUiColors.TextPrimary
                )
                Switch(
                    checked = isEnabled,
                    onCheckedChange = { compressorController.setEnabled(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = CompressorUiColors.Accent,
                        checkedTrackColor = CompressorUiColors.AccentDim
                    )
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // GR Meter
        CardSection {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(
                    "增益衰减",
                    fontSize = 14.sp,
                    color = CompressorUiColors.TextSecondary
                )
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(24.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(CompressorUiColors.SliderTrack)
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(fraction = (currentGR / 30f).coerceIn(0f, 1f))
                            .height(24.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(CompressorUiColors.GRMeter)
                    )
                    Text(
                        text = String.format("%.1f dB", currentGR),
                        fontSize = 12.sp,
                        color = CompressorUiColors.TextPrimary,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // 参数控制区域
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
        ) {
            // 阈值
            CardSection {
                SliderParam(
                    label = "阈值",
                    value = thresholdDB,
                    valueRange = -60f..0f,
                    valueFormat = { String.format("%.1f dB", it) },
                    onValueChange = { compressorController.setThreshold(it) }
                )
            }

            Spacer(Modifier.height(8.dp))

            // 压缩比
            CardSection {
                SliderParam(
                    label = "压缩比",
                    value = ratio,
                    valueRange = 1f..20f,
                    valueFormat = { String.format("%.1f:1", it) },
                    onValueChange = { compressorController.setRatio(it) }
                )
            }

            Spacer(Modifier.height(8.dp))

            // 启动时间
            CardSection {
                SliderParam(
                    label = "启动时间",
                    value = attackMs,
                    valueRange = 0.1f..100f,
                    valueFormat = { String.format("%.1f ms", it) },
                    onValueChange = { compressorController.setAttack(it) }
                )
            }

            Spacer(Modifier.height(8.dp))

            // 释放时间
            CardSection {
                SliderParam(
                    label = "释放时间",
                    value = releaseMs,
                    valueRange = 10f..1000f,
                    valueFormat = { String.format("%.0f ms", it) },
                    onValueChange = { compressorController.setRelease(it) }
                )
            }

            Spacer(Modifier.height(8.dp))

            // 补偿增益
            CardSection {
                SliderParam(
                    label = "补偿增益",
                    value = makeupGainDB,
                    valueRange = 0f..24f,
                    valueFormat = { String.format("%.1f dB", it) },
                    onValueChange = { compressorController.setMakeupGain(it) }
                )
            }

            Spacer(Modifier.height(8.dp))

            // 拐点宽度
            CardSection {
                SliderParam(
                    label = "拐点宽度",
                    value = kneeWidthDB,
                    valueRange = 0f..30f,
                    valueFormat = { String.format("%.1f dB", it) },
                    onValueChange = { compressorController.setKneeWidth(it) }
                )
            }

            Spacer(Modifier.height(8.dp))

            // 检测模式
            CardSection {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "检测模式",
                        fontSize = 14.sp,
                        color = CompressorUiColors.TextSecondary
                    )
                    Row {
                        TextButton(
                            onClick = { compressorController.setDetectionMode(0) }
                        ) {
                            Text(
                                "Peak",
                                color = if (detectionMode == 0) CompressorUiColors.Accent else CompressorUiColors.TextSecondary
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        TextButton(
                            onClick = { compressorController.setDetectionMode(1) }
                        ) {
                            Text(
                                "RMS",
                                color = if (detectionMode == 1) CompressorUiColors.Accent else CompressorUiColors.TextSecondary
                            )
                        }
                    }
                }
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
            .background(CompressorUiColors.CardBackground)
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
    onValueChange: (Float) -> Unit
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
                color = CompressorUiColors.TextSecondary
            )
            Text(
                valueFormat(value),
                fontSize = 14.sp,
                color = CompressorUiColors.Accent
            )
        }
        Spacer(Modifier.height(4.dp))
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = CompressorUiColors.Accent,
                activeTrackColor = CompressorUiColors.Accent,
                inactiveTrackColor = CompressorUiColors.SliderTrack
            )
        )
    }
}
