package com.rawsmusic.ui.settings

import top.yukonga.miuix.kmp.theme.MiuixTheme
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.core.common.model.AudioOutputMode
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.player.AudioOutputManager

@Composable
fun LiquidGlassAudioSettingsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    

    val sampleRates = intArrayOf(0, 44100, 48000, 88200, 96000, 176400, 192000, 352800, 384000)
    val bitDepths = AudioOutputManager.STANDARD_BIT_DEPTH_OPTIONS

    var sampleRateIndex by remember {
        mutableStateOf(sampleRates.indexOf(AudioOutputManager.getTargetSampleRate()).coerceAtLeast(0))
    }
    var bitDepthIndex by remember {
        mutableStateOf(bitDepths.indexOf(AudioOutputManager.getTargetBitDepth()).coerceAtLeast(0))
    }
    var outputMode by remember { mutableStateOf(AppPreferences.Player.audioOutputMode) }
    var normalization by remember { mutableStateOf(AppPreferences.Player.volumeNormalizationEnabled) }
    var gapless by remember { mutableStateOf(AppPreferences.Player.gaplessPlaybackEnabled) }
    var crossfadeSec by remember { mutableStateOf(AppPreferences.Player.crossfadeDuration) }
    var infoDialogId by remember { mutableStateOf(0) }

    fun applyAudioOutputSettings() {
        com.rawsmusic.ui.songs.PlayerHolder.controller?.applyAudioOutputSettingsChanged()
    }

    SettingsPage(title = "音质设置", onBack = onBack) {
        SettingsCard {
            SectionHeader("采样率")
            Text(
                AudioOutputManager.SAMPLE_RATE_LABELS[sampleRates[sampleRateIndex]] ?: "自动",
                fontSize = 14.sp,
                color = MiuixTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp)
            )
            Slider(
                value = sampleRateIndex.toFloat(),
                onValueChange = { idx ->
                    sampleRateIndex = idx.toInt()
                    val rate = sampleRates.getOrElse(sampleRateIndex) { 0 }
                    AudioOutputManager.setTargetSampleRate(rate)
                },
                onValueChangeFinished = {
                    applyAudioOutputSettings()
                },
                valueRange = 0f..(sampleRates.size - 1).toFloat(),
                steps = sampleRates.size - 2,
                colors = SliderDefaults.colors(thumbColor = MiuixTheme.colorScheme.primary, activeTrackColor = MiuixTheme.colorScheme.primary)
            )
        }

        Spacer(Modifier.height(12.dp))

        SettingsCard {
            SectionHeader("位深")
            Text(
                AudioOutputManager.BIT_DEPTH_LABELS[bitDepths[bitDepthIndex]] ?: "自动",
                fontSize = 14.sp,
                color = MiuixTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp)
            )
            Slider(
                value = bitDepthIndex.toFloat(),
                onValueChange = { idx ->
                    bitDepthIndex = idx.toInt()
                    val depth = bitDepths.getOrElse(bitDepthIndex) { 0 }
                    AudioOutputManager.setTargetBitDepth(depth)
                },
                onValueChangeFinished = {
                    applyAudioOutputSettings()
                },
                valueRange = 0f..(bitDepths.size - 1).toFloat(),
                steps = bitDepths.size - 2,
                colors = SliderDefaults.colors(thumbColor = MiuixTheme.colorScheme.primary, activeTrackColor = MiuixTheme.colorScheme.primary)
            )
        }

        Spacer(Modifier.height(12.dp))

        SettingsCard {
            SectionHeader("输出模式")
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
                                applyAudioOutputSettings()
                            } else {
                                Toast.makeText(context, "$label 当前不可用", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            label,
                            color = if (isSelected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 13.sp,
                            fontFamily = appFontFamily()
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        SettingsCard {
            SectionHeader("播放选项")
            Spacer(Modifier.height(4.dp))
            InfoSwitchRow("音量标准化", "根据歌曲的响度元数据自动调整播放音量，使不同歌曲的听感音量趋于一致。", normalization, { infoDialogId = 1 }) { checked ->
                normalization = checked
                AppPreferences.Player.volumeNormalizationEnabled = checked
            }
            InfoSwitchRow("无缝播放", "歌曲结束时无间隙地直接切换到下一首，消除曲目切换间的静音停顿。", gapless, { infoDialogId = 2 }) { checked ->
                gapless = checked
                AppPreferences.Player.gaplessPlaybackEnabled = checked
            }
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().clickable { infoDialogId = 3 },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "交叉淡入淡出: ${if (crossfadeSec == 0) "关闭" else "${crossfadeSec}秒"}",
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text("ⓘ", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(top = 4.dp))
            }
            Slider(
                value = crossfadeSec.toFloat(),
                onValueChange = { sec ->
                    crossfadeSec = sec.toInt()
                    AppPreferences.Player.crossfadeDuration = sec.toInt()
                },
                valueRange = 0f..12f,
                steps = 11,
                colors = SliderDefaults.colors(thumbColor = MiuixTheme.colorScheme.primary, activeTrackColor = MiuixTheme.colorScheme.primary)
            )
        }

        Spacer(Modifier.height(12.dp))

        // 蓝牙 SCO 通话信道设置
        SettingsCard {
            SectionHeader("蓝牙通话信道")
            Spacer(Modifier.height(4.dp))

            // 说明文字
            Text(
                "适配仅支持通话协议（HFP/HSP）的车载蓝牙设备",
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // 三档选择：关闭/自动/强制
            var scoMode by remember { mutableStateOf(AppPreferences.Player.bluetoothScoMode) }
            var scoDownsample by remember { mutableStateOf(AppPreferences.Player.bluetoothScoDownsample) }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (mode in listOf(0, 1, 2)) {
                    val label = AudioOutputManager.getScoModeLabel(mode)
                    val isSelected = scoMode == mode
                    TextButton(
                        onClick = {
                            scoMode = mode
                            AppPreferences.Player.bluetoothScoMode = mode
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            label,
                            color = if (isSelected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 13.sp,
                            fontFamily = appFontFamily()
                        )
                    }
                }
            }

            // 模式描述
            Text(
                AudioOutputManager.getScoModeDescription(scoMode),
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
            )

            // 降采样开关
            if (scoMode > 0) {
                InfoSwitchRow(
                    "降采样到 16kHz",
                    "SCO 通话信道带宽有限，降采样可提高兼容性，但音质会降低。",
                    scoDownsample,
                    { infoDialogId = 4 }
                ) { checked ->
                    scoDownsample = checked
                    AppPreferences.Player.bluetoothScoDownsample = checked
                }
            }
        }

        if (infoDialogId > 0) {
            val (title, body) = when (infoDialogId) {
                1 -> "音量标准化" to "根据歌曲内嵌的响度元数据（ReplayGain）自动调整播放增益，使不同录制响度的歌曲在播放时听感音量趋于一致。\n 基于 ITU-R BS.1770 响度归一化标准。"
                2 -> "无缝播放" to "在当前歌曲播放结束时，直接无缝切换到下一首歌曲的解码流，消除曲目之间常见的短暂静音间隙（Gap）。\n\n适用于现场专辑、DJ 混音等需要连续播放的场景。"
                3 -> "交叉淡入淡出" to "在当前歌曲结束前，提前开始播放下一首歌曲，两首歌的音频重叠混合。使用恒定功率曲线（Constant-Power）进行淡入淡出，保持听感自然。\n\n可设置 1~12 秒的重叠时长，0 表示关闭。"
                4 -> "SCO 降采样" to "蓝牙 SCO（Synchronous Connection Oriented）通话信道的音频带宽有限，通常仅支持 8kHz 或 16kHz 采样率。\n\n开启降采样后，音频会在输出前重采样到 16kHz，可提高与各类车载设备的兼容性，但音质会明显降低（类似电话通话效果）。\n\n关闭此选项时，系统会尝试以原始采样率输出，部分设备可能无法正常播放。"
                else -> "" to ""
            }
            AlertDialog(
                onDismissRequest = { infoDialogId = 0 },
                title = { Text(title, fontWeight = FontWeight.Bold) },
                text = { Text(body, fontSize = 14.sp, lineHeight = 22.sp) },
                confirmButton = {
                    TextButton(onClick = { infoDialogId = 0 }) { Text("知道了") }
                }
            )
        }
    }
}

@Composable
private fun InfoSwitchRow(
    label: String,
    info: String,
    checked: Boolean,
    onInfoClick: () -> Unit,
    onCheckedChange: (Boolean) -> Unit
) {
    
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Text(
                label,
                fontSize = 14.sp,
                color = MiuixTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
                fontFamily = appFontFamily()
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "ⓘ",
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.clickable { onInfoClick() }
            )
        }
        androidx.compose.material3.Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.padding(start = 16.dp),
            colors = androidx.compose.material3.SwitchDefaults.colors(
                checkedThumbColor = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
                checkedTrackColor = androidx.compose.ui.graphics.Color(0xFF7F7F7F),
                uncheckedThumbColor = androidx.compose.ui.graphics.Color(0xFFF1F1F1),
                uncheckedTrackColor = androidx.compose.ui.graphics.Color(0xFF9F9F9F)
            )
        )
    }
}
