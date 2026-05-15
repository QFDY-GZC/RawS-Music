package com.rawsmusic.ui.settings

import android.widget.Toast
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import com.rawsmusic.core.common.model.AudioOutputMode
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.player.AudioOutputManager

@Composable
fun LiquidGlassAudioSettingsScreen(
    onNavigateToSpatialSound: () -> Unit,
    onNavigateToUsbDac: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val backdrop = rememberLayerBackdrop()

    val sampleRates = intArrayOf(0, 44100, 48000, 88200, 96000, 176400, 192000, 352800, 384000)
    val bitDepths = intArrayOf(0, 16, 24, 32)

    var sampleRateIndex by remember {
        mutableStateOf(sampleRates.indexOf(AudioOutputManager.getTargetSampleRate()).coerceAtLeast(0))
    }
    var bitDepthIndex by remember {
        mutableStateOf(bitDepths.indexOf(AudioOutputManager.getTargetBitDepth()).coerceAtLeast(0))
    }
    var outputMode by remember { mutableStateOf(AppPreferences.Player.audioOutputMode) }
    var normalization by remember { mutableStateOf(AppPreferences.Player.volumeNormalizationEnabled) }
    var gapless by remember { mutableStateOf(AppPreferences.Player.gaplessPlaybackEnabled) }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .layerBackdrop(backdrop)
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFFF2F1F0),
                            Color(0xFFE8E7E5),
                            Color(0xFFD1D0CD).copy(alpha = 0.3f),
                            Color(0xFFB0AFA8).copy(alpha = 0.1f)
                        )
                    )
                )
        )

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(Modifier.height(16.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onBack) {
                    Text("← 返回", color = Color(0xFF6750A4), fontSize = 16.sp)
                }
                Text(
                    "音质设置",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF1C1B1F)
                )
                Spacer(Modifier.weight(1f))
            }

            LiquidGlassCard(backdrop) {
                Text("采样率", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1C1B1F))
                Spacer(Modifier.height(4.dp))
                Text(
                    AudioOutputManager.SAMPLE_RATE_LABELS[sampleRates[sampleRateIndex]] ?: "自动",
                    fontSize = 14.sp, color = Color(0xFF6750A4)
                )
                Spacer(Modifier.height(8.dp))
                Slider(
                    value = sampleRateIndex.toFloat(),
                    onValueChange = { idx ->
                        sampleRateIndex = idx.toInt()
                        val rate = sampleRates.getOrElse(sampleRateIndex) { 0 }
                        AudioOutputManager.setTargetSampleRate(rate)
                    },
                    valueRange = 0f..(sampleRates.size - 1).toFloat(),
                    steps = sampleRates.size - 2,
                    colors = SliderDefaults.colors(thumbColor = Color(0xFF6750A4), activeTrackColor = Color(0xFF6750A4))
                )
            }

            LiquidGlassCard(backdrop) {
                Text("位深", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1C1B1F))
                Spacer(Modifier.height(4.dp))
                Text(
                    AudioOutputManager.BIT_DEPTH_LABELS[bitDepths[bitDepthIndex]] ?: "自动",
                    fontSize = 14.sp, color = Color(0xFF6750A4)
                )
                Spacer(Modifier.height(8.dp))
                Slider(
                    value = bitDepthIndex.toFloat(),
                    onValueChange = { idx ->
                        bitDepthIndex = idx.toInt()
                        val depth = bitDepths.getOrElse(bitDepthIndex) { 0 }
                        AudioOutputManager.setTargetBitDepth(depth)
                    },
                    valueRange = 0f..(bitDepths.size - 1).toFloat(),
                    steps = bitDepths.size - 2,
                    colors = SliderDefaults.colors(thumbColor = Color(0xFF6750A4), activeTrackColor = Color(0xFF6750A4))
                )
            }

            LiquidGlassCard(backdrop) {
                Text("输出模式", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1C1B1F))
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (mode in listOf(AudioOutputMode.OPENSL_ES, AudioOutputMode.AAUDIO, AudioOutputMode.DIRECT)) {
                        val label = AudioOutputManager.getOutputModeLabel(mode)
                        val isSelected = outputMode == mode
                        val isAvailable = AudioOutputManager.isOutputModeAvailable(mode, context)
                        TextButton(
                            onClick = {
                                if (isAvailable) {
                                    outputMode = mode
                                    AudioOutputManager.setOutputMode(mode)
                                } else {
                                    Toast.makeText(context, "$label 当前不可用", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                label,
                                color = if (isSelected) Color(0xFF6750A4) else Color(0xFF79747E),
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }

            LiquidGlassCard(backdrop) {
                SwitchRow("音量标准化", normalization) { checked ->
                    normalization = checked
                    AppPreferences.Player.volumeNormalizationEnabled = checked
                }
                SwitchRow("无缝播放", gapless) { checked ->
                    gapless = checked
                    AppPreferences.Player.gaplessPlaybackEnabled = checked
                }
            }

            LiquidGlassCard(backdrop) {
                Text("USB DAC 设置", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1C1B1F))
                Spacer(Modifier.height(4.dp))
                Text("Bit-Perfect、硬件音量控制、高级 USB 设置", fontSize = 12.sp, color = Color(0xFF79747E))
                Spacer(Modifier.height(12.dp))
                GlassButton(onClick = onNavigateToUsbDac) {
                    Text("进入 USB DAC 设置", color = Color(0xFF6750A4), fontSize = 14.sp)
                }
            }

            LiquidGlassCard(backdrop) {
                Text("空间音效", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1C1B1F))
                Spacer(Modifier.height(12.dp))
                GlassButton(onClick = onNavigateToSpatialSound) {
                    Text("进入空间音效设置", color = Color(0xFF6750A4), fontSize = 14.sp)
                }
            }

            Spacer(Modifier.height(160.dp))
        }
    }
}
