package com.rawsmusic.core.ui.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
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
        Color.White.copy(alpha = 0.44f)
    } else {
        Color.White.copy(alpha = 0.12f)
    }

    val glassModifier = if (backdrop != null) {
        Modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                vibrancy()
                blur(20f)
                lens(6f, 12f)
            },
            highlight = {
                Highlight.Default.copy(
                    alpha = if (isLight) 0.26f else 0.16f
                )
            },
            shadow = {
                Shadow.Default.copy(
                    color = Color.Black.copy(
                        alpha = if (isLight) 0.12f else 0.30f
                    )
                )
            },
            onDrawSurface = {
                drawRect(containerColor)
            }
        )
    } else {
        Modifier.background(containerColor, shape)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(shape)
            .then(glassModifier)
    )
}
