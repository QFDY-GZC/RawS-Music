package com.rawsmusic.core.ui.widget.player

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.core.ui.widget.bitmaps.BitmapImage
import kotlin.math.abs

/**
 * 全屏封面页面。
 * 播放器全屏封面场景。
 */
@Composable
fun FullCoverPage(
    coverPath: String?,
    title: String = "",
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var edgeStartX by remember { mutableFloatStateOf(0f) }
    var widthPx by remember { mutableFloatStateOf(0f) }
    var edgeTracking by remember { mutableStateOf(false) }
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(0.5f, 5f)
        if (scale > 1f) {
            offsetX += panChange.x
            offsetY += panChange.y
        } else {
            offsetX = 0f
            offsetY = 0f
        }
    }
    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val first = event.changes.firstOrNull() ?: continue
                        widthPx = size.width.toFloat()
                        when {
                            first.pressed && first.previousPressed.not() -> {
                                edgeStartX = first.position.x
                                val edgeZone = 36.dp.toPx()
                                edgeTracking = edgeStartX < edgeZone || edgeStartX > widthPx - edgeZone
                            }
                            first.pressed && edgeTracking -> {
                                val dx = first.position.x - edgeStartX
                                if (abs(dx) > 48.dp.toPx()) {
                                    onBack()
                                    edgeTracking = false
                                }
                            }
                            !first.pressed -> edgeTracking = false
                        }
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { onBack() },
                    onTap = {
                        if (scale < 1f) {
                            scale = 1f
                            offsetX = 0f
                            offsetY = 0f
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        // 返回按钮
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 8.dp, top = 44.dp)
                .size(44.dp)
        ) {
            Text("←", fontSize = 20.sp, color = Color.White)
        }

        // 大封面
        if (coverPath != null && coverPath.isNotBlank()) {
            BitmapImage(
                key = coverPath,
                contentDescription = title,
                modifier = Modifier
                    .fillMaxSize(0.9f)
                    .aspectRatio(1f)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offsetX
                        translationY = offsetY
                    }
                    .transformable(transformState)
                    .clip(RoundedCornerShape(16.dp)),
                contentScale = ContentScale.Crop,
                targetWidth = 2048,
                targetHeight = 2048
            )
        }
    }
}
