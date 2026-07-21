package com.rawsmusic.core.ui.widget.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.core.ui.theme.RawThemeRuntimeState
import com.rawsmusic.module.data.prefs.FontManager
import kotlinx.coroutines.delay
import kotlin.math.ceil

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ComposePlayerTitleInfo(
    title: String,
    artist: String,
    album: String,
    modifier: Modifier = Modifier,
    titleColor: Color = Color.White,
    artistColor: Color = Color(0xCCFFFFFF),
    albumColor: Color = Color(0x99FFFFFF),
    endPaddingDp: Float = 0f,
    onLongClick: (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onLongClick != null) {
                    Modifier.combinedClickable(
                        onClick = {},
                        onLongClick = onLongClick
                    )
                } else {
                    Modifier
                }
            )
    ) {
        MarqueeCanvasText(
            text = title,
            color = titleColor,
            fontSizeSp = 20f,
            fontWeight = FontWeight.Bold,
            endPaddingDp = endPaddingDp,
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
        )
        val secondary = listOf(artist, album)
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .joinToString(" / ")
        MarqueeCanvasText(
            text = secondary,
            color = artistColor,
            fontSizeSp = 14f,
            endPaddingDp = endPaddingDp,
            modifier = Modifier
                .fillMaxWidth()
                .height(21.dp)
                .padding(top = 2.dp)
        )
    }
}

@Composable
private fun MarqueeCanvasText(
    text: String,
    color: Color,
    fontSizeSp: Float,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight = FontWeight.Normal,
    endPaddingDp: Float = 0f
) {
    val density = LocalDensity.current
    val fontRuntimeVersion = RawThemeRuntimeState.version
    val paint = remember(text, color, fontSizeSp, fontWeight, density.density, density.fontScale, fontRuntimeVersion) {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color.toArgb()
            textSize = with(density) { fontSizeSp.sp.toPx() }
            val configuredTypeface = FontManager.typeface
            typeface = if (configuredTypeface != null) {
                android.graphics.Typeface.create(
                    configuredTypeface,
                    if (fontWeight >= FontWeight.Bold) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL
                )
            } else {
                android.graphics.Typeface.create(
                    android.graphics.Typeface.SANS_SERIF,
                    if (fontWeight >= FontWeight.Bold) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL
                )
            }
        }
    }
    val textWidth = remember(text, paint.textSize, fontWeight) {
        paint.measureText(text)
    }
    val speed = paint.textSize * 0.8f
    var measuredWidth by remember { mutableFloatStateOf(0f) }
    val availableWidth = (measuredWidth - endPaddingDp * density.density).coerceAtLeast(0f)
    val overflowWidth = (textWidth - availableWidth).coerceAtLeast(0f)
    val offset = remember(text, fontRuntimeVersion) { Animatable(0f) }
    LaunchedEffect(text, overflowWidth, speed) {
        offset.snapTo(0f)
        if (overflowWidth <= 0f) return@LaunchedEffect
        delay(1_200L)
        val duration = ceil(overflowWidth / speed * 1000f).toInt().coerceAtLeast(1_000)
        offset.animateTo(
            targetValue = overflowWidth,
            animationSpec = tween(durationMillis = duration, easing = LinearEasing)
        )
    }

    Canvas(modifier = modifier.onSizeChanged { measuredWidth = it.width.toFloat() }) {
        if (availableWidth <= 0f) return@Canvas
        val fm = paint.fontMetrics
        val baseline = (size.height - (fm.descent - fm.ascent)) / 2f - fm.ascent
        clipRect(left = 0f, top = 0f, right = availableWidth, bottom = size.height) {
            drawIntoNativeText(text, paint, Offset(-offset.value, baseline))
        }
    }
}

private fun androidx.compose.ui.graphics.Color.toArgb(): Int {
    return android.graphics.Color.argb(
        (alpha * 255).toInt().coerceIn(0, 255),
        (red * 255).toInt().coerceIn(0, 255),
        (green * 255).toInt().coerceIn(0, 255),
        (blue * 255).toInt().coerceIn(0, 255)
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawIntoNativeText(
    text: String,
    paint: android.graphics.Paint,
    offset: Offset
) {
    drawContext.canvas.nativeCanvas.drawText(text, offset.x, offset.y, paint)
}
