package com.rawsmusic.core.ui.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import coil.imageLoader
import coil.request.ImageRequest

/**
 * 基于 Poweramp AAImageView 思路的自定义封面 View
 *
 * 核心机制（逆向自 Poweramp AAImageView）：
 * 1. 继承 View（非 ImageView），使用 BitmapShader + Matrix + drawRoundRect 渲染
 * 2. onMeasure 根据 Bitmap 宽高比自动计算高度，消除一切透明边距
 * 3. 当图片自然高度超过 maxHeight 时，按比例缩小宽度以保持宽高比，完整显示图片
 * 4. 使用 FIT_CENTER 模式确保图片完整显示，不裁剪
 * 5. drawRoundRect 实现圆角，无需 ShapeableImageView
 */
class CoverImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ==================== 位图 & Shader ====================
    private var bitmap: Bitmap? = null
    private var bitmapShader: BitmapShader? = null
    private val shaderPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val drawMatrix = Matrix()
    private val drawRect = RectF()

    // ==================== 属性 ====================
    /** 圆角半径（px） */
    var cornerRadius: Float = 0f
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** 最大高度（px），超出部分将按比例缩小宽度以保持宽高比 */
    var maxHeightPx: Int = Int.MAX_VALUE
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
            }
        }

    /** 当前加载的封面路径（用于调试） */
    private var currentPath: String? = null

    init {
        // 确保 onDraw 被调用
        setWillNotDraw(false)
    }

    // ==================== 公开 API ====================

    /**
     * 设置封面 Bitmap（从 Coil target 回调调用）
     * 会触发重新测量和绘制
     */
    fun setCoverBitmap(bmp: Bitmap?) {
        if (bitmap === bmp && bitmap != null) return
        bitmap = bmp
        currentPath = null
        if (bmp != null && bmp.width > 0 && bmp.height > 0) {
            bitmapShader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            shaderPaint.shader = bitmapShader
        } else {
            bitmapShader = null
            shaderPaint.shader = null
        }
        requestLayout()
        invalidate()
    }

    /**
     * 设置封面 Drawable（自动提取 Bitmap）
     */
    fun setCoverDrawable(drawable: Drawable?) {
        val bmp = when (drawable) {
            is BitmapDrawable -> drawable.bitmap
            else -> drawableToBitmap(drawable)
        }
        setCoverBitmap(bmp)
    }

    /**
     * 使用 Coil 加载封面图片
     * @param data 图片路径/Uri/任意 Coil 支持的数据类型
     * @param crossfade 是否启用过渡动画
     */
    fun loadCover(data: Any?, crossfade: Boolean = true) {
        if (data == null) {
            setCoverBitmap(null)
            return
        }
        currentPath = data.toString()
        val request = ImageRequest.Builder(context)
            .data(data)
            .crossfade(crossfade)
            .allowHardware(false) // BitmapShader 需要 software bitmap
            .target(
                onSuccess = { result ->
                    setCoverDrawable(result)
                },
                onError = {
                    setCoverBitmap(null)
                }
            )
            .build()
        context.imageLoader.enqueue(request)
    }

    /**
     * 清除封面
     */
    fun clearCover() {
        setCoverBitmap(null)
        currentPath = null
    }

    // ==================== 测量 ====================

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)

        val bm = bitmap
        if (bm != null && bm.width > 0 && bm.height > 0 && widthSize > 0) {
            // 根据 Bitmap 宽高比计算理想尺寸
            val aspectRatio = bm.height.toFloat() / bm.width.toFloat()
            var desiredWidth = widthSize
            var desiredHeight = (desiredWidth * aspectRatio).toInt()

            // 当高度超过 maxHeight 时，按比例缩小宽度以保持宽高比
            if (desiredHeight > maxHeightPx) {
                desiredHeight = maxHeightPx
                desiredWidth = (desiredHeight / aspectRatio).toInt()
                // 确保宽度不超过父布局允许的最大宽度
                if (desiredWidth > widthSize) {
                    desiredWidth = widthSize
                    desiredHeight = (desiredWidth * aspectRatio).toInt()
                }
            }

            // 受 MeasureSpec 约束
            val heightMode = MeasureSpec.getMode(heightMeasureSpec)
            val heightSize = MeasureSpec.getSize(heightMeasureSpec)
            val measuredHeight = when (heightMode) {
                MeasureSpec.EXACTLY -> heightSize
                MeasureSpec.AT_MOST -> desiredHeight.coerceAtMost(heightSize)
                else -> desiredHeight
            }

            // 如果宽度模式是 EXACTLY，使用计算后的 desiredWidth（可能因高度限制而缩小）
            // 确保 View 宽高比与 Bitmap 宽高比一致，消除 FIT_CENTER 的两侧留白
            val measuredWidth = when (widthMode) {
                MeasureSpec.EXACTLY -> desiredWidth.coerceAtMost(widthSize)
                MeasureSpec.AT_MOST -> desiredWidth.coerceAtMost(widthSize)
                else -> desiredWidth
            }

            setMeasuredDimension(measuredWidth, measuredHeight)
        } else {
            // 无位图时，使用默认测量或 0 高度
            val defaultHeight = resolveDefaultHeight(heightMeasureSpec)
            setMeasuredDimension(widthSize, defaultHeight)
        }
    }

    private fun resolveDefaultHeight(heightMeasureSpec: Int): Int {
        val mode = MeasureSpec.getMode(heightMeasureSpec)
        val size = MeasureSpec.getSize(heightMeasureSpec)
        return when (mode) {
            MeasureSpec.EXACTLY -> size
            MeasureSpec.AT_MOST -> 0.coerceAtMost(size)
            else -> 0
        }
    }

    // ==================== 绘制 ====================

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val bm = bitmap ?: return
        val shader = bitmapShader ?: return
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 0f || vh <= 0f) return

        // 更新 shader matrix
        updateShaderMatrix(shader, bm, vw, vh)

        // 绘制圆角矩形
        if (cornerRadius > 0f) {
            drawRect.set(0f, 0f, vw, vh)
            canvas.drawRoundRect(drawRect, cornerRadius, cornerRadius, shaderPaint)
        } else {
            canvas.drawRect(0f, 0f, vw, vh, shaderPaint)
        }
    }

    /**
     * 计算 FIT_CENTER 矩阵（完整显示图片，不裁剪）
     *
     * 当 View 宽高比与 Bitmap 宽高比一致时，scaleX == scaleY，完美适配。
     * 当比例不一致时，使用较小的缩放比例，确保图片完全在 View 内显示。
     */
    private fun updateShaderMatrix(shader: BitmapShader, bm: Bitmap, vw: Float, vh: Float) {
        val bw = bm.width.toFloat()
        val bh = bm.height.toFloat()
        if (bw <= 0f || bh <= 0f) return

        drawMatrix.reset()

        // FIT_CENTER：均匀缩放使两个维度都 <= View 维度（完整显示，不裁剪）
        val scaleX = vw / bw
        val scaleY = vh / bh
        val scale = minOf(scaleX, scaleY)

        val scaledW = bw * scale
        val scaledH = bh * scale
        val dx = (vw - scaledW) / 2f
        val dy = (vh - scaledH) / 2f

        drawMatrix.setScale(scale, scale)
        drawMatrix.postTranslate(dx, dy)

        shader.setLocalMatrix(drawMatrix)
    }

    // ==================== 工具方法 ====================

    /**
     * Drawable → Bitmap 转换
     */
    private fun drawableToBitmap(drawable: Drawable?): Bitmap? {
        drawable ?: return null
        if (drawable is BitmapDrawable) return drawable.bitmap

        val w = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else width
        val h = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else height
        if (w <= 0 || h <= 0) return null

        return try {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            drawable.setBounds(0, 0, w, h)
            drawable.draw(canvas)
            bmp
        } catch (e: Exception) {
            null
        }
    }
}
