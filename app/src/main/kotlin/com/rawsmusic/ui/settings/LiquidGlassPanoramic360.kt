package com.rawsmusic.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.module.player.dsp.Panoramic360Controller

private object Panoramic360UiColors {
    val Background = Color(0xFF0D0D1A)
    val CardBackground = Color(0xFF1A1A2E)
    val Accent = Color.White
    val AccentDim = Color.White
    val TextPrimary = Color(0xFFEEEEFF)
    val TextSecondary = Color(0xFF8A8AAA)
    val SliderTrack = Color(0xFF2A2A4A)
}

@Composable
fun LiquidGlassPanoramic360Screen(
    controller: Panoramic360Controller,
    onBack: () -> Unit
) {
    val isEnabled by controller.isEnabled.collectAsState()
    val intensity by controller.intensity.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .background(Panoramic360UiColors.Background)
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(16.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) {
                Text("← 返回", color = Panoramic360UiColors.Accent, fontSize = 16.sp)
            }
            Text(
                "360° 全景音",
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                color = Panoramic360UiColors.TextPrimary
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
                            "360° 全景音",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            color = Panoramic360UiColors.TextPrimary
                        )
                        Text(
                            "3D 球面双耳渲染 · 全方位扩散 · 耳廓EQ + 早期反射 + FDN混响",
                            fontSize = 12.sp,
                            color = Panoramic360UiColors.TextSecondary
                        )
                    }
                    Switch(
                        checked = isEnabled,
                        onCheckedChange = { controller.setEnabled(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Panoramic360UiColors.Accent,
                            checkedTrackColor = Panoramic360UiColors.AccentDim
                        )
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // 效果说明卡片
            GlassCard {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(
                        "效果说明",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = Panoramic360UiColors.TextPrimary
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "将音频信号全方位360°扩散，模拟真实空间中的声音包围感。无需手动调节方向，开启即可体验沉浸式全景声场。",
                        fontSize = 13.sp,
                        color = Panoramic360UiColors.TextSecondary,
                        lineHeight = 20.sp
                    )
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
                        color = Panoramic360UiColors.TextPrimary
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${intensity.toInt()}%",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = Panoramic360UiColors.Accent
                    )
                    Slider(
                        value = intensity,
                        onValueChange = { controller.setIntensity(it) },
                        valueRange = 0f..100f,
                        enabled = isEnabled,
                        colors = SliderDefaults.colors(
                            thumbColor = Panoramic360UiColors.Accent,
                            activeTrackColor = Panoramic360UiColors.Accent,
                            inactiveTrackColor = Panoramic360UiColors.SliderTrack
                        )
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("轻微", fontSize = 11.sp, color = Panoramic360UiColors.TextSecondary)
                        Text("适中", fontSize = 11.sp, color = Panoramic360UiColors.TextSecondary)
                        Text("强烈", fontSize = 11.sp, color = Panoramic360UiColors.TextSecondary)
                    }
                }
            }

            Spacer(Modifier.height(300.dp))
        }
    }
}

@Composable
private fun GlassCard(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Panoramic360UiColors.CardBackground)
    ) {
        content()
    }
}
