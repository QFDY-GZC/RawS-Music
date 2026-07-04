package com.rawsmusic.ui.settings

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.ui.songs.PlayerHolder

@Composable
fun LiquidGlassSpatialSoundScreen(
    onBack: () -> Unit
) {
    
    var spatialEnabled by remember { mutableStateOf(AppPreferences.Equalizer.virtualizer > 0) }
    var strength by remember { mutableStateOf(AppPreferences.Equalizer.virtualizer.toFloat()) }
    var savedStrength by remember { mutableStateOf(AppPreferences.Equalizer.virtualizer) }

    // Crossfeed 状态
    var crossfeedEnabled by remember { mutableStateOf(AppPreferences.Equalizer.crossfeedEnabled) }
    var cfLowCut by remember { mutableStateOf(AppPreferences.Equalizer.crossfeedLowCut.toFloat()) }
    var cfHighCut by remember { mutableStateOf(AppPreferences.Equalizer.crossfeedHighCut.toFloat()) }
    var cfAttenuation by remember { mutableStateOf(AppPreferences.Equalizer.crossfeedAttenuation / 10f) }

    Column(
        Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp)
    ) {
        Spacer(Modifier.height(16.dp))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) {
                Text("← 返回", color = MiuixTheme.colorScheme.primary, fontSize = 16.sp, fontFamily = appFontFamily())
            }
            Text(
                "立体声扩展",
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onBackground,
                fontFamily = appFontFamily()
            )
            Spacer(Modifier.weight(1f))
        }

        Spacer(Modifier.height(24.dp))

        SettingsCard {
            SectionHeader("立体声扩展")
            Spacer(Modifier.height(8.dp))
            SwitchRow("启用立体声扩展", spatialEnabled) { checked ->
                spatialEnabled = checked
                if (checked) {
                    val target = savedStrength.coerceAtLeast(100)
                    strength = target.toFloat()
                    PlayerHolder.controller?.setStereoWidenFactor(target / 1000f)
                } else {
                    savedStrength = strength.toInt()
                    strength = 0f
                    PlayerHolder.controller?.setStereoWidenFactor(0f)
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        SettingsCard {
            SectionHeader("强度")
            Text(
                "${(strength.toInt() / 10)}%",
                fontSize = 14.sp, color = MiuixTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp)
            )
            Slider(
                value = strength,
                onValueChange = { value ->
                    strength = value
                },
                valueRange = 0f..1000f,
                steps = 99,
                colors = SliderDefaults.colors(thumbColor = MiuixTheme.colorScheme.primary, activeTrackColor = MiuixTheme.colorScheme.primary),
                onValueChangeFinished = {
                    val value = strength.toInt()
                    Log.d("SpatialSound", "Slider finished: value=$value")
                    PlayerHolder.controller?.setStereoWidenFactor(value / 1000f)
                    savedStrength = value
                    if (value > 0 && !spatialEnabled) {
                        spatialEnabled = true
                    }
                }
            )
        }

        Spacer(Modifier.height(12.dp))

        SettingsCard {
            SectionHeader("互馈 (Crossfeed)")
            Spacer(Modifier.height(8.dp))
            Text(
                "模拟音箱串音，消除头中效应",
                fontSize = 13.sp, color = MiuixTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                fontFamily = appFontFamily()
            )
            Spacer(Modifier.height(8.dp))
            SwitchRow("启用互馈", crossfeedEnabled) { checked ->
                crossfeedEnabled = checked
                PlayerHolder.controller?.setCrossfeedEnabled(checked)
            }
        }

        Spacer(Modifier.height(12.dp))

        SettingsCard {
            SectionHeader("低切频率")
            Text(
                "${cfLowCut.toInt()} Hz",
                fontSize = 14.sp, color = MiuixTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp)
            )
            Slider(
                value = cfLowCut,
                onValueChange = { value ->
                    cfLowCut = value
                },
                valueRange = 50f..1000f,
                steps = 19,
                colors = SliderDefaults.colors(thumbColor = MiuixTheme.colorScheme.primary, activeTrackColor = MiuixTheme.colorScheme.primary),
                onValueChangeFinished = {
                    PlayerHolder.controller?.setCrossfeedParams(cfLowCut, cfHighCut, cfAttenuation)
                }
            )
        }

        Spacer(Modifier.height(12.dp))

        SettingsCard {
            SectionHeader("高切频率")
            Text(
                "${cfHighCut.toInt()} Hz",
                fontSize = 14.sp, color = MiuixTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp)
            )
            Slider(
                value = cfHighCut,
                onValueChange = { value ->
                    cfHighCut = value
                },
                valueRange = 500f..8000f,
                steps = 15,
                colors = SliderDefaults.colors(thumbColor = MiuixTheme.colorScheme.primary, activeTrackColor = MiuixTheme.colorScheme.primary),
                onValueChangeFinished = {
                    PlayerHolder.controller?.setCrossfeedParams(cfLowCut, cfHighCut, cfAttenuation)
                }
            )
        }

        Spacer(Modifier.height(12.dp))

        SettingsCard {
            SectionHeader("衰减量")
            Text(
                "%.1f dB".format(cfAttenuation),
                fontSize = 14.sp, color = MiuixTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp)
            )
            Slider(
                value = cfAttenuation,
                onValueChange = { value ->
                    cfAttenuation = value
                },
                valueRange = 0f..15f,
                steps = 29,
                colors = SliderDefaults.colors(thumbColor = MiuixTheme.colorScheme.primary, activeTrackColor = MiuixTheme.colorScheme.primary),
                onValueChangeFinished = {
                    PlayerHolder.controller?.setCrossfeedParams(cfLowCut, cfHighCut, cfAttenuation)
                }
            )
        }

        Spacer(Modifier.height(300.dp))
    }
}
