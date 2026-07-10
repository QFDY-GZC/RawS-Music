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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import kotlin.math.roundToInt

interface DynamicCoverBackgroundHost {
    val isLightBackground: Boolean

    fun setBlurredCoverStyle(enabled: Boolean)
    fun setArtwork(bitmap: Bitmap?)
    fun clearArtwork()
    fun setTopCornerRadius(radius: Float)
    fun setReducedEffects(reduced: Boolean)
    fun pauseAnimations()
    fun resumeAnimations()
    fun setDimAmount(amount: Float)
    fun setThemeLightMode(isLight: Boolean)
    fun setOverlayColors(colors: IntArray)
    fun setBlurRadius(radius: Int)
    fun setOnColorsExtractedListener(listener: ((primary: Int, dark: Int) -> Unit)?)
    fun setDynamic(dynamic: Boolean)
    fun setAllowDynamicRunning(allow: Boolean)
    fun syncFrom(source: DynamicCoverBackgroundHost): Boolean
    fun isAnimationRunning(): Boolean
}

class DynamicCoverBackgroundState : DynamicCoverBackgroundHost {
    internal var sourceBitmap by mutableStateOf<Bitmap?>(null)
        private set
    internal var overlayColorStops by mutableStateOf(intArrayOf())
        private set
    internal var baseColor by mutableStateOf(Color(0xFF171717))
        private set
    internal var backgroundDimAmount by mutableFloatStateOf(0f)
        private set
    internal var topClipCornerRadius by mutableFloatStateOf(0f)
        private set
    internal var blurredCoverStyleEnabled by mutableStateOf(false)
        private set

    override var isLightBackground: Boolean by mutableStateOf(false)
        private set

    override fun setBlurredCoverStyle(enabled: Boolean) {
        blurredCoverStyleEnabled = enabled
    }

    override fun setArtwork(bitmap: Bitmap?) {
        if (bitmap == null || bitmap.isRecycled) return
        sourceBitmap = bitmap
    }

    override fun clearArtwork() {
        sourceBitmap = null
    }

    override fun setTopCornerRadius(radius: Float) {
        topClipCornerRadius = radius.coerceAtLeast(0f)
    }

    override fun setReducedEffects(reduced: Boolean) = Unit
    override fun pauseAnimations() = Unit
    override fun resumeAnimations() = Unit

    override fun setDimAmount(amount: Float) {
        backgroundDimAmount = amount.coerceIn(0f, 1f)
    }

    override fun setThemeLightMode(isLight: Boolean) {
        isLightBackground = isLight
        baseColor = if (isLight) Color.White else Color(0xFF171717)
    }

    override fun setOverlayColors(colors: IntArray) {
        overlayColorStops = colors.copyOf()
    }

    override fun setBlurRadius(radius: Int) = Unit

    override fun setOnColorsExtractedListener(listener: ((primary: Int, dark: Int) -> Unit)?) {
    }

    override fun setDynamic(dynamic: Boolean) = Unit
    override fun setAllowDynamicRunning(allow: Boolean) = Unit
    override fun isAnimationRunning(): Boolean = false

    override fun syncFrom(source: DynamicCoverBackgroundHost): Boolean {
        val other = source as? DynamicCoverBackgroundState ?: return false
        val bitmap = other.sourceBitmap
        if (bitmap == null || bitmap.isRecycled) return false
        isLightBackground = other.isLightBackground
        baseColor = other.baseColor
        backgroundDimAmount = other.backgroundDimAmount
        topClipCornerRadius = other.topClipCornerRadius
        blurredCoverStyleEnabled = other.blurredCoverStyleEnabled
        overlayColorStops = other.overlayColorStops.copyOf()
        setArtwork(bitmap)
        return true
    }
}

@Composable
fun DynamicCoverBackground(
    state: DynamicCoverBackgroundState,
    modifier: Modifier = Modifier
) {
    val bitmap = state.sourceBitmap
    val overlays = state.overlayColorStops
    val background = state.baseColor
    val corner = state.topClipCornerRadius
    val dim = state.backgroundDimAmount
    val blurredStyle = state.blurredCoverStyleEnabled

    Canvas(modifier.fillMaxSize()) {
        if (size.width <= 0f || size.height <= 0f) return@Canvas
        val clip = if (corner > 0f) {
            Path().apply {
                addRoundRect(
                    RoundRect(
                        rect = Rect(0f, 0f, size.width, size.height),
                        topLeft = CornerRadius(corner, corner),
                        topRight = CornerRadius(corner, corner),
                        bottomLeft = CornerRadius.Zero,
                        bottomRight = CornerRadius.Zero
                    )
                )
            }
        } else {
            null
        }

        val drawBlock: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit = {
            if (bitmap == null || bitmap.isRecycled) {
                drawRect(background, topLeft = Offset.Zero, size = size)
            } else {
                val cropW = (bitmap.width * 0.6f).roundToInt().coerceAtLeast(1)
                val cropH = (bitmap.height * 0.6f).roundToInt().coerceAtLeast(1)
                val cropX = (bitmap.width - cropW) / 2
                val cropY = (bitmap.height - cropH) / 2
                val src = android.graphics.Rect(cropX, cropY, cropX + cropW, cropY + cropH)
                drawIntoCanvas { canvas ->
                    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG).apply {
                        if (blurredStyle) {
                            val cm = android.graphics.ColorMatrix()
                            cm.setSaturation(2.5f)
                            colorFilter = android.graphics.ColorMatrixColorFilter(cm)
                        }
                    }
                    canvas.nativeCanvas.drawBitmap(
                        bitmap,
                        src,
                        android.graphics.RectF(0f, 0f, size.width, size.height),
                        paint
                    )
                }
                if (blurredStyle) {
                    drawRect(Color.Black.copy(alpha = 0.30f), topLeft = Offset.Zero, size = size)
                    drawRect(Color.White.copy(alpha = 0.08f), topLeft = Offset.Zero, size = size)
                } else {
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(Color.Black.copy(alpha = 0.22f), Color.Black.copy(alpha = 0.55f))
                        ),
                        topLeft = Offset.Zero,
                        size = size
                    )
                }
            }

            overlays.forEach { overlay ->
                drawRect(Color(overlay), topLeft = Offset.Zero, size = size)
            }
            if (dim > 0f) {
                drawRect(Color.Black.copy(alpha = dim), topLeft = Offset.Zero, size = size)
            }
        }

        if (clip != null) {
            clipPath(clip) { drawBlock() }
        } else {
            drawBlock()
        }
    }
}
