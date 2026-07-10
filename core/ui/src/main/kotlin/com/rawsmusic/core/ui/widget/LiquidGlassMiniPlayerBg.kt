package com.rawsmusic.core.ui.widget

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.liquidGlass
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow

/**
 * 播放栏液态玻璃背景。
 *
 * 当 [backdrop] 非空时使用 Backdrop 效果（模糊+折射+高光+阴影），
 * 否则退化为半透明纯色背景。
 */
@Composable
fun LiquidGlassMiniPlayerBg(
    backdrop: Backdrop? = null,
    isLight: Boolean = false
) {
    val shape = RoundedCornerShape(24.dp)
    val containerColor = if (isLight) {
        Color.White.copy(alpha = 0.24f)
    } else {
        Color(0xFF171512).copy(alpha = 0.24f)
    }
    val fallbackSurface = Modifier
        .background(
            Brush.linearGradient(
                colors = if (isLight) {
                    listOf(
                        Color.White.copy(alpha = 0.62f),
                        Color.White.copy(alpha = 0.28f),
                        Color(0xFFC8B6A3).copy(alpha = 0.18f)
                    )
                } else {
                    listOf(
                        Color.White.copy(alpha = 0.20f),
                        Color(0xFF2A2420).copy(alpha = 0.36f),
                        Color.Black.copy(alpha = 0.24f)
                    )
                },
                start = Offset.Zero,
                end = Offset.Infinite
            ),
            shape
        )
        .drawBehind {
            drawRoundRect(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = if (isLight) 0.32f else 0.18f),
                        Color.Transparent
                    ),
                    center = Offset(size.width * 0.18f, size.height * 0.12f),
                    radius = size.width * 0.82f
                ),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(24.dp.toPx())
            )
        }

    val glassModifier = if (backdrop != null) {
        Modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                vibrancy()
                blur(if (isLight) 40f else 50f)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    liquidGlass(
                        cornerRadius = 24.dp.toPx(),
                        refraction = 0.90f,
                        curve = 0.80f,
                        dispersion = 0.35f,
                        saturation = if (isLight) 1.25f else 1.40f,
                        contrast = if (isLight) 1.06f else 1.14f,
                        edge = if (isLight) 0.15f else 0.22f,
                        tintR = if (isLight) 1f else 0.86f,
                        tintG = if (isLight) 1f else 0.80f,
                        tintB = if (isLight) 1f else 0.70f,
                        tintA = if (isLight) 0.06f else 0.08f
                    )
                } else {
                    lens(
                        refractionHeight = 18f,
                        refractionAmount = 36f,
                        depthEffect = true,
                        chromaticAberration = true
                    )
                }
            },
            highlight = {
                Highlight.Default.copy(
                    alpha = if (isLight) 0.50f else 0.38f
                )
            },
            shadow = {
                Shadow.Default.copy(
                    color = Color.Black.copy(
                        alpha = if (isLight) 0.20f else 0.52f
                    )
                )
            },
            innerShadow = {
                InnerShadow.Default.copy(
                    color = Color.White.copy(alpha = if (isLight) 0.32f else 0.18f)
                )
            },
            onDrawSurface = {
                drawRect(containerColor)
                drawRect(
                    Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = if (isLight) 0.40f else 0.24f),
                            Color.Transparent,
                            Color.Black.copy(alpha = if (isLight) 0.04f else 0.14f)
                        ),
                        start = Offset(0f, 0f),
                        end = Offset(size.width, size.height)
                    )
                )
            },
            onDrawFront = {
                drawRoundRect(
                    color = Color.White.copy(alpha = if (isLight) 0.30f else 0.18f),
                    size = size,
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(24.dp.toPx()),
                    style = Stroke(width = 1.dp.toPx())
                )
            }
        )
    } else {
        Modifier
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(shape)
            .then(fallbackSurface)
            .then(glassModifier)
            .drawWithContent {
                drawContent()
                drawRoundRect(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = if (isLight) 0.56f else 0.26f),
                            Color.White.copy(alpha = 0.06f),
                            Color.Black.copy(alpha = if (isLight) 0.04f else 0.18f)
                        ),
                        start = Offset(0f, 0f),
                        end = Offset(size.width, size.height)
                    ),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(24.dp.toPx()),
                    style = Stroke(width = 1.2.dp.toPx())
                )
            }
    )
}
