package com.rawsmusic.ui.settings

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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

@Composable
internal fun SettingsPage(
    title: String,
    onBack: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = themeColors()
    val context = LocalContext.current
    val pageBackground = colors.background
    Column(
        Modifier
            .fillMaxSize()
            .background(pageBackground)
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        Spacer(Modifier.height(18.dp))
        if (onBack == null) {
            Text(
                title,
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface,
                modifier = Modifier.padding(horizontal = 8.dp),
                fontFamily = appFontFamily()
            )
        } else {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onBack) {
                    Text("← 返回", color = colors.primary, fontSize = 14.sp, fontFamily = appFontFamily())
                }
                Text(
                    title,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.onSurface,
                    fontFamily = appFontFamily()
                )
                Spacer(Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(22.dp))
        content()
        Spacer(Modifier.height(180.dp))
    }
}

@Composable
internal fun SettingsActionRow(
    title: String,
    description: String? = null,
    onClick: () -> Unit
) {
    SettingsEntryRow(title, description.orEmpty(), onClick)
}

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
    onNavigateToComposePlayerDemo: () -> Unit = {},
    onNavigateToComposeScene: () -> Unit = {}
) {
    val colors = themeColors()
    val isDark = colors.onSurface == Color.White
    val pageBackground = colors.background

    Column(
        Modifier
            .fillMaxSize()
            .background(pageBackground)
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Spacer(Modifier.height(18.dp))
        Text(
            "设置",
            fontSize = 24.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.onSurface,
            modifier = Modifier.padding(horizontal = 8.dp),
            fontFamily = appFontFamily()
        )
        Spacer(Modifier.height(26.dp))

        MainSettingsSection("播放与界面") {
            MainSettingsEntry(
                title = "界面设置",
                description = "默认背景、沉浸模式、常驻封面、音频可视化",
                onClick = onNavigateToPlayerInterface
            )
            MainSettingsEntry(
                title = "专辑图",
                description = "画质、高清封面、24位 RGB、封面下载与动画",
                onClick = onNavigateToAlbumArt
            )
            MainSettingsEntry(
                title = "外观主题",
                description = "主题模式、界面色彩与显示风格",
                onClick = onNavigateToAppearance
            )
            MainSettingsEntry(
                title = "全局字体",
                description = "字体大小、字重、斜体与全局显示",
                onClick = onNavigateToGlobalFont
            )
        }

        MainSettingsSection("歌词") {
            MainSettingsEntry(
                title = "歌词管理",
                description = "歌词源、歌词字体设置与歌词显示",
                onClick = onNavigateToLyricManagement
            )
            MainSettingsEntry(
                title = "状态栏歌词",
                description = "Flyme、三星、蓝牙、Lyric Getter",
                onClick = onNavigateToStatusBarLyric
            )
        }

        MainSettingsSection("音频") {
            MainSettingsEntry(
                title = "音质设置",
                description = "采样率、位深、输出模式与重采样",
                onClick = onNavigateToAudioSettings
            )
            MainSettingsEntry(
                title = "音效设置",
                description = "均衡器、动态范围、空间音频与增强",
                onClick = onNavigateToAudioEffects
            )
            MainSettingsEntry(
                title = "USB DAC",
                description = "USB 独占、DAC 状态、PCM 输出与 DSD",
                onClick = onNavigateToUsbDac
            )
        }

        MainSettingsSection("数据") {
            MainSettingsEntry(
                title = "WebDAV 备份",
                description = "备份与恢复歌单、统计数据和应用配置",
                onClick = onWebDavBackup
            )
        }

        MainSettingsSection("开发") {
            MainSettingsEntry(
                title = "Compose Player Demo",
                description = "纯 Compose 播放器演示：液态玻璃 + 捏合缩放 + 场景切换",
                onClick = onNavigateToComposePlayerDemo
            )
            MainSettingsEntry(
                title = "纯 Compose 场景系统",
                description = "纯 Compose 版本的统一容器：双引擎 + SceneParams + 场景切换",
                onClick = onNavigateToComposeScene
            )
        }

        Spacer(Modifier.height(180.dp))
    }
}

@Composable
private fun MainSettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = themeColors()
    val cardColor = colors.surface

    Text(
        title,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        color = colors.onSurface,
        modifier = Modifier.padding(start = 8.dp, bottom = 8.dp),
        fontFamily = appFontFamily()
    )
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(cardColor)
            .padding(vertical = 6.dp),
        content = content
    )
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun MainSettingsEntry(
    title: String,
    description: String,
    onClick: () -> Unit
) {
    val colors = themeColors()
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Normal,
                color = colors.onSurface,
                fontFamily = appFontFamily()
            )
            Text(
                description,
                fontSize = 11.sp,
                color = colors.secondaryText,
                modifier = Modifier.padding(top = 4.dp),
                fontFamily = appFontFamily()
            )
        }
        Text(
            "›",
            fontSize = 24.sp,
            color = colors.outline,
            modifier = Modifier.padding(start = 12.dp)
        )
    }
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
    MainSettingsEntry(title = title, description = description, onClick = onClick)
}

@Composable
internal fun SettingsInfoEntry(
    title: String,
    description: String
) {
    val colors = themeColors()
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(
            title,
            fontSize = 15.sp,
            fontWeight = FontWeight.Normal,
            color = colors.onSurface,
            fontFamily = appFontFamily()
        )
        Text(
            description,
            fontSize = 11.sp,
            color = colors.secondaryText,
            modifier = Modifier.padding(top = 4.dp),
            fontFamily = appFontFamily()
        )
    }
}

@Composable
private fun SettingsEntryRow(
    title: String,
    description: String,
    onClick: () -> Unit
) {
    val colors = themeColors()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = colors.onSurface,
                fontFamily = appFontFamily()
            )
            Text(
                description,
                fontSize = 13.sp,
                color = colors.secondaryText,
                modifier = Modifier.padding(top = 2.dp),
                fontFamily = appFontFamily()
            )
        }
        Text(
            "→",
            fontSize = 18.sp,
            color = colors.outline,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
    Divider()
}

// ==================== 通用组件 ====================

@Composable
internal fun SectionHeader(title: String) {
    val colors = themeColors()
    Text(
        title,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        color = colors.onSurface,
        modifier = Modifier.padding(start = 2.dp, top = 2.dp, bottom = 8.dp),
        fontFamily = appFontFamily()
    )
}

@Composable
internal fun Divider() {
}

@Composable
internal fun SettingsCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = themeColors()
    val cardColor = colors.surface
    Column(
        modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(cardColor)
            .padding(horizontal = 12.dp, vertical = 10.dp),
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
    val colors = themeColors()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            fontSize = 14.sp,
            color = if (enabled) colors.onSurface else colors.outline,
            modifier = Modifier.weight(1f),
            fontFamily = appFontFamily()
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            modifier = Modifier.padding(start = 16.dp),
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFFFFFFFF),
                checkedTrackColor = Color(0xFF4285F4),
                uncheckedThumbColor = Color(0xFFF1F1F1),
                uncheckedTrackColor = Color(0xFF9AA0A6)
            )
        )
    }
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
    val colors = themeColors()
    Column(
        modifier
            .shadow(4.dp, RoundedCornerShape(24.dp), ambientColor = Color(0x1A000000))
            .clip(RoundedCornerShape(24.dp))
            .background(colors.surface.copy(alpha = 0.85f))
            .padding(16.dp),
        content = content
    )
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
