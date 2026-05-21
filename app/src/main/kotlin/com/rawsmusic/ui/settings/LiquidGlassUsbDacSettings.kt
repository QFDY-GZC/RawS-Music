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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.core.common.model.PlayState
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.player.usb.UsbAudioEngine

@Composable
fun LiquidGlassUsbDacSettingsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val colors = themeColors()

    var bitPerfect by remember { mutableStateOf(AppPreferences.Player.bitPerfectEnabled) }
    var hardwareFU by remember { mutableStateOf(AppPreferences.Player.hardwareFeatureUnitEnabled) }
    var usbNoCI by remember { mutableStateOf(AppPreferences.Player.usbNoControlInterface) }
    var usbLinearVol by remember { mutableStateOf(AppPreferences.Player.usbLinearVolume) }
    var usbForce1ms by remember { mutableStateOf(AppPreferences.Player.usbForce1MsPacket) }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        Spacer(Modifier.height(16.dp))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) {
                Text("\u2190 \u8fd4\u56de", color = colors.primary, fontSize = 16.sp, fontFamily = appFontFamily())
            }
            Text(
                "USB DAC \u8bbe\u7f6e",
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                color = colors.onSurface,
                fontFamily = appFontFamily()
            )
            Spacer(Modifier.weight(1f))
        }

        Spacer(Modifier.height(8.dp))

        SettingsCard {
            SectionHeader("USB DAC")
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
            SwitchRow("\u786c\u4ef6\u97f3\u91cf\u63a7\u5236", hardwareFU) { checked ->
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

        Spacer(Modifier.height(12.dp))

        SettingsCard {
            SectionHeader("USB DAC \u9ad8\u7ea7\u8bbe\u7f6e")
            Text(
                "\u89e3\u51b3\u7535\u6d41\u58f0/\u6742\u97f3\uff08\u5982\u679c\u8bbe\u5907\u6709\uff0c\u53ef\u80fd\u5bf9\u4f60\u6709\u5e2e\u52a9\uff09",
                fontSize = 13.sp,
                color = colors.secondaryText,
                modifier = Modifier.padding(top = 2.dp),
                fontFamily = appFontFamily()
            )
            Spacer(Modifier.height(12.dp))
            SwitchRow("\u8df3\u8fc7 AC \u63a5\u53e3", usbNoCI) { checked ->
                usbNoCI = checked
                AppPreferences.Player.usbNoControlInterface = checked
                UsbAudioEngine.nativeSetUsbDacSettings(checked, false, usbLinearVol, false, usbForce1ms)
                val pc = (context as? com.rawsmusic.MainActivity)?.playerController
                if (pc?.playState?.value == PlayState.PLAYING) pc.pause()
            }
            SwitchRow("\u7ebf\u6027\u97f3\u91cf", usbLinearVol) { checked ->
                usbLinearVol = checked
                AppPreferences.Player.usbLinearVolume = checked
                UsbAudioEngine.nativeSetUsbDacSettings(usbNoCI, false, checked, false, usbForce1ms)
                val pc = (context as? com.rawsmusic.MainActivity)?.playerController
                if (pc?.playState?.value == PlayState.PLAYING) pc.pause()
            }
            SwitchRow("\u5f3a\u5236 1ms \u5305\u95f4\u9694", usbForce1ms) { checked ->
                usbForce1ms = checked
                AppPreferences.Player.usbForce1MsPacket = checked
                UsbAudioEngine.nativeSetUsbDacSettings(usbNoCI, false, usbLinearVol, false, checked)
                val pc = (context as? com.rawsmusic.MainActivity)?.playerController
                if (pc?.playState?.value == PlayState.PLAYING) pc.pause()
            }
        }

        Spacer(Modifier.height(32.dp))
    }
}
