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
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.rawsmusic.core.common.model.PlayState
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.player.usb.UsbAudioEngine

@Composable
fun LiquidGlassUsbDacSettingsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val backdrop = rememberLayerBackdrop()

    var bitPerfect by remember { mutableStateOf(AppPreferences.Player.bitPerfectEnabled) }
    var hardwareFU by remember { mutableStateOf(AppPreferences.Player.hardwareFeatureUnitEnabled) }
    var usbNoCI by remember { mutableStateOf(AppPreferences.Player.usbNoControlInterface) }
    var usbLinearVol by remember { mutableStateOf(AppPreferences.Player.usbLinearVolume) }
    var usbForce1ms by remember { mutableStateOf(AppPreferences.Player.usbForce1MsPacket) }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
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
                    "USB DAC 设置",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF1C1B1F)
                )
                Spacer(Modifier.weight(1f))
            }

            LiquidGlassCard(backdrop) {
                Text("USB DAC", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1C1B1F))
                Spacer(Modifier.height(4.dp))
                SwitchRow("Bit-Perfect", bitPerfect) { checked ->
                    bitPerfect = checked
                    AppPreferences.Player.bitPerfectEnabled = checked
                    val mainActivity = context as? com.rawsmusic.MainActivity
                    val exclusive = mainActivity?.playerController?.isUsbExclusiveActive() == true
                    UsbAudioEngine.nativeSetPolicy(exclusive, checked, hardwareFU)
                    val pc = mainActivity?.playerController
                    if (pc?.playState?.value == PlayState.PLAYING) {
                        pc.pause()
                    }
                }
                SwitchRow("硬件音量控制", hardwareFU) { checked ->
                    hardwareFU = checked
                    AppPreferences.Player.hardwareFeatureUnitEnabled = checked
                    val mainActivity = context as? com.rawsmusic.MainActivity
                    val exclusive = mainActivity?.playerController?.isUsbExclusiveActive() == true
                    UsbAudioEngine.nativeSetPolicy(exclusive, bitPerfect, checked)
                    val pc = mainActivity?.playerController
                    if (pc?.playState?.value == PlayState.PLAYING) {
                        pc.pause()
                    }
                }
            }

            LiquidGlassCard(backdrop) {
                Text("USB DAC 高级设置", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1C1B1F))
                Spacer(Modifier.height(2.dp))
                Text("解决电流声/杂音（如果设备有，可能对你有帮助）", fontSize = 12.sp, color = Color(0xFF79747E))
                Spacer(Modifier.height(8.dp))
                SwitchRow("跳过 AC 接口", usbNoCI) { checked ->
                    usbNoCI = checked
                    AppPreferences.Player.usbNoControlInterface = checked
                    UsbAudioEngine.nativeSetUsbDacSettings(checked, false, usbLinearVol, false, usbForce1ms)
                    val pc = (context as? com.rawsmusic.MainActivity)?.playerController
                    if (pc?.playState?.value == PlayState.PLAYING) pc.pause()
                }
                SwitchRow("线性音量 ", usbLinearVol) { checked ->
                    usbLinearVol = checked
                    AppPreferences.Player.usbLinearVolume = checked
                    UsbAudioEngine.nativeSetUsbDacSettings(usbNoCI, false, checked, false, usbForce1ms)
                    val pc = (context as? com.rawsmusic.MainActivity)?.playerController
                    if (pc?.playState?.value == PlayState.PLAYING) pc.pause()
                }
                SwitchRow("强制 1ms 包间隔", usbForce1ms) { checked ->
                    usbForce1ms = checked
                    AppPreferences.Player.usbForce1MsPacket = checked
                    UsbAudioEngine.nativeSetUsbDacSettings(usbNoCI, false, usbLinearVol, false, checked)
                    val pc = (context as? com.rawsmusic.MainActivity)?.playerController
                    if (pc?.playState?.value == PlayState.PLAYING) pc.pause()
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
