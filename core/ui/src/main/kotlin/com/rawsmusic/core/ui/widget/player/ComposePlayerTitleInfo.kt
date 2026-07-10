package com.rawsmusic.core.ui.widget.player

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.ceil

@Composable
fun ComposePlayerTitleInfo(
    title: String,
    artist: String,
    album: String,
    modifier: Modifier = Modifier,
    titleColor: Color = Color.White,
    artistColor: Color = Color(0xCCFFFFFF),
    albumColor: Color = Color(0x99FFFFFF),
    endPaddingDp: Float = 0f
) {
    Column(modifier = modifier.fillMaxWidth()) {
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
        MarqueeCanvasText(
            text = artist,
            color = artistColor,
            fontSizeSp = 14f,
            endPaddingDp = endPaddingDp,
            modifier = Modifier
                .fillMaxWidth()
                .height(21.dp)
                .padding(top = 2.dp)
        )
        if (album.isNotBlank()) {
            MarqueeCanvasText(
                text = album,
                color = albumColor,
                fontSizeSp = 12f,
                endPaddingDp = endPaddingDp,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(18.dp)
                    .padding(top = 1.dp)
            )
        }
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
    val paint = remember(text, color, fontSizeSp, fontWeight, density.density, density.fontScale) {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color.toArgb()
            textSize = with(density) { fontSizeSp.sp.toPx() }
            typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.SANS_SERIF,
                if (fontWeight >= FontWeight.Bold) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL
            )
        }
    }
    val textWidth = remember(text, paint.textSize, fontWeight) {
        paint.measureText(text)
    }
    val spacing = paint.textSize * 1.5f
    val speed = paint.textSize * 0.8f
    val cycleWidth = textWidth + spacing
    val duration = ceil(cycleWidth / speed * 1000f).toInt().coerceAtLeast(1000)
    val transition = rememberInfiniteTransition(label = "title-marquee")
    val offset by transition.animateFloat(
        initialValue = 0f,
        targetValue = cycleWidth,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = duration, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "title-marquee-offset"
    )

    Canvas(modifier = modifier) {
        val availableWidth = size.width - endPaddingDp * density.density
        if (availableWidth <= 0f) return@Canvas
        val fm = paint.fontMetrics
        val baseline = (size.height - (fm.descent - fm.ascent)) / 2f - fm.ascent
        clipRect(left = 0f, top = 0f, right = availableWidth, bottom = size.height) {
            drawIntoNativeText(text, paint, Offset(if (textWidth > availableWidth) -offset else 0f, baseline))
            if (textWidth > availableWidth) {
                drawIntoNativeText(text, paint, Offset(cycleWidth - offset, baseline))
            }
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
