package com.rawsmusic.core.ui.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.util.AttributeSet
import android.view.View
import androidx.palette.graphics.Palette
import coil.ImageLoader
import coil.request.ImageRequest
import coil.imageLoader

class ImmersiveBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var isImmersiveEnabled: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    var isMiniCoverEnabled: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    var isDarkMode: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** 封面图片透明度（0f~1f），用于主界面25%透明显示 */
    var coverAlpha: Float = 1f
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var onImmersiveDrawingChanged: ((Boolean) -> Unit)? = null

    var bottomPaddingHeight: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    private var sourceBitmap: Bitmap? = null
    private var dominantColor: Int = Color.DKGRAY
    private var mutedColor: Int = Color.GRAY

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private var coverGeneration = 0
    private var currentPath: String? = null

    fun setCover(path: String?) {
        if (path == currentPath) {
            invalidate()
            return
        }
        currentPath = path

        if (path.isNullOrBlank()) {
            sourceBitmap = null
            invalidate()
            return
        }

        val gen = ++coverGeneration
        val imageLoader: ImageLoader = context.imageLoader
        val request = ImageRequest.Builder(context)
            .data(path)
            .size(width.coerceAtLeast(1080), height.coerceAtLeast(1080))
            .allowHardware(false)
            .target { drawable ->
                val bitmap = (drawable as? BitmapDrawable)?.bitmap
                if (bitmap != null && !bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0) {
                    if (gen != coverGeneration) return@target

                    sourceBitmap = bitmap

                    Palette.from(bitmap).generate { palette ->
                        if (gen != coverGeneration) return@generate

                        dominantColor = palette?.getDarkVibrantColor(Color.DKGRAY) ?: Color.DKGRAY
                        mutedColor = palette?.getDarkMutedColor(Color.GRAY) ?: Color.GRAY
                        invalidate()
                    }
                } else {
                    if (gen != coverGeneration) return@target
                    sourceBitmap = null
                }
            }
            .build()
        imageLoader.enqueue(request)
    }

    fun clear() {
        sourceBitmap = null
        currentPath = null
        invalidate()
    }

    private fun shouldDraw(): Boolean {
        if (sourceBitmap == null) return false
        if (alpha <= 0.01f) return false
        return isImmersiveEnabled || isMiniCoverEnabled
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (!shouldDraw()) return

        onImmersiveDrawingChanged?.invoke(true)

        val w = width.toFloat()
        val h = height.toFloat()
        val splitY = h * 0.55f
        val blurStartY = splitY - h * 0.1f

        sourceBitmap?.let { bmp ->
            // 各层独立裁剪区域
            val coverSrcRect = calculateCropRect(bmp, w, splitY)
            val fullSrcRect = calculateCropRect(bmp, w, h)

            // 检测是否为非正方形专辑图
            val bmpRatio = bmp.width.toFloat() / bmp.height.toFloat()
            val isSquare = kotlin.math.abs(bmpRatio - 1.0f) < 0.15f  // 宽高比在0.85-1.15之间视为正方形

            // 步骤0：对于非正方形专辑图，先绘制全图拉伸作为模糊背景填充空白区域
            if (!isSquare) {
                canvas.save()
                canvas.clipRect(0f, 0f, w, splitY)
                paint.alpha = (coverAlpha * 255).toInt()
                // 使用全图拉伸填充（作为模糊背景）
                canvas.drawBitmap(bmp, Rect(0, 0, bmp.width, bmp.height), RectF(0f, 0f, w, splitY), paint)
                paint.alpha = 255
                canvas.restore()
            }

            // 步骤1：绘制清晰封面图（0 到 splitY），应用 coverAlpha 透明度
            canvas.save()
            canvas.clipRect(0f, 0f, w, splitY)
            paint.alpha = (coverAlpha * 255).toInt()
            canvas.drawBitmap(bmp, coverSrcRect, RectF(0f, 0f, w, splitY), paint)
            paint.alpha = 255
            canvas.restore()

            // 步骤2：绘制底部镜像图（从 splitY 到屏幕底部）
            canvas.save()
            canvas.clipRect(0f, splitY, w, h)
            val mirrorMatrix = Matrix()
            mirrorMatrix.setScale(1f, -1f, w / 2f, splitY)
            canvas.concat(mirrorMatrix)
            canvas.drawBitmap(bmp, coverSrcRect, RectF(0f, 0f, w, splitY), paint)
            canvas.restore()

            // 步骤3：全屏150%模糊层 + 渐变遮罩
            // 注意：drawRenderNode 需要硬件加速，软件渲染模式下跳过模糊效果
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && canvas.isHardwareAccelerated) {
                val blurRadius = 150f
                val blurEffect = RenderEffect.createBlurEffect(blurRadius, blurRadius, Shader.TileMode.CLAMP)

                val blurNode = android.graphics.RenderNode("fullBlur")
                blurNode.setPosition(0, 0, w.toInt(), h.toInt())
                val rnCanvas = blurNode.beginRecording(w.toInt(), h.toInt())
                rnCanvas.drawBitmap(bmp, fullSrcRect, RectF(0f, 0f, w, h), paint)
                blurNode.endRecording()
                blurNode.setRenderEffect(blurEffect)

                val saveCount = canvas.saveLayer(0f, 0f, w, h, null)
                canvas.drawRenderNode(blurNode)

                val fadePaint = Paint(Paint.ANTI_ALIAS_FLAG)
                fadePaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                val fullGradient = LinearGradient(
                    0f, 0f, 0f, h,
                    intArrayOf(
                        Color.TRANSPARENT,
                        Color.TRANSPARENT,
                        Color.BLACK,
                        Color.BLACK
                    ),
                    floatArrayOf(
                        0f,
                        (blurStartY / h).coerceIn(0f, 1f),
                        (splitY / h).coerceIn(0f, 1f),
                        1f
                    ),
                    Shader.TileMode.CLAMP
                )
                fadePaint.shader = fullGradient
                canvas.drawRect(0f, 0f, w, h, fadePaint)

                canvas.restoreToCount(saveCount)
            }
        }

        // 顶部状态栏融合
        val statusBarShader = LinearGradient(
            0f, 0f, 0f, dpToPx(60f),
            (dominantColor and 0x00FFFFFF.toInt()) or 0x99000000.toInt(),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        paint.shader = statusBarShader
        canvas.drawRect(0f, 0f, w, dpToPx(60f), paint)
        paint.shader = null

        onImmersiveDrawingChanged?.invoke(false)
    }

    /**
     * 计算裁剪区域，智能处理非正方形专辑图
     * - 正方形专辑图：使用标准 center-crop（填满目标区域）
     * - 非正方形专辑图（横向或竖向矩形）：使用 fit 模式，完整显示图片
     */
    private fun calculateCropRect(bmp: Bitmap, destW: Float, destH: Float): Rect {
        val bmpW = bmp.width.toFloat()
        val bmpH = bmp.height.toFloat()
        // 防御性检查：避免除零或无效尺寸
        if (bmpW <= 0f || bmpH <= 0f || destW <= 0f || destH <= 0f) {
            return Rect(0, 0, bmp.width, bmp.height)
        }

        // 判断专辑图是否为正方形（宽高比接近1）
        val bmpRatio = bmpW / bmpH
        val isSquare = kotlin.math.abs(bmpRatio - 1.0f) < 0.15f  // 宽高比在0.85-1.15之间视为正方形

        val scale: Float
        if (isSquare) {
            // 正方形专辑图：使用 fill 模式，填满目标区域
            scale = maxOf(destW / bmpW, destH / bmpH)
        } else {
            // 非正方形专辑图（横向或竖向矩形）：使用 fit 模式，完整显示图片
            scale = minOf(destW / bmpW, destH / bmpH)
        }

        val scaledW = bmpW * scale
        val scaledH = bmpH * scale
        val left = (scaledW - destW) / 2f
        val top = (scaledH - destH) / 2f
        val cropLeft = (left / scale).toInt().coerceIn(0, bmp.width)
        val cropTop = (top / scale).toInt().coerceIn(0, bmp.height)
        val cropRight = ((left + destW) / scale).toInt().coerceIn(cropLeft, bmp.width)
        val cropBottom = ((top + destH) / scale).toInt().coerceIn(cropTop, bmp.height)
        return Rect(cropLeft, cropTop, cropRight, cropBottom)
    }

    private fun dpToPx(dp: Float): Float = dp * context.resources.displayMetrics.density
}
