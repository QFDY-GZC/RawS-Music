package com.rawsmusic.ui.settings

import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.data.prefs.FontManager
import com.rawsmusic.module.player.GlobalSettingsViewModel
import com.rawsmusic.module.player.LyriconProviderManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LiquidGlassSettingsScreen(
    onNavigateToAudioSettings: () -> Unit,
    onPickFolder: () -> Unit,
    onPickFont: () -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val backdrop = rememberLayerBackdrop()

    val lyriconPrefs = AppPreferences.Lyricon
    var lyriconEnabled by remember { mutableStateOf(lyriconPrefs.enabled) }
    var lyriconTranslation by remember { mutableStateOf(lyriconPrefs.displayTranslation) }
    var lyriconRoma by remember { mutableStateOf(lyriconPrefs.displayRoma) }
    var lyriconStatus by remember { mutableStateOf(if (!lyriconEnabled) "未启用" else if (LyriconProviderManager.isConnected()) "已连接" else "未连接") }

    var scanPaths by remember { mutableStateOf(AppPreferences.UI.scanPaths) }
    var isScanning by remember { mutableStateOf(false) }

    LaunchedEffect(lyriconEnabled) {
        if (lyriconEnabled) {
            LyriconProviderManager.onConnectionStatusChanged = { status ->
                lyriconStatus = when {
                    !AppPreferences.Lyricon.enabled -> "未启用"
                    status == io.github.proify.lyricon.provider.ConnectionStatus.CONNECTED -> "已连接"
                    status == io.github.proify.lyricon.provider.ConnectionStatus.CONNECTING -> "连接中…"
                    else -> "未连接"
                }
            }
        } else {
            LyriconProviderManager.onConnectionStatusChanged = null
        }
    }

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

            Text(
                "设置",
                fontSize = 28.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF1C1B1F)
            )

            Spacer(Modifier.height(8.dp))

            LiquidGlassCard(backdrop) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "词幕（Lyricon）",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF1C1B1F)
                        )
                        Text(
                            lyriconStatus,
                            fontSize = 12.sp,
                            color = Color(0xFF79747E)
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))

                Text(
                    "向系统状态栏推送歌词显示。需要安装词幕中央服务才能连接。",
                    fontSize = 12.sp,
                    color = Color(0xFF79747E)
                )

                Spacer(Modifier.height(12.dp))

                SwitchRow("启用词幕推送", lyriconEnabled) { checked ->
                    lyriconEnabled = checked
                    lyriconPrefs.enabled = checked
                    if (checked) {
                        LyriconProviderManager.init(context.applicationContext, com.rawsmusic.R.mipmap.ic_launcher)
                        lyriconStatus = if (LyriconProviderManager.isConnected()) "已连接" else "未连接"
                    } else {
                        LyriconProviderManager.stopPositionSync()
                        LyriconProviderManager.destroy()
                        lyriconStatus = "未启用"
                    }
                }

                SwitchRow("显示翻译", lyriconTranslation, enabled = lyriconEnabled) { checked ->
                    lyriconTranslation = checked
                    LyriconProviderManager.setDisplayTranslation(checked)
                }

                SwitchRow("显示罗马音", lyriconRoma, enabled = lyriconEnabled) { checked ->
                    lyriconRoma = checked
                    LyriconProviderManager.setDisplayRoma(checked)
                }
            }

            LiquidGlassCard(backdrop) {
                Text(
                    "音质设置",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF1C1B1F)
                )
                Spacer(Modifier.height(12.dp))
                GlassButton(onClick = onNavigateToAudioSettings) {
                    Text("进入音质设置", color = Color(0xFF6750A4), fontSize = 14.sp)
                }
            }

            var immersiveEnabled by remember { mutableStateOf(AppPreferences.UI.isImmersiveEnabled) }
            var miniCoverEnabled by remember { mutableStateOf(AppPreferences.UI.isMiniCoverEnabled) }
            LiquidGlassCard(backdrop) {
                Text(
                    "播放界面",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF1C1B1F)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "自定义播放界面的视觉效果",
                    fontSize = 12.sp,
                    color = Color(0xFF79747E)
                )
                Spacer(Modifier.height(12.dp))
                SwitchRow("沉浸模式", immersiveEnabled) { checked ->
                    immersiveEnabled = checked
                    AppPreferences.UI.isImmersiveEnabled = checked
                    android.content.Intent("com.rawsmusic.action.IMMERSIVE_SETTING_CHANGED").also {
                        it.setPackage(context.packageName)
                        context.sendBroadcast(it)
                    }
                }
                Text(
                    "进入播放界面时显示专辑封面背景",
                    fontSize = 11.sp,
                    color = Color(0xFF79747E)
                )
                Spacer(Modifier.height(8.dp))
                SwitchRow("主界面常驻封面", miniCoverEnabled) { checked ->
                    miniCoverEnabled = checked
                    AppPreferences.UI.isMiniCoverEnabled = checked
                    android.content.Intent("com.rawsmusic.action.MINI_COVER_SETTING_CHANGED").also {
                        it.setPackage(context.packageName)
                        context.sendBroadcast(it)
                    }
                }
                Text(
                    "在主界面胶囊栏显示专辑封面",
                    fontSize = 11.sp,
                    color = Color(0xFF79747E)
                )
            }

            LiquidGlassCard(backdrop) {
                Text(
                    "扫描文件夹",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF1C1B1F)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (scanPaths.isEmpty()) "未指定则扫描全部音乐" else "仅扫描以下文件夹",
                    fontSize = 12.sp,
                    color = Color(0xFF79747E)
                )

                if (scanPaths.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    for (path in scanPaths) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                path.substringAfterLast("/"),
                                fontSize = 14.sp,
                                color = Color(0xFF49454F),
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = {
                                val current = AppPreferences.UI.scanPaths.toMutableList()
                                current.remove(path)
                                AppPreferences.UI.scanPaths = current
                                scanPaths = current
                            }) {
                                Text("移除", color = Color(0xFFFF5722), fontSize = 13.sp)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                GlassButton(onClick = onPickFolder) {
                    Text("添加文件夹", color = Color(0xFF6750A4), fontSize = 14.sp)
                }
            }

            LiquidGlassCard(backdrop) {
                Text(
                    "重新扫描",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF1C1B1F)
                )
                Spacer(Modifier.height(12.dp))
                GlassButton(
                    onClick = {
                        if (isScanning) return@GlassButton
                        isScanning = true
                        coroutineScope.launch {
                            try {
                                val result = withContext(Dispatchers.IO) {
                                    com.rawsmusic.module.data.repository.MusicRepository.clearAll()
                                    val customPaths = AppPreferences.UI.scanPaths
                                    val songs = mutableListOf<com.rawsmusic.core.common.model.AudioFile>()
                                    com.rawsmusic.module.scanner.MediaStoreScanner.scan(context, customPaths, quickScan = false)
                                        .collect { progress ->
                                            when (progress) {
                                                is com.rawsmusic.module.scanner.ScanProgress.Completed -> {
                                                    songs.addAll(progress.songs)
                                                }
                                                else -> {}
                                            }
                                        }
                                    com.rawsmusic.module.data.repository.MusicRepository.replaceAllSongs(songs)
                                    songs.size
                                }
                                Toast.makeText(context, "扫描完成，共 $result 首歌曲", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                Toast.makeText(context, "扫描失败: ${e.message}", Toast.LENGTH_SHORT).show()
                            } finally {
                                isScanning = false
                            }
                        }
                    }
                ) {
                    Text(
                        if (isScanning) "扫描中…" else "重新扫描",
                        color = Color(0xFF6750A4),
                        fontSize = 14.sp
                    )
                }
            }

            var customFontPath by remember { mutableStateOf(AppPreferences.UI.customFontPath) }
            var fontWeight by remember { mutableStateOf(AppPreferences.UI.fontWeight) }
            var fontItalic by remember { mutableStateOf(AppPreferences.UI.fontItalic) }
            val fontContext = LocalContext.current

            LiquidGlassCard(backdrop) {
                Text(
                    "自定义字体",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF1C1B1F)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (customFontPath.isBlank()) "使用系统默认字体" else customFontPath.substringAfterLast("/"),
                    fontSize = 12.sp,
                    color = Color(0xFF79747E)
                )
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassButton(onClick = onPickFont) {
                        Text("选择字体文件", color = Color(0xFF6750A4), fontSize = 14.sp)
                    }
                    if (customFontPath.isNotBlank()) {
                        GlassButton(onClick = {
                            customFontPath = ""
                            AppPreferences.UI.customFontPath = ""
                        }) {
                            Text("重置", color = Color(0xFFFF5722), fontSize = 14.sp)
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text("字重: ${String.format("%.1f", fontWeight)}", fontSize = 13.sp, color = Color(0xFF49454F))
                Slider(
                    value = fontWeight,
                    onValueChange = {
                        fontWeight = it
                        AppPreferences.UI.fontWeight = it
                        FontManager.init(fontContext)
                    },
                    valueRange = 0.5f..2f,
                    colors = SliderDefaults.colors(thumbColor = Color(0xFF6750A4), activeTrackColor = Color(0xFF6750A4))
                )

                Text("斜体: ${String.format("%.1f", fontItalic)}", fontSize = 13.sp, color = Color(0xFF49454F))
                Slider(
                    value = fontItalic,
                    onValueChange = {
                        fontItalic = it
                        AppPreferences.UI.fontItalic = it
                        FontManager.init(fontContext)
                    },
                    valueRange = -0.5f..0.5f,
                    colors = SliderDefaults.colors(thumbColor = Color(0xFF6750A4), activeTrackColor = Color(0xFF6750A4))
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * 检测设备是否支持 backdrop 渲染效果。
 * 在某些定制 ROM（如澎湃3/HyperOS 3）上，AGSL RuntimeShader 或 RenderEffect 链
 * 可能因 GPU 驱动不兼容而导致原生崩溃。
 */
private val isBackdropSupported: Boolean by lazy {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return@lazy false
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        try {
            android.graphics.RuntimeShader("half4 main(float2 c) { return half4(1.0); }")
            true
        } catch (_: Throwable) {
            false
        }
    } else true
}

@Composable
fun LiquidGlassCard(
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    lightweight: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    if (!isBackdropSupported) {
        LiquidGlassCardFallback(modifier, content)
        return
    }

    if (lightweight) {
        // 轻量模式：跳过昂贵的 lens AGSL shader，只用 blur + vibrancy
        LiquidGlassCardLightweight(backdrop, modifier, content)
        return
    }

    var isPressed by remember { mutableStateOf(false) }
    var pressOffsetX by remember { mutableStateOf(0f) }
    var pressOffsetY by remember { mutableStateOf(0f) }

    val rotationX by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (isPressed) -pressOffsetY * 6f else 0f,
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
            stiffness = androidx.compose.animation.core.Spring.StiffnessMedium
        ),
        label = "rotationX"
    )
    val rotationY by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (isPressed) pressOffsetX * 6f else 0f,
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
            stiffness = androidx.compose.animation.core.Spring.StiffnessMedium
        ),
        label = "rotationY"
    )
    val scale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (isPressed) 0.98f else 1f,
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
            stiffness = androidx.compose.animation.core.Spring.StiffnessMedium
        ),
        label = "scale"
    )
    val context = LocalContext.current
    val cameraDistance by remember { mutableStateOf(12f * context.resources.displayMetrics.density) }

    Column(
        modifier
            .graphicsLayer {
                this.cameraDistance = cameraDistance
                scaleX = scale
                scaleY = scale
                this.rotationX = rotationX
                this.rotationY = rotationY
            }
            .clip(RoundedCornerShape(24.dp))
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = { offset ->
                        val centerX = size.width / 2f
                        val centerY = size.height / 2f
                        pressOffsetX = (offset.x - centerX) / centerX
                        pressOffsetY = (offset.y - centerY) / centerY
                        isPressed = true
                        tryAwaitRelease()
                        isPressed = false
                    }
                )
            }
            .drawBackdrop(
                backdrop = backdrop,
                shape = { RoundedCornerShape(24.dp) },
                effects = {
                    vibrancy(1.5f)
                    blur(8.dp.toPx())
                    lens(24.dp.toPx(), 48.dp.toPx(), depthEffect = true)
                },
                highlight = { Highlight.Plain },
                shadow = { Shadow.Default }
            )
            .padding(16.dp),
        content = content
    )
}

