package com.rawsmusic.core.ui.scene.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.rawsmusic.core.ui.scene.LocalSceneBackgroundFrozen
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 页面主题颜色。
 * 保留作为兼容层，逐步迁移到 MiuixTheme.colorScheme。
 */
object PageColors {
    var PAGE_BG: Color = Color.Transparent
    var CARD_BG: Color = Color(0xFF353130)
    var TEXT_PRIMARY: Color = Color.White
    var TEXT_SECONDARY: Color = Color(0xCCFFFFFF)
    var TEXT_META: Color = Color(0xFF928A86)
    var ACCENT: Color = Color(0xFFD4B896)
    var DIVIDER: Color = Color(0x1AFFFFFF)

    fun updateForTheme(isDark: Boolean) {
        updateFromPalette(
            pageBg = if (isDark) Color.Transparent else Color.White,
            cardBg = if (isDark) Color(0xFF353130) else Color(0xFFF5DED0),
            textPrimary = if (isDark) Color.White else Color(0xFF1B1B1B),
            textSecondary = if (isDark) Color(0xCCFFFFFF) else Color(0xCC1B1B1B),
            textMeta = if (isDark) Color(0xFF928A86) else Color(0xFF857367),
            accent = if (isDark) Color(0xFFD4B896) else Color(0xFFC4956A),
            divider = if (isDark) Color(0x1AFFFFFF) else Color(0x1A000000)
        )
    }

    fun updateFromPalette(
        pageBg: Color,
        cardBg: Color,
        textPrimary: Color,
        textSecondary: Color,
        textMeta: Color,
        accent: Color,
        divider: Color
    ) {
        PAGE_BG = pageBg
        CARD_BG = cardBg
        TEXT_PRIMARY = textPrimary
        TEXT_SECONDARY = textSecondary
        TEXT_META = textMeta
        ACCENT = accent
        DIVIDER = divider
    }
}

/**
 * 通用页面模板：Miuix SmallTopAppBar + 内容。
 */
@Composable
fun SimplePageScaffold(
    title: String,
    onBack: () -> Unit,
    actions: @Composable () -> Unit = {},
    content: @Composable () -> Unit
) {
    val backgroundColor = MiuixTheme.colorScheme.background

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
    ) {
        SmallTopAppBar(
            title = title,
            color = backgroundColor,
            titleColor = MiuixTheme.colorScheme.onBackground,
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = MiuixIcons.Regular.Back,
                        contentDescription = "返回",
                        tint = MiuixTheme.colorScheme.onSurface
                    )
                }
            }
        )

        content()
    }
}
