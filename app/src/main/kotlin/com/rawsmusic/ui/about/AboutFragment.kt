package com.rawsmusic.ui.about

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.NavHostFragment
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.rawsmusic.ui.settings.LiquidGlassCard

class AboutFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                AboutScreen(
                    onBack = {
                        try {
                            NavHostFragment.findNavController(this@AboutFragment).navigateUp()
                        } catch (_: Exception) {}
                    }
                )
            }
        }
    }
}

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val backdrop = rememberLayerBackdrop()

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
                    "关于",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF1C1B1F)
                )
            }

            Spacer(Modifier.height(8.dp))

            Text(
                "RawS Music",
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1C1B1F),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "版本 1.1.0",
                fontSize = 14.sp,
                color = Color(0xFF79747E),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(16.dp))

            LiquidGlassCard(backdrop) {
                Text("作者", fontSize = 13.sp, color = Color(0xFF79747E))
                Spacer(Modifier.height(8.dp))
                Text("QFDY", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1C1B1F))
                Text("个人开发者", fontSize = 14.sp, color = Color(0xFF79747E))
                Spacer(Modifier.height(8.dp))
                Text(
                    "打造纯粹的本地音乐播放器。",
                    fontSize = 14.sp, color = Color(0xFF49454F), lineHeight = 20.sp
                )
            }

            LiquidGlassCard(backdrop) {
                Text("开源库", fontSize = 13.sp, color = Color(0xFF79747E))
                Spacer(Modifier.height(12.dp))

                OpenSourceItem("FFmpeg + AudioTrack", "Google", "强大的媒体播放框架，支持多种音频格式和高级播放功能。", "Apache License 2.0")
                Spacer(Modifier.height(12.dp))
                OpenSourceItem("Coil", "Coil Contributors", "高效加载和缓存图片，用于专辑封面显示。", "BSD, MIT, Apache License 2.0")
                Spacer(Modifier.height(12.dp))
                OpenSourceItem("Material Components", "Google", "Material Design 3 组件库，提供现代化的 UI 组件。", "Apache License 2.0")
                Spacer(Modifier.height(12.dp))
                OpenSourceItem("Kotlin Coroutines", "JetBrains", "Kotlin 协程库，用于异步编程和并发处理。", "Apache License 2.0")
                Spacer(Modifier.height(12.dp))
                OpenSourceItem("AndroidX Navigation", "Google", "Android Jetpack 导航组件，管理应用内页面跳转。", "Apache License 2.0")
                Spacer(Modifier.height(12.dp))
                OpenSourceItem("JAudioTagger", "JAudioTagger Team", "音频元数据读取库，用于解析歌曲标签信息。", "LGPL v2.1")
                Spacer(Modifier.height(12.dp))
                OpenSourceItem("Backdrop-Compose", "Kyant", "Compose 液态玻璃，用于实现精美的 UI。", "Apache License 2.0")
                Spacer(Modifier.height(12.dp))
                OpenSourceItem("libusb", "libusb Contributors", "USB 设备访问库，用于 USB DAC 音频输出。", "LGPL v2.1")
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun OpenSourceItem(name: String, author: String, desc: String, license: String) {
    Column {
        Text(name, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1C1B1F))
        Text(author, fontSize = 13.sp, color = Color(0xFF79747E))
        Text(desc, fontSize = 14.sp, color = Color(0xFF49454F), lineHeight = 20.sp)
        Text(license, fontSize = 12.sp, color = Color(0xFF79747E))
    }
}

@Composable
private fun Row(
    modifier: Modifier,
    horizontalArrangement: Arrangement.Horizontal,
    verticalAlignment: Alignment.Vertical,
    content: @Composable () -> Unit
) {
    androidx.compose.foundation.layout.Row(
        modifier,
        horizontalArrangement = horizontalArrangement,
        verticalAlignment = verticalAlignment
    ) {
        content()
    }
}
