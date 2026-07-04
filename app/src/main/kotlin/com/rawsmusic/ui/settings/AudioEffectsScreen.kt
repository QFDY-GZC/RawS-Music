package com.rawsmusic.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.module.data.prefs.AppPreferences

@Composable
fun LiquidGlassAudioEffectsScreen(
    onNavigateToPEQ: () -> Unit,
    onNavigateToGraphicEQ: () -> Unit,
    onNavigateToSpatialSound: () -> Unit,
    onNavigateToCompressor: () -> Unit,
    onNavigateToBassTreble: () -> Unit,
    onNavigateToSurround360: () -> Unit,
    onNavigateToPanoramic360: () -> Unit,
    onTogglePEQ: (Boolean) -> Unit,
    onToggleCompressor: (Boolean) -> Unit,
    onToggleSurround360: (Boolean) -> Unit,
    onTogglePanoramic360: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    var peqEnabled by remember { mutableStateOf(AppPreferences.PEQ.isEnabled) }
    var compressorEnabled by remember { mutableStateOf(AppPreferences.Compressor.isEnabled) }
    SettingsPage(title = "音效设置", onBack = onBack) {
        SettingsSection("均衡") {
            SwitchRow("启用参量均衡器", peqEnabled) { checked ->
                peqEnabled = checked
                onTogglePEQ(checked)
            }
            SettingsNavigationEntry(
                title = "参量均衡器",
                description = "滤波器、前级增益、AutoEQ 与频响曲线",
                onClick = onNavigateToPEQ
            )
            SettingsNavigationEntry(
                title = "图形均衡器",
                description = "10-40 段固定频率滑块式均衡、内置预设",
                onClick = onNavigateToGraphicEQ
            )
        }

        SettingsSection("动态") {
            SwitchRow("启用压限器", compressorEnabled) { checked ->
                compressorEnabled = checked
                onToggleCompressor(checked)
            }
            SettingsNavigationEntry(
                title = "压限器",
                description = "阈值、压缩比、启动释放、补偿增益",
                onClick = onNavigateToCompressor
            )
        }

        SettingsSection("频率增强") {
            SettingsNavigationEntry(
                title = "低音 / 高音增强",
                description = "低频架、高频架和转折频率",
                onClick = onNavigateToBassTreble
            )
        }

        SettingsSection("空间") {
            SettingsNavigationEntry(
                title = "360° 环绕音",
                description = "水平面双耳渲染、强度和旋转速度",
                onClick = onNavigateToSurround360
            )
            SettingsNavigationEntry(
                title = "360° 全景音",
                description = "3D 方位、仰角、反射和空间混响",
                onClick = onNavigateToPanoramic360
            )
        }

        SettingsSection("立体声") {
            SettingsNavigationEntry(
                title = "立体声扩展",
                description = "声场宽度、虚拟器和互馈 Crossfeed",
                onClick = onNavigateToSpatialSound
            )
        }
    }
}
