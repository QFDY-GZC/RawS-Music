package com.rawsmusic.ui.settings

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.rawsmusic.R
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
import top.yukonga.miuix.kmp.basic.TextButton
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
                            contentDescription = stringResource(R.string.settings_back),
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
    onNavigateToLyricFont: () -> Unit,
    onNavigateToAppearance: () -> Unit,
    onNavigateToPersonalization: () -> Unit,
    onNavigateToAudioSettings: () -> Unit,
    onNavigateToAudioEffects: () -> Unit,
    onNavigateToTransitionSettings: () -> Unit,
    onNavigateToPlayerInterface: () -> Unit,
    onNavigateToUsbDac: () -> Unit,
    onNavigateToGlobalFont: () -> Unit,
    onNavigateToAlbumArt: () -> Unit,
    onWebDavBackup: () -> Unit,
    onNavigateToLogViewer: () -> Unit,
    onNavigateToAbout: () -> Unit = {},
    onNavigateToScanSettings: () -> Unit = {}
) {
    val isDark = MiuixTheme.colorScheme.background.luminance() < 0.5f
    val pageBackground = if (isDark) Color(0xFF101014) else Color(0xFFF4F4F7)
    var searchQuery by remember { mutableStateOf("") }

    // Root sections and search use the same model. This prevents entries from
    // being searchable but missing from the normal list (or the reverse).
    val sections = listOf(
        SettingsRootSection(
            title = stringResource(R.string.settings_section_playback_audio),
            items = listOf(
                SettingsRootItem(
                    title = stringResource(R.string.settings_audio_quality_title),
                    summary = stringResource(R.string.settings_audio_quality_summary),
                    keywords = stringResource(R.string.settings_audio_quality_keywords),
                    onClick = onNavigateToAudioSettings
                ),
                SettingsRootItem(
                    title = stringResource(R.string.settings_transition_title),
                    summary = stringResource(R.string.settings_transition_summary),
                    keywords = stringResource(R.string.settings_transition_keywords),
                    onClick = onNavigateToTransitionSettings
                ),
                SettingsRootItem(
                    title = stringResource(R.string.settings_audio_effects_title),
                    summary = stringResource(R.string.settings_audio_effects_summary),
                    keywords = stringResource(R.string.settings_audio_effects_keywords),
                    onClick = onNavigateToAudioEffects
                ),
                SettingsRootItem(
                    title = stringResource(R.string.settings_usb_dac_title),
                    summary = stringResource(R.string.settings_usb_dac_summary),
                    keywords = stringResource(R.string.settings_usb_dac_keywords),
                    onClick = onNavigateToUsbDac
                )
            )
        ),
        SettingsRootSection(
            title = stringResource(R.string.settings_section_interface_display),
            items = listOf(
                SettingsRootItem(
                    title = stringResource(R.string.settings_appearance_title),
                    summary = stringResource(R.string.settings_appearance_summary),
                    keywords = stringResource(R.string.settings_appearance_keywords),
                    onClick = onNavigateToAppearance
                ),
                SettingsRootItem(
                    title = stringResource(R.string.settings_player_interface_title),
                    summary = stringResource(R.string.settings_player_interface_summary),
                    keywords = stringResource(R.string.settings_player_interface_keywords),
                    onClick = onNavigateToPlayerInterface
                ),
                SettingsRootItem(
                    title = stringResource(R.string.settings_album_art_title),
                    summary = stringResource(R.string.settings_album_art_summary),
                    keywords = stringResource(R.string.settings_album_art_keywords),
                    onClick = onNavigateToAlbumArt
                ),
                SettingsRootItem(
                    title = stringResource(R.string.settings_global_font_title),
                    summary = stringResource(R.string.settings_global_font_summary),
                    keywords = stringResource(R.string.settings_global_font_keywords),
                    onClick = onNavigateToGlobalFont
                ),
                SettingsRootItem(
                    title = stringResource(R.string.settings_personalization_title),
                    summary = stringResource(R.string.settings_personalization_summary),
                    keywords = stringResource(R.string.settings_personalization_keywords),
                    onClick = onNavigateToPersonalization
                )
            )
        ),
        SettingsRootSection(
            title = stringResource(R.string.settings_section_lyrics_extensions),
            items = listOf(
                SettingsRootItem(
                    title = stringResource(R.string.settings_lyric_management_title),
                    summary = stringResource(R.string.settings_lyric_management_summary),
                    keywords = stringResource(R.string.settings_lyric_management_keywords),
                    onClick = onNavigateToLyricManagement
                ),
                SettingsRootItem(
                    title = stringResource(R.string.settings_lyric_font_title),
                    summary = stringResource(R.string.settings_lyric_font_summary),
                    keywords = stringResource(R.string.settings_lyric_font_keywords),
                    onClick = onNavigateToLyricFont
                ),
                SettingsRootItem(
                    title = stringResource(R.string.settings_status_bar_lyric_title),
                    summary = stringResource(R.string.settings_status_bar_lyric_summary),
                    keywords = stringResource(R.string.settings_status_bar_lyric_keywords),
                    onClick = onNavigateToStatusBarLyric
                )
            )
        ),
        SettingsRootSection(
            title = stringResource(R.string.settings_section_library_data),
            items = listOf(
                SettingsRootItem(
                    title = stringResource(R.string.settings_scan_settings_title),
                    summary = stringResource(R.string.settings_scan_settings_summary),
                    keywords = stringResource(R.string.settings_scan_settings_keywords),
                    onClick = onNavigateToScanSettings
                ),
                SettingsRootItem(
                    title = stringResource(R.string.settings_webdav_backup_title),
                    summary = stringResource(R.string.settings_webdav_backup_summary),
                    keywords = stringResource(R.string.settings_webdav_backup_keywords),
                    onClick = onWebDavBackup
                )
            )
        ),
        SettingsRootSection(
            title = stringResource(R.string.settings_section_help_diagnostics),
            items = listOf(
                SettingsRootItem(
                    title = stringResource(R.string.settings_log_viewer_title),
                    summary = stringResource(R.string.settings_log_viewer_summary),
                    keywords = stringResource(R.string.settings_log_viewer_keywords),
                    onClick = onNavigateToLogViewer
                ),
                SettingsRootItem(
                    title = stringResource(R.string.settings_about_raws_music_title),
                    summary = stringResource(R.string.settings_about_raws_music_summary),
                    keywords = stringResource(R.string.settings_about_keywords),
                    onClick = onNavigateToAbout
                )
            )
        )
    )

    val filteredItems = if (searchQuery.isBlank()) {
        emptyList()
    } else {
        sections.flatMap { section ->
            section.items.map { item -> SearchableSetting(section.title, item) }
        }.filter { result ->
            result.sectionTitle.contains(searchQuery, ignoreCase = true) ||
                result.item.title.contains(searchQuery, ignoreCase = true) ||
                result.item.summary.contains(searchQuery, ignoreCase = true) ||
                result.item.keywords.contains(searchQuery, ignoreCase = true)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(pageBackground)
    ) {
        SmallTopAppBar(
            title = stringResource(R.string.settings_main_title),
            color = pageBackground,
            titleColor = MiuixTheme.colorScheme.onBackground,
            navigationIcon = {}
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
        ) {
            TextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                label = stringResource(R.string.settings_search_hint)
            )
        }

        if (searchQuery.isNotBlank() && filteredItems.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp)
            ) {
                SmallTitle(text = stringResource(R.string.settings_search_results))
                SettingsCardGroup {
                    Column {
                        filteredItems.forEach { result ->
                            ArrowPreference(
                                title = result.item.title,
                                summary = "${result.sectionTitle} · ${result.item.summary}",
                                onClick = {
                                    searchQuery = ""
                                    result.item.onClick()
                                }
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        } else if (searchQuery.isNotBlank()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.settings_search_no_results),
                    color = MiuixTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                    fontSize = 14.sp
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp)
            ) {
                Spacer(modifier = Modifier.height(8.dp))
                sections.forEach { section ->
                    SmallTitle(text = section.title)
                    SettingsCardGroup {
                        Column {
                            section.items.forEach { item ->
                                ArrowPreference(
                                    title = item.title,
                                    summary = item.summary,
                                    onClick = item.onClick
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(180.dp))
            }
        }
    }
}

private data class SettingsRootSection(
    val title: String,
    val items: List<SettingsRootItem>
)

private data class SettingsRootItem(
    val title: String,
    val summary: String,
    val keywords: String,
    val onClick: () -> Unit
)

private data class SearchableSetting(
    val sectionTitle: String,
    val item: SettingsRootItem
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
internal fun ExpandableEffectContent(
    enabled: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(enabled) }
    var previousEnabled by rememberSaveable { mutableStateOf(enabled) }

    LaunchedEffect(enabled) {
        if (enabled && !previousEnabled) {
            expanded = true
        } else if (!enabled) {
            expanded = false
        }
        previousEnabled = enabled
    }

    AnimatedVisibility(
        visible = enabled,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        Column(modifier = modifier.fillMaxWidth()) {
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    content = content
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    text = stringResource(
                        if (expanded) R.string.settings_effect_card_collapse
                        else R.string.settings_effect_card_expand
                    ),
                    onClick = { expanded = !expanded }
                )
            }
        }
    }
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
