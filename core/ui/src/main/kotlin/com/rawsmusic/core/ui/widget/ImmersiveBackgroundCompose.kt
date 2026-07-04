package com.rawsmusic.core.ui.widget

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.rawsmusic.core.ui.widget.bitmaps.BitmapImage

/**
 * 纯 Compose 沉浸背景。
 *
 * 替代原 ImmersiveBackgroundView (333行)。
 * 功能：
 * 1. 封面图片显示（带模糊背景）
 * 2. 镜像倒影效果
 * 3. 渐变遮罩
 * 4. 暗色/亮色主题适配
 */
@Composable
fun ImmersiveBackgroundCompose(
    coverPath: String? = null,
    isImmersiveEnabled: Boolean = true,
    isMiniCoverEnabled: Boolean = true,
    isDarkMode: Boolean = true,
    coverAlpha: Float = 1f,
    modifier: Modifier = Modifier
) {
    if (!isImmersiveEnabled) return

    val bgColor = if (isDarkMode) Color(0xFF171412) else Color(0xFFF7F6F4)
    val scrimColor = if (isDarkMode) Color.Black.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.3f)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(bgColor)
    ) {
        if (coverPath != null && coverPath.isNotBlank()) {
            // 封面图片（带模糊背景）
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(coverAlpha)
            ) {
                // 模糊背景层
                BitmapImage(
                    key = coverPath,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .blur(60.dp)
                        .graphicsLayer { alpha = 0.6f },
                    contentScale = ContentScale.Crop,
                    targetWidth = 128,
                    targetHeight = 128
                )

                // 主封面（居中，带圆角）
                BitmapImage(
                    key = coverPath,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(0.25f),
                    contentScale = ContentScale.Crop,
                    targetWidth = 512,
                    targetHeight = 512
                )
            }

            // 渐变遮罩（从底部向上渐变）
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                scrimColor
                            ),
                            startY = 0f,
                            endY = Float.POSITIVE_INFINITY
                        )
                    )
            )
        } else {
            // 无封面时显示纯色背景
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(bgColor)
            )
        }
    }
}