/**
 * 轻量级 backdrop 卡片：只用 blur + vibrancy，跳过昂贵的 lens AGSL shader。
 * 适用于滚动列表中的卡片，避免大量卡片同时渲染导致卡顿。
 */
@Composable
private fun LiquidGlassCardLightweight(
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier
            .clip(RoundedCornerShape(24.dp))
            .drawBackdrop(
                backdrop = backdrop,
                shape = { RoundedCornerShape(24.dp) },
                effects = {
                    vibrancy(1.2f)
                    blur(6.dp.toPx())
                },
                highlight = { Highlight.Plain },
                shadow = { Shadow.Default }
            )
            .padding(16.dp),
        content = content
    )
}

/**
 * 不支持 backdrop 渲染时的降级卡片，使用简单半透明背景+阴影
 */
@Composable
private fun LiquidGlassCardFallback(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier
            .shadow(4.dp, RoundedCornerShape(24.dp), ambientColor = Color(0x1A000000))
            .clip(RoundedCornerShape(24.dp))
            .background(Color.White.copy(alpha = 0.85f))
            .padding(16.dp),
        content = content
    )
}

@Composable
fun SwitchRow(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            fontSize = 14.sp,
            color = if (enabled) Color(0xFF49454F) else Color(0xFF79747E)
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF6750A4),
                checkedTrackColor = Color(0xFFD0BCFF)
            )
        )
    }
}

@Composable
fun GlassButton(
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        content()
    }
}
