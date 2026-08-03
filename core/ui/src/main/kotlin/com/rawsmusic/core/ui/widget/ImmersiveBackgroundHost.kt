package com.rawsmusic.core.ui.widget

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import com.rawsmusic.core.ui.widget.bitmaps.ArtworkSurface
import com.rawsmusic.core.ui.widget.bitmaps.BitmapProvider
import com.rawsmusic.core.ui.widget.bitmaps.BitmapRequest
import kotlin.math.abs

interface ImmersiveBackgroundHost {
    var isImmersiveEnabled: Boolean
    var isMiniCoverEnabled: Boolean
    var isDarkMode: Boolean
    var coverAlpha: Float
    var onImmersiveDrawingChanged: ((Boolean) -> Unit)?
    var bottomPaddingHeight: Float

    fun setCover(path: String?)
    fun clear()
}

class ImmersiveBackgroundState : ImmersiveBackgroundHost {
    internal var currentBitmap by mutableStateOf<Bitmap?>(null)
        private set
    internal var dominantColor by mutableStateOf(Color(0xFF333333))
        private set
    private var currentPath: String? = null
    private var coverGeneration = 0

    override var isImmersiveEnabled by mutableStateOf(true)
    override var isMiniCoverEnabled by mutableStateOf(true)
    override var isDarkMode by mutableStateOf(true)
    override var coverAlpha by mutableFloatStateOf(1f)
    override var onImmersiveDrawingChanged: ((Boolean) -> Unit)? = null
    override var bottomPaddingHeight by mutableFloatStateOf(0f)

    override fun setCover(path: String?) {
        val needReload = path != currentPath ||
            currentBitmap == null ||
            currentBitmap?.isRecycled == true
        currentPath = path

        if (!needReload && !path.isNullOrBlank()) return
        if (path.isNullOrBlank()) {
            currentBitmap = null
            return
        }

        val gen = ++coverGeneration
        BitmapProvider.load(
            key = path,
            targetWidth = 1080,
            targetHeight = 1080,
            priority = BitmapRequest.Priority.LOADING_WIDGET,
            surface = ArtworkSurface.Playback,
            callback = { bitmap ->
                if (gen != coverGeneration) return@load
                if (bitmap != null && !bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0) {
                    currentBitmap = bitmap
                } else {
                    currentBitmap = null
                }
            }
        )
    }

    override fun clear() {
        currentPath = null
        currentBitmap = null
    }
}

@Composable
fun ImmersiveBackground(
    state: ImmersiveBackgroundState,
    modifier: Modifier = Modifier
) {
    val bitmap = state.currentBitmap
    val shouldDraw = bitmap != null &&
        (state.isImmersiveEnabled || state.isMiniCoverEnabled)
    val coverAlpha = state.coverAlpha
    val isDarkMode = state.isDarkMode
    val dominantColor = state.dominantColor

    Canvas(modifier.fillMaxSize()) {
        if (!shouldDraw || bitmap.isRecycled || size.width <= 0f || size.height <= 0f) {
            return@Canvas
        }

        state.onImmersiveDrawingChanged?.invoke(true)

        val w = size.width
        val h = size.height
        val splitY = h * 0.55f
        val blurStartY = splitY - h * 0.1f
        val bmpRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
        val isSquare = abs(bmpRatio - 1f) < 0.15f
        val topSrc = calculateImmersiveCropRect(bitmap, w, splitY)
        val fullSrc = android.graphics.Rect(0, 0, bitmap.width, bitmap.height)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)

        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            if (!isSquare) {
                native.save()
                native.clipRect(0f, 0f, w, splitY)
                paint.alpha = (coverAlpha * 255f).toInt().coerceIn(0, 255)
                native.drawBitmap(bitmap, fullSrc, android.graphics.RectF(0f, 0f, w, splitY), paint)
                native.restore()
            }

            native.save()
            native.clipRect(0f, 0f, w, splitY)
            paint.alpha = (coverAlpha * 255f).toInt().coerceIn(0, 255)
            native.drawBitmap(bitmap, topSrc, android.graphics.RectF(0f, 0f, w, splitY), paint)
            native.restore()

            native.save()
            native.clipRect(0f, splitY, w, h)
            native.scale(1f, -1f, w / 2f, splitY)
            paint.alpha = 255
            native.drawBitmap(bitmap, topSrc, android.graphics.RectF(0f, 0f, w, splitY), paint)
            native.restore()

            paint.alpha = 130
            native.drawBitmap(bitmap, calculateImmersiveCropRect(bitmap, w, h), android.graphics.RectF(0f, 0f, w, h), paint)
            paint.alpha = 255
        }

        drawRect(
            brush = Brush.verticalGradient(
                0f to Color.Transparent,
                (blurStartY / h).coerceIn(0f, 1f) to Color.Transparent,
                (splitY / h).coerceIn(0f, 1f) to Color.Black.copy(alpha = 0.3f),
                1f to Color.Black.copy(alpha = if (isDarkMode) 0.72f else 0.38f)
            ),
            topLeft = Offset.Zero,
            size = Size(w, h)
        )
        drawRect(
            brush = Brush.verticalGradient(
                0f to dominantColor.copy(alpha = 0.55f),
                1f to Color.Transparent
            ),
            topLeft = Offset.Zero,
            size = Size(w, 60f * density)
        )

        state.onImmersiveDrawingChanged?.invoke(false)
    }
}

private fun calculateImmersiveCropRect(bitmap: Bitmap, destW: Float, destH: Float): android.graphics.Rect {
    val bmpW = bitmap.width.toFloat()
    val bmpH = bitmap.height.toFloat()
    if (bmpW <= 0f || bmpH <= 0f || destW <= 0f || destH <= 0f) {
        return android.graphics.Rect(0, 0, bitmap.width, bitmap.height)
    }
    val bmpRatio = bmpW / bmpH
    val isSquare = abs(bmpRatio - 1f) < 0.15f
    val scale = if (isSquare) maxOf(destW / bmpW, destH / bmpH) else minOf(destW / bmpW, destH / bmpH)
    val scaledW = bmpW * scale
    val scaledH = bmpH * scale
    val left = (scaledW - destW) / 2f
    val top = (scaledH - destH) / 2f
    val cropLeft = (left / scale).toInt().coerceIn(0, bitmap.width)
    val cropTop = (top / scale).toInt().coerceIn(0, bitmap.height)
    val cropRight = ((left + destW) / scale).toInt().coerceIn(cropLeft, bitmap.width)
    val cropBottom = ((top + destH) / scale).toInt().coerceIn(cropTop, bitmap.height)
    return android.graphics.Rect(cropLeft, cropTop, cropRight, cropBottom)
}
