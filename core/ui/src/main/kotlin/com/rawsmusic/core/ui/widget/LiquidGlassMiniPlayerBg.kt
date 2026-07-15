package com.rawsmusic.core.ui.widget

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import com.rawsmusic.module.data.prefs.PersonalizationPreferences

/**
 * 底部播放栏的玻璃背景。
 *
 * Android 12 及以上直接采样页面内容；Android 13 及以上再增加轻量折射。
 * 低版本才绘制半透明降级层，避免支持模糊的设备被颜色遮罩盖住。
 */
@Composable
fun LiquidGlassMiniPlayerBg(
    backdrop: Backdrop? = null,
    isLight: Boolean = false
) {
    val shape = RoundedCornerShape(32.dp)
    val glassSupported = backdrop != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val performanceMode by PersonalizationPreferences.performanceMode.collectAsState()
    val containerColor = when {
        performanceMode && isLight -> Color.White.copy(alpha = 0.56f)
        performanceMode -> Color(0xFF111114).copy(alpha = 0.58f)
        isLight -> Color.White.copy(alpha = 0.34f)
        else -> Color(0xFF111114).copy(alpha = 0.38f)
    }

    val fallbackSurface = Modifier
        .background(containerColor, shape)

    val glassModifier = backdrop
        ?.takeIf { Build.VERSION.SDK_INT >= Build.VERSION_CODES.S }
        ?.let { resolvedBackdrop ->
        Modifier.drawBackdrop(
            backdrop = resolvedBackdrop,
            shape = { shape },
            effects = {
                if (performanceMode) {
                    blur(34.dp.toPx())
                } else {
                    blur(6.dp.toPx())
                    lens(
                        refractionHeight = 24.dp.toPx(),
                        refractionAmount = 24.dp.toPx()
                    )
                }
            },
            highlight = {
                Highlight.Default.copy(
                    alpha = if (performanceMode) {
                        if (isLight) 0.22f else 0.14f
                    } else {
                        if (isLight) 0.18f else 0.10f
                    }
                )
            },
            shadow = {
                Shadow.Default.copy(
                    color = Color.Black.copy(
                        alpha = if (performanceMode) {
                            if (isLight) 0.12f else 0.30f
                        } else {
                            if (isLight) 0.12f else 0.26f
                        }
                    )
                )
            },
            onDrawSurface = {
                drawRect(containerColor)
            }
        )
    } ?: Modifier

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(shape)
            .then(if (glassSupported) Modifier else fallbackSurface)
            .then(glassModifier)
            .drawWithContent {
                drawContent()
                drawRoundRect(
                    color = Color.White.copy(
                        alpha = if (performanceMode) {
                            if (isLight) 0.24f else 0.13f
                        } else {
                            if (isLight) 0.20f else 0.10f
                        }
                    ),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(32.dp.toPx()),
                    style = Stroke(width = 1.dp.toPx())
                )
            }
    )
}
