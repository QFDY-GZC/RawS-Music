package com.rawsmusic.core.ui.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.util.Log
import android.view.View

class DynamicCoverBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "DynamicCoverBg"
        private const val CROP_RATIO = 0.6f       // 取中心 60%
        private const val BLUR_RADIUS = 180f       // 180% 高斯模糊
    }

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private var sourceBitmap: Bitmap? = null
    private var clipPath: Path? = null
    private var topCornerRadius = 0f
    private var dimAmount = 0f
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var baseColor = 0xFF1A1614.toInt()
    private var onColorsExtracted: ((primary: Int, dark: Int) -> Unit)? = null
    var isLightBackground = false
        private set

    // 缓存 RenderNode 对象，避免每帧 new 导致 GPU 资源抖动
    // display list 每帧重新录制（beginRecording/endRecording 很便宜）
    private var cachedBlurNode: RenderNode? = null
    private var cachedBlurNodeWidth = 0
    private var cachedBlurNodeHeight = 0

    fun setArtwork(bitmap: Bitmap?) {
        if (bitmap == null || bitmap.isRecycled) return
        Log.d(TAG, "setArtwork: ${bitmap.width}x${bitmap.height}")
        sourceBitmap = bitmap
        extractColors(bitmap)
        invalidate()
    }

    fun setTopCornerRadius(radius: Float) {
        topCornerRadius = radius.coerceAtMost(8f * resources.displayMetrics.density)
        updateClipPath()
    }

    fun setReducedEffects(reduced: Boolean) {}
    fun pauseAnimations() {}
    fun resumeAnimations() { invalidate() }

    fun setDimAmount(amount: Float) {
        dimAmount = amount.coerceIn(0f, 1f)
        invalidate()
    }

    fun setOverlayColors(colors: IntArray) { invalidate() }
    fun setBlurRadius(radius: Int) {}

    fun setOnColorsExtractedListener(listener: ((primary: Int, dark: Int) -> Unit)?) {
        onColorsExtracted = listener
    }

    fun setDynamic(dynamic: Boolean) {}
    fun setAllowDynamicRunning(allow: Boolean) {}

    fun syncFrom(source: DynamicCoverBackgroundView): Boolean {
        val bitmap = source.sourceBitmap
        if (bitmap == null || bitmap.isRecycled) return false
        this.isLightBackground = source.isLightBackground
        setArtwork(bitmap)
        return true
    }

    fun isAnimationRunning(): Boolean = false

    fun clearArtwork() {
        sourceBitmap = null
        invalidate()
    }

    private fun extractColors(bitmap: Bitmap?) {
        if (bitmap == null || bitmap.isRecycled) return

        try {
            val w = bitmap.width
            val h = bitmap.height
            val cropW = (w * CROP_RATIO).toInt().coerceAtLeast(1)
            val cropH = (h * CROP_RATIO).toInt().coerceAtLeast(1)
            val x = (w - cropW) / 2
            val y = (h - cropH) / 2
            val centerCrop = Bitmap.createBitmap(bitmap, x, y, cropW, cropH)

            var totalLuminance = 0.0
            val step = 8
            var pixelCount = 0
            for (py in 0 until centerCrop.height step step) {
                for (px in 0 until centerCrop.width step step) {
                    val pixel = centerCrop.getPixel(px, py)
                    val r = Color.red(pixel) / 255.0
                    val g = Color.green(pixel) / 255.0
                    val b = Color.blue(pixel) / 255.0
                    totalLuminance += 0.299 * r + 0.587 * g + 0.114 * b
                    pixelCount++
                }
            }
            if (centerCrop !== bitmap) centerCrop.recycle()

            val avgLuminance = if (pixelCount > 0) totalLuminance / pixelCount else 0.0
            isLightBackground = avgLuminance > 0.7

            val primary = if (isLightBackground) darken(0xFF2A2A2A.toInt(), 0.1f) else darken(0xFF1A1A2E.toInt(), 0.1f)
            val dark = if (isLightBackground) darken(0xFF1A1A1A.toInt(), 0.2f) else darken(0xFF0D0D1A.toInt(), 0.2f)
            onColorsExtracted?.invoke(primary, dark)
        } catch (_: Exception) {}
    }

    private fun darken(color: Int, amount: Float): Int {
        val r = (Color.red(color) * (1f - amount)).toInt().coerceIn(0, 255)
        val g = (Color.green(color) * (1f - amount)).toInt().coerceIn(0, 255)
        val b = (Color.blue(color) * (1f - amount)).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

    private fun updateClipPath() {
        if (width <= 0 || height <= 0) return
        val path = Path()
        val r = topCornerRadius
        path.addRoundRect(
            RectF(0f, 0f, width.toFloat(), height.toFloat()),
            floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f),
            Path.Direction.CW
        )
        clipPath = path
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        val bmp = sourceBitmap
        if (bmp == null || bmp.isRecycled) {
            canvas.drawColor(baseColor)
            return
        }

        Log.d(TAG, "onDraw: ${width}x${height}, bmp=${bmp.width}x${bmp.height}, vis=$visibility, alpha=$alpha")

        val w = width.toFloat()
        val h = height.toFloat()

        // 计算中心 60% 裁剪区域
        val cropW = (bmp.width * CROP_RATIO).toInt()
        val cropH = (bmp.height * CROP_RATIO).toInt()
        val cropX = (bmp.width - cropW) / 2
        val cropY = (bmp.height - cropH) / 2
        val srcRect = Rect(cropX, cropY, cropX + cropW, cropY + cropH)

        val path = clipPath
        if (path != null) {
            canvas.save()
            canvas.clipPath(path)
        }

        // 使用 RenderEffect 实现高斯模糊（Android 12+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && canvas.isHardwareAccelerated) {
            val blur = BLUR_RADIUS.toInt()
            val extW = w.toInt() + blur * 2
            val extH = h.toInt() + blur * 2

            // 复用缓存的 RenderNode 对象，仅在尺寸变化时重建
            val blurNode = cachedBlurNode?.takeIf {
                cachedBlurNodeWidth == extW && cachedBlurNodeHeight == extH
            } ?: run {
                val node = RenderNode("coverBlur")
                node.setPosition(-blur, -blur, w.toInt() + blur, h.toInt() + blur)
                node.setRenderEffect(RenderEffect.createBlurEffect(BLUR_RADIUS, BLUR_RADIUS, Shader.TileMode.CLAMP))
                cachedBlurNode = node
                cachedBlurNodeWidth = extW
                cachedBlurNodeHeight = extH
                node
            }

            // 每帧重新录制 display list（便宜操作，确保后台恢复后内容正确）
            val rnCanvas = blurNode.beginRecording(extW, extH)
            rnCanvas.drawBitmap(bmp, srcRect, RectF(0f, 0f, extW.toFloat(), extH.toFloat()), bitmapPaint)
            blurNode.endRecording()

            canvas.drawRenderNode(blurNode)
        } else {
            // 低版本回退：直接绘制（无模糊）
            canvas.drawBitmap(bmp, srcRect, RectF(0f, 0f, w, h), bitmapPaint)
        }

        if (dimAmount > 0f) {
            dimPaint.color = Color.BLACK
            dimPaint.alpha = (dimAmount * 255f).toInt()
            canvas.drawPaint(dimPaint)
        }

        if (path != null) {
            canvas.restore()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateClipPath()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cachedBlurNode?.discardDisplayList()
        cachedBlurNode = null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // 不清除 cachedBlurNode，让它在下次 onDraw 时重新录制
        if (sourceBitmap != null && !sourceBitmap!!.isRecycled) {
            invalidate()
        }
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == View.VISIBLE && sourceBitmap != null && !sourceBitmap!!.isRecycled) {
            cachedBlurNode = null
            invalidate()
        }
    }
}
