package com.rawsmusic.ui.settings

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun themeColors(): ThemeColors {
    val context = LocalContext.current
    val isDark = com.rawsmusic.core.ui.theme.ThemeManager.isDarkMode(context)
    return if (isDark) ThemeColors(
        background = Color(0xFF2A2624),
        surface = Color(0xFF353130),
        onSurface = Color.White,
        onSurfaceVariant = Color(0xCCFFFFFF),
        outline = Color(0xFF9F8D80),
        primary = Color.White,
        onPrimary = Color(0xFF3E2D1A),
        primaryContainer = Color(0xFF57432E),
        onPrimaryContainer = Color.White,
        secondaryText = Color(0xCCFFFFFF)
    ) else ThemeColors(
        background = Color(0xFFF8F7FC),
        surface = Color(0xFFE4E6F2),
        onSurface = Color.Black,
        onSurfaceVariant = Color(0x8A000000),
        outline = Color(0xFF8A8E9C),
        primary = Color.Black,
        onPrimary = Color.White,
        primaryContainer = Color(0xFFE9EEF8),
        onPrimaryContainer = Color.Black,
        secondaryText = Color(0x8A000000)
    )
}

internal data class ThemeColors(
    val background: Color,
    val surface: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val outline: Color,
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondaryText: Color
)

@Composable
internal fun appFontFamily(): FontFamily {
    val tf = com.rawsmusic.module.data.prefs.FontManager.typeface
    return if (tf != null) FontFamily(tf) else FontFamily.Default
}

/**
 * 设置页面模板。
 * 使用 Miuix SmallTopAppBar。
 */
