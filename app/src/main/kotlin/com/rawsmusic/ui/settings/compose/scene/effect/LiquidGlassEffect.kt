package com.rawsmusic.ui.settings.compose.scene.effect

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 液态玻璃效果
 * 对应原版的液态玻璃效果
 *
 * 提供毛玻璃、渐变、边框等效果
 */
object LiquidGlassEffect {
    /**
     * 毛玻璃效果
     *
     * @param cornerRadius 圆角半径
     * @param alpha 透明度
     */
    @Composable
    fun glassEffect(
        cornerRadius: Dp = 16.dp,
        alpha: Float = 0.15f
    ): Modifier {
        return Modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = alpha),
                        Color.White.copy(alpha = alpha * 0.5f)
                    )
                )
            )
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.2f),
                shape = RoundedCornerShape(cornerRadius)
            )
    }

    /**
     * 液态玻璃效果
     *
     * @param cornerRadius 圆角半径
     * @param intensity 强度
     */
    @Composable
    fun liquidGlassEffect(
        cornerRadius: Dp = 20.dp,
        intensity: Float = 1f
    ): Modifier {
        return Modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.15f * intensity),
                        Color.White.copy(alpha = 0.05f * intensity)
                    )
                )
            )
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.2f),
                shape = RoundedCornerShape(cornerRadius)
            )
    }

    /**
     * 渐变背景
     *
     * @param colors 渐变颜色
     * @param cornerRadius 圆角半径
     */
    @Composable
    fun GradientBackground(
        colors: List<Color>,
        cornerRadius: Dp = 0.dp,
        modifier: Modifier = Modifier
    ) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(cornerRadius))
                .background(
                    Brush.verticalGradient(colors = colors)
                )
        )
    }

    /**
     * 暗色背景
     *
     * @param cornerRadius 圆角半径
     */
    @Composable
    fun DarkBackground(
        cornerRadius: Dp = 0.dp,
        modifier: Modifier = Modifier
    ) {
        GradientBackground(
            colors = listOf(
                Color(0xFF1A1A2E),
                Color(0xFF16213E)
            ),
            cornerRadius = cornerRadius,
            modifier = modifier
        )
    }

    /**
     * 透明背景
     *
     * @param cornerRadius 圆角半径
     * @param alpha 透明度
     */
    @Composable
    fun TransparentBackground(
        cornerRadius: Dp = 0.dp,
        alpha: Float = 0.1f,
        modifier: Modifier = Modifier
    ) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(cornerRadius))
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = alpha),
                            Color.White.copy(alpha = alpha * 0.5f)
                        )
                    )
                )
                .border(
                    width = 1.dp,
                    color = Color.White.copy(alpha = alpha * 2),
                    shape = RoundedCornerShape(cornerRadius)
                )
        )
    }
}

/**
 * 液态玻璃 Modifier
 *
 * @param cornerRadius 圆角半径
 * @param alpha 透明度
 */
fun Modifier.liquidGlass(
    cornerRadius: Dp = 16.dp,
    alpha: Float = 0.1f
): Modifier {
    return this
        .clip(RoundedCornerShape(cornerRadius))
        .background(
            Brush.verticalGradient(
                colors = listOf(
                    Color.White.copy(alpha = alpha),
                    Color.White.copy(alpha = alpha * 0.5f)
                )
            )
        )
        .border(
            width = 1.dp,
            color = Color.White.copy(alpha = alpha * 2),
            shape = RoundedCornerShape(cornerRadius)
        )
}

/**
 * 暗色液态玻璃 Modifier
 *
 * @param cornerRadius 圆角半径
 */
fun Modifier.darkLiquidGlass(
    cornerRadius: Dp = 16.dp
): Modifier {
    return this
        .clip(RoundedCornerShape(cornerRadius))
        .background(
            Brush.verticalGradient(
                colors = listOf(
                    Color(0xFF1A1A2E),
                    Color(0xFF16213E)
                )
            )
        )
        .border(
            width = 1.dp,
            color = Color.White.copy(alpha = 0.1f),
            shape = RoundedCornerShape(cornerRadius)
        )
}
