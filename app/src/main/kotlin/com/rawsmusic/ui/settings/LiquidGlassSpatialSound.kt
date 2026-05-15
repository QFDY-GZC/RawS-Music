package com.rawsmusic.ui.settings

import android.util.Log
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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.ui.songs.PlayerHolder

@Composable
fun LiquidGlassSpatialSoundScreen(
    onBack: () -> Unit
) {
    val backdrop = rememberLayerBackdrop()

    var spatialEnabled by remember { mutableStateOf(AppPreferences.Equalizer.virtualizer > 0) }
    var strength by remember { mutableStateOf(AppPreferences.Equalizer.virtualizer.toFloat()) }
    var savedStrength by remember { mutableStateOf(AppPreferences.Equalizer.virtualizer) }

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
                    "空间音效",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF1C1B1F)
                )
                Spacer(Modifier.weight(1f))
            }

            LiquidGlassCard(backdrop) {
                SwitchRow("启用空间音效", spatialEnabled) { checked ->
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

            LiquidGlassCard(backdrop) {
                Text("强度", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1C1B1F))
                Spacer(Modifier.height(4.dp))
                Text(
                    "${(strength.toInt() / 10)}%",
                    fontSize = 14.sp, color = Color(0xFF6750A4)
                )
                Spacer(Modifier.height(8.dp))
                Slider(
                    value = strength,
                    onValueChange = { value ->
                        strength = value
                    },
                    valueRange = 0f..1000f,
                    steps = 99,
                    colors = SliderDefaults.colors(thumbColor = Color(0xFF6750A4), activeTrackColor = Color(0xFF6750A4)),
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

            Spacer(Modifier.height(24.dp))
        }
    }
}