@Composable
internal fun SettingsPage(
    title: String,
    onBack: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val isDark = MiuixTheme.colorScheme.background.luminance() < 0.5f
    val pageBackground = if (isDark) Color(0xFF101014) else Color(0xFFF4F4F7)

    Column(
        Modifier
            .fillMaxSize()
            .background(pageBackground)
    ) {
        SmallTopAppBar(
            title = title,
            color = pageBackground,
            titleColor = MiuixTheme.colorScheme.onBackground,
            navigationIcon = {
                if (onBack != null) {
                    top.yukonga.miuix.kmp.basic.IconButton(onClick = onBack) {
                        top.yukonga.miuix.kmp.basic.Icon(
                            imageVector = MiuixIcons.Regular.Back,
                            contentDescription = "返回",
                            tint = MiuixTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))
            content()
            Spacer(modifier = Modifier.height(180.dp))
        }
    }
}

@Composable
internal fun SettingsActionRow(
    title: String,
    description: String? = null,
    onClick: () -> Unit
) {
    ArrowPreference(
        title = title,
        summary = description.orEmpty(),
        onClick = onClick
    )
}

/**
 * 设置主页面。
 * 使用 Miuix 组件。
 */
@Composable
fun LiquidGlassSettingsScreen(
    onNavigateToLyricManagement: () -> Unit,
    onNavigateToStatusBarLyric: () -> Unit,
    onNavigateToAppearance: () -> Unit,
    onNavigateToAudioSettings: () -> Unit,
    onNavigateToAudioEffects: () -> Unit,
    onNavigateToPlayerInterface: () -> Unit,
    onNavigateToUsbDac: () -> Unit,
    onNavigateToGlobalFont: () -> Unit,
    onNavigateToAlbumArt: () -> Unit,
    onWebDavBackup: () -> Unit,
    onNavigateToAbout: () -> Unit = {},
    onNavigateToScanSettings: () -> Unit = {}
) {
    val isDark = MiuixTheme.colorScheme.background.luminance() < 0.5f
    val pageBackground = if (isDark) Color(0xFF101014) else Color(0xFFF4F4F7)

    // 搜索状态
    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }

    // 可搜索的设置项
    val searchableItems = remember(
        onNavigateToAudioSettings, onNavigateToUsbDac, onNavigateToAudioEffects,
        onNavigateToPlayerInterface, onNavigateToAppearance, onNavigateToLyricManagement,
        onNavigateToStatusBarLyric, onNavigateToGlobalFont, onNavigateToAlbumArt, onWebDavBackup
    ) {
        listOf(
            SearchableSetting("音质设置", "采样率 位深 输出模式 重采样 sample rate", onNavigateToAudioSettings),
            SearchableSetting("USB DAC", "USB 独占 DAC PCM DSD 解码器", onNavigateToUsbDac),
            SearchableSetting("音频效果", "均衡器 EQ 混响 reverb bass treble", onNavigateToAudioEffects),
            SearchableSetting("播放器界面", "播放器 专辑图 控制 按钮 player", onNavigateToPlayerInterface),
            SearchableSetting("外观", "主题 字体 颜色 暗色 深色 dark theme", onNavigateToAppearance),
            SearchableSetting("歌词管理", "歌词 字体 大小 lrc lyric", onNavigateToLyricManagement),
            SearchableSetting("状态栏歌词", "状态栏 通知 lyric status bar", onNavigateToStatusBarLyric),
            SearchableSetting("全局字体", "字体 font typeface global", onNavigateToGlobalFont),
            SearchableSetting("专辑图", "封面 缓存 高清 album art cover", onNavigateToAlbumArt),
            SearchableSetting("WebDAV 备份", "备份 恢复 云同步 backup restore", onWebDavBackup),
            SearchableSetting("关于", "关于 版本 项目 开源 about version", onNavigateToAbout),
            SearchableSetting("扫描设置", "扫描 文件夹 重新扫描 短曲 播放次数 进度 scan folder", onNavigateToScanSettings),
        )
    }

    val filteredItems = if (searchQuery.isBlank()) emptyList()
    else searchableItems.filter { item ->
        item.title.contains(searchQuery, ignoreCase = true) ||
                item.keywords.contains(searchQuery, ignoreCase = true)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(pageBackground)
    ) {
        SmallTopAppBar(
            title = "设置",
            color = pageBackground,
            titleColor = MiuixTheme.colorScheme.onBackground,
            navigationIcon = {}
        )

        // 搜索框
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
        ) {
            TextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                label = "搜索设置"
            )
        }

        if (searchQuery.isNotBlank() && filteredItems.isNotEmpty()) {
            // 搜索结果
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp)
            ) {
                SmallTitle(text = "搜索结果")
                SettingsCardGroup {
                    Column {
                        filteredItems.forEach { item ->
                            ArrowPreference(
                                title = item.title,
                                summary = "点击进入",
                                onClick = {
                                    searchQuery = ""
                                    item.onClick()
                                }
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        } else if (searchQuery.isNotBlank() && filteredItems.isEmpty()) {
            // 无结果
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "未找到匹配的设置项",
                    color = MiuixTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                    fontSize = 14.sp
                )
            }
        } else {
            // 正常设置列表
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp)
            ) {
                Spacer(modifier = Modifier.height(8.dp))

            SmallTitle(text = "音频输出")

            SettingsCardGroup {
                Column {
                    ArrowPreference(
                        title = "音质设置",
                        summary = "采样率、位深、输出模式与重采样",
                        onClick = onNavigateToAudioSettings
                    )
                    ArrowPreference(
                        title = "USB DAC",
                        summary = "USB 独占、DAC 状态、PCM 输出与 DSD",
                        onClick = onNavigateToUsbDac
                    )
                }
            }

            SmallTitle(text = "播放与界面")

            SettingsCardGroup {
                Column {
                    ArrowPreference(
                        title = "界面设置",
                        summary = "默认背景、沉浸模式、常驻封面、音频可视化",
                        onClick = onNavigateToPlayerInterface
                    )
                    ArrowPreference(
                        title = "专辑图",
                        summary = "画质、高清封面、24位 RGB、封面下载与动画",
                        onClick = onNavigateToAlbumArt
                    )
                    ArrowPreference(
                        title = "外观主题",
                        summary = "主题模式、界面色彩与显示风格",
                        onClick = onNavigateToAppearance
                    )
                    ArrowPreference(
                        title = "全局字体",
                        summary = "字体大小、字重、斜体与全局显示",
                        onClick = onNavigateToGlobalFont
                    )
                }
            }

            SmallTitle(text = "歌词")

            SettingsCardGroup {
                Column {
                    ArrowPreference(
                        title = "歌词管理",
                        summary = "歌词源、歌词字体设置与歌词显示",
                        onClick = onNavigateToLyricManagement
                    )
                    ArrowPreference(
                        title = "状态栏歌词",
                        summary = "Flyme、三星、蓝牙、Lyric Getter",
                        onClick = onNavigateToStatusBarLyric
                    )
                }
            }

            SmallTitle(text = "媒体库")

            SettingsCardGroup {
                Column {
                    ArrowPreference(
                        title = "扫描设置",
                        summary = "音乐文件夹、重新扫描、短曲过滤、播放次数和进度恢复",
                        onClick = onNavigateToScanSettings
                    )
                }
            }

            SmallTitle(text = "数据")

            SettingsCardGroup {
                Column {
                    ArrowPreference(
                        title = "WebDAV 备份",
                        summary = "备份与恢复歌单、统计数据和应用配置",
                        onClick = onWebDavBackup
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            SettingsCardGroup {
                Column {
                    ArrowPreference(
                        title = "关于 RawS Music",
                        summary = "版本、核心功能、项目主页与开源组件",
                        onClick = onNavigateToAbout
                    )
                }
            }

            Spacer(modifier = Modifier.height(180.dp))
            }
        }
    }
}

private data class SearchableSetting(
    val title: String,
    val keywords: String,
    val onClick: () -> Unit
)

@Composable
private fun MainSettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    SmallTitle(text = title)
    SettingsCardGroup { Column(content = content) }
}

