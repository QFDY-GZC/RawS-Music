package com.rawsmusic.ui.settings

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.module.player.dsp.Surround360Controller
import kotlin.math.cos
import kotlin.math.sin

private object Surround360UiColors {
    val Background = Color(0xFF0D0D1A)
    val CardBackground = Color(0xFF1A1A2E)
    val Accent = Color(0xFF9C27B0)
    val AccentDim = Color(0xFF6A1B9A)
    val TextPrimary = Color(0xFFEEEEFF)
    val TextSecondary = Color(0xFF8A8AAA)
    val SliderTrack = Color(0xFF2A2A4A)
    val DotColor = Color(0xFFCE93D8)
}

@Composable
fun LiquidGlassSurround360Screen(
    controller: Surround360Controller,
    onBack: () -> Unit
) {
    val isEnabled by controller.isEnabled.collectAsState()
    val intensity by controller.intensity.collectAsState()
    val azimuthDeg by controller.azimuthDeg.collectAsState()
    val rotationSpeed by controller.rotationSpeed.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .background(Surround360UiColors.Background)
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(16.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) {
                Text("← 返回", color = Surround360UiColors.Accent, fontSize = 16.sp)
            }
            Text(
                "360° 环绕音",
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                color = Surround360UiColors.TextPrimary
            )
            Spacer(Modifier.width(64.dp))
        }

        Spacer(Modifier.height(16.dp))

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            // 开关卡片
            GlassCard {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            "360° 环绕音",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            color = Surround360UiColors.TextPrimary
                        )
                        Text(
                            "2D 水平面双耳渲染 · 声源自动旋转",
                            fontSize = 12.sp,
                            color = Surround360UiColors.TextSecondary
                        )
                    }
                    Switch(
                        checked = isEnabled,
                        onCheckedChange = { controller.setEnabled(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Surround360UiColors.Accent,
                            checkedTrackColor = Surround360UiColors.AccentDim
                        )
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // 方位角可视化（只读，自动旋转）
            GlassCard {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(
                        "声源方位（自动旋转）",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = Surround360UiColors.TextPrimary
                    )
                    Spacer(Modifier.height(8.dp))

                    AzimuthIndicator(
                        azimuthDeg = azimuthDeg,
                        enabled = isEnabled,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp)
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // 旋转速度控制
            GlassCard {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(
                        "旋转速度",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = Surround360UiColors.TextPrimary
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (rotationSpeed <= 0f) "静止" else "${rotationSpeed.toInt()}°/秒",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = Surround360UiColors.Accent
                    )
                    Text(
                        if (rotationSpeed <= 0f) "声源固定不动"
                        else "一圈 ${"%.1f".format(360f / rotationSpeed)} 秒",
                        fontSize = 12.sp,
                        color = Surround360UiColors.TextSecondary
                    )
                    Spacer(Modifier.height(4.dp))
                    Slider(
                        value = rotationSpeed,
                        onValueChange = { controller.setRotationSpeed(it) },
                        valueRange = 0f..360f,
                        enabled = isEnabled,
                        colors = SliderDefaults.colors(
                            thumbColor = Surround360UiColors.Accent,
                            activeTrackColor = Surround360UiColors.Accent,
                            inactiveTrackColor = Surround360UiColors.SliderTrack
                        )
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("静止", fontSize = 11.sp, color = Surround360UiColors.TextSecondary)
                        Text("慢", fontSize = 11.sp, color = Surround360UiColors.TextSecondary)
                        Text("快", fontSize = 11.sp, color = Surround360UiColors.TextSecondary)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // 强度控制
            GlassCard {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(
                        "效果强度",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = Surround360UiColors.TextPrimary
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${intensity.toInt()}%",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = Surround360UiColors.Accent
                    )
                    Slider(
                        value = intensity,
                        onValueChange = { controller.setIntensity(it) },
                        valueRange = 0f..100f,
                        enabled = isEnabled,
                        colors = SliderDefaults.colors(
                            thumbColor = Surround360UiColors.Accent,
                            activeTrackColor = Surround360UiColors.Accent,
                            inactiveTrackColor = Surround360UiColors.SliderTrack
                        )
                    )
                }
            }

            Spacer(Modifier.height(300.dp))
        }
    }
}

@Composable
private fun AzimuthIndicator(azimuthDeg: Float, enabled: Boolean, modifier: Modifier = Modifier) {
    val azRad = Math.toRadians(azimuthDeg.toDouble())

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Surround360UiColors.CardBackground),
        contentAlignment = Alignment.Center
    ) {
        // 圆环
        Box(
            Modifier
                .size(120.dp)
                .clip(CircleShape)
                .background(Color.Transparent)
        )

        // 方向标签
        Text("前", fontSize = 10.sp, color = Surround360UiColors.TextSecondary,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 4.dp))
        Text("右", fontSize = 10.sp, color = Surround360UiColors.TextSecondary,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 4.dp))
        Text("后", fontSize = 10.sp, color = Surround360UiColors.TextSecondary,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp))
        Text("左", fontSize = 10.sp, color = Surround360UiColors.TextSecondary,
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 4.dp))

        // 声源指示点
        val radius = 48f
        val dotX = (sin(azRad) * radius).toFloat()
        val dotY = (-cos(azRad) * radius).toFloat()

        Box(
            Modifier
                .align(Alignment.Center)
                .offset(x = dotX.dp, y = dotY.dp)
                .size(16.dp)
                .clip(CircleShape)
                .background(if (enabled) Surround360UiColors.DotColor else Surround360UiColors.DotColor.copy(alpha = 0.3f))
        )

        // 中心文字
        Text(
            "${azimuthDeg.toInt()}°",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = if (enabled) Surround360UiColors.Accent else Surround360UiColors.Accent.copy(alpha = 0.4f),
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.Center)
        )
    }
}

@Composable
private fun GlassCard(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Surround360UiColors.CardBackground)
    ) {
        content()
    }
}