@Composable
internal fun SettingsCardGroup(
    content: @Composable () -> Unit
) {
    val isDark = MiuixTheme.colorScheme.background.luminance() < 0.5f
    val cardColor = if (isDark) Color(0xFF1E1E1E) else Color.White

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(cardColor)
            .padding(vertical = 4.dp)
    ) {
        content()
    }
    Spacer(modifier = Modifier.height(12.dp))
}

@Composable
internal fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    MainSettingsSection(title = title, content = content)
}

@Composable
internal fun SettingsNavigationEntry(
    title: String,
    description: String,
    onClick: () -> Unit
) {
    ArrowPreference(
        title = title,
        summary = description,
        onClick = onClick
    )
}

@Composable
internal fun SettingsInfoEntry(
    title: String,
    description: String
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(
            title,
            fontSize = 15.sp,
            fontWeight = FontWeight.Normal,
            color = MiuixTheme.colorScheme.onBackground
        )
        Text(
            description,
            fontSize = 11.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
internal fun SectionHeader(title: String) {
    SmallTitle(text = title)
}

@Composable
internal fun Divider() {
}

@Composable
internal fun SettingsCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val isDark = MiuixTheme.colorScheme.background.luminance() < 0.5f
    val cardColor = if (isDark) Color(0xFF1E1E1E) else Color.White
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(cardColor)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        content = content
    )
    Spacer(modifier = Modifier.height(12.dp))
}

@Composable
fun SwitchRow(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    SwitchPreference(
        title = label,
        checked = checked,
        enabled = enabled,
        onCheckedChange = onCheckedChange
    )
}

// ==================== 液态玻璃组件（保留供后续扩展） ====================

private val isBackdropSupported: Boolean by lazy {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
        android.util.Log.d("BackdropCompat", "Not supported: API ${Build.VERSION.SDK_INT} < S")
        return@lazy false
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        try {
            android.graphics.RuntimeShader("half4 main(float2 c) { return half4(1.0); }")
            android.util.Log.d("BackdropCompat", "Supported: RuntimeShader test passed on API ${Build.VERSION.SDK_INT}")
            true
        } catch (e: Throwable) {
            android.util.Log.e("BackdropCompat", "Not supported: RuntimeShader test failed", e)
            false
        }
    } else {
        android.util.Log.d("BackdropCompat", "Supported: API ${Build.VERSION.SDK_INT} between S and TIRAMISU")
        true
    }
}

private const val USE_FALLBACK_CARDS = true

@Composable
fun LiquidGlassCard(
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    lightweight: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    if (USE_FALLBACK_CARDS || !isBackdropSupported) {
        LiquidGlassCardFallback(modifier, content)
        return
    }

    if (lightweight) {
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

@Composable
private fun LiquidGlassCardFallback(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val isDark = MiuixTheme.colorScheme.background.luminance() < 0.5f
    val cardColor = if (isDark) Color(0xFF353130) else Color(0xFFE4E6F2)
    Column(
        modifier
            .shadow(4.dp, RoundedCornerShape(24.dp), ambientColor = Color(0x1A000000))
            .clip(RoundedCornerShape(24.dp))
            .background(cardColor.copy(alpha = 0.85f))
            .padding(16.dp),
        content = content
    )
}

@Composable
fun GlassButton(
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}
