package com.rawsmusic.core.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.widget.FrameLayout
import com.rawsmusic.core.ui.R

/**
 * 液态玻璃效果容器 (inspired by AndroidLiquidGlass)
 *
 * 特性：
 * - 高斯模糊背景（API 31+ 使用 RenderEffect，低版本回退为半透明深色）
 * - 毛玻璃半透明表面色
 * - 镜头折射高光（顶部渐变光泽）
 * - 边缘高光描边
 * - 顶部/底部内阴影
 * - 底部漫反射光斑
 *
 * 重要：模糊仅应用于背景绘制，不会模糊子View内容
 */
class LiquidGlassView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    var cornerRadius: Float = 24f * resources.displayMetrics.density
    var surfaceColor: Int = Color.argb(153, 26, 22, 20) // 60% 深色半透明 (#1A1614)
    var highlightAlpha: Float = 0.35f
    var innerShadowAlpha: Float = 0.18f
    var blurRadius: Float = 25f * resources.displayMetrics.density

    private val density = resources.displayMetrics.density

    // 背景绘制
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val surfacePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val innerShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lensHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bottomGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val clipPath = Path()
    private val bgRect = RectF()

    // 模糊用的RenderNode（仅模糊背景，不影响子View）
    private var blurRenderNode: RenderNode? = null

    private val isBlurSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    init {
        // 核心修复 1：强制移除系统默认背景，防止漏出方形底色
        background = null

        // 核心修复 2：必须裁剪子 View，防止子 View 绘制到圆角之外形成矩形污染
        clipChildren = true
        clipToPadding = true

        // 读取XML属性
        attrs?.let {
            val a = context.obtainStyledAttributes(it, R.styleable.LiquidGlassView, defStyleAttr, 0)
            cornerRadius = a.getDimension(R.styleable.LiquidGlassView_cornerRadius, cornerRadius)
            surfaceColor = a.getColor(R.styleable.LiquidGlassView_surfaceColor, surfaceColor)
            blurRadius = a.getDimension(R.styleable.LiquidGlassView_blurRadius, blurRadius)
            highlightAlpha = a.getFloat(R.styleable.LiquidGlassView_highlightAlpha, highlightAlpha)
            a.recycle()
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) {
            super.dispatchDraw(canvas)
            return
        }

        // 0. 创建圆角裁剪路径
        clipPath.reset()
        bgRect.set(0f, 0f, w, h)
        clipPath.addRoundRect(bgRect, cornerRadius, cornerRadius, Path.Direction.CW)

        canvas.save()
        canvas.clipPath(clipPath)

        // 1. 绘制模糊背景（仅模糊背景色块，不模糊子View）
        drawBlurredBackground(canvas, w, h)

        // 2. 绘制半透明毛玻璃表面色
        surfacePaint.color = surfaceColor
        surfacePaint.style = Paint.Style.FILL
        surfacePaint.isAntiAlias = true
        canvas.drawRoundRect(0f, 0f, w, h, cornerRadius, cornerRadius, surfacePaint)

        // 3. 镜头折射高光 — 顶部光泽渐变
        drawLensHighlight(canvas, w, h)

        // 4. 绘制边缘高光描边
        drawHighlight(canvas, w, h)

        // 5. 绘制内阴影
        drawInnerShadow(canvas, w, h)

        // 6. 底部漫反射光斑
        drawBottomGlow(canvas, w, h)

        canvas.restore()

        // 7. 绘制子View（不受模糊影响）
        super.dispatchDraw(canvas)
    }

    /**
     * 绘制模糊背景
     * 使用RenderNode + RenderEffect实现高斯模糊，仅模糊背景色块
     */
    private fun drawBlurredBackground(canvas: Canvas, w: Float, h: Float) {
        if (isBlurSupported && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val node = blurRenderNode ?: RenderNode("glassBlurBg").also {
                    blurRenderNode = it
                }
                node.setPosition(0, 0, width, height)

                // 在RenderNode中绘制背景色（使用surfaceColor作为模糊基底）
                val nodeCanvas = node.beginRecording()
                bgPaint.color = surfaceColor
                nodeCanvas.drawRoundRect(0f, 0f, w, h, cornerRadius, cornerRadius, bgPaint)
                node.endRecording()

                // 对RenderNode应用高斯模糊
                val blurEffect = RenderEffect.createBlurEffect(
                    blurRadius, blurRadius, Shader.TileMode.CLAMP
                )
                node.setRenderEffect(blurEffect)

                canvas.drawRenderNode(node)
            } catch (_: Exception) {
                drawFallbackBackground(canvas, w, h)
            }
        } else {
            drawFallbackBackground(canvas, w, h)
        }
    }

    /** 回退背景 (API < 31) */
    private fun drawFallbackBackground(canvas: Canvas, w: Float, h: Float) {
        bgPaint.color = surfaceColor
        canvas.drawRoundRect(0f, 0f, w, h, cornerRadius, cornerRadius, bgPaint)
    }

    /**
     * 镜头折射高光 — 模拟液态玻璃的透镜效果
     * 顶部白色渐变光泽，模拟光线折射
     */
    private fun drawLensHighlight(canvas: Canvas, w: Float, h: Float) {
        val highlightHeight = h * 0.4f
        val gradient = LinearGradient(
            0f, 0f, 0f, highlightHeight,
            Color.argb((0.12f * 255).toInt(), 255, 255, 255),  // 顶部微弱白光
            Color.argb(0, 255, 255, 255),                      // 底部透明
            Shader.TileMode.CLAMP
        )
        lensHighlightPaint.reset()
        lensHighlightPaint.isAntiAlias = true
        lensHighlightPaint.shader = gradient
        canvas.drawRoundRect(
            RectF(0f, 0f, w, highlightHeight),
            cornerRadius, cornerRadius, lensHighlightPaint
        )
    }

    /** 边缘高光描边 */
    private fun drawHighlight(canvas: Canvas, w: Float, h: Float) {
        highlightPaint.reset()
        highlightPaint.isAntiAlias = true
        highlightPaint.style = Paint.Style.STROKE
        highlightPaint.strokeWidth = 1.2f * density
        highlightPaint.color = Color.argb((highlightAlpha * 255).toInt(), 255, 255, 255)
        canvas.drawRoundRect(
            RectF(0.5f, 0.5f, w - 0.5f, h - 0.5f),
            cornerRadius, cornerRadius, highlightPaint
        )
    }

    /** 内阴影 */
    private fun drawInnerShadow(canvas: Canvas, w: Float, h: Float) {
        innerShadowPaint.reset()
        innerShadowPaint.isAntiAlias = true
        innerShadowPaint.style = Paint.Style.FILL

        val shadowHeight = 14f * density

        // 顶部内阴影
        for (i in 0 until shadowHeight.toInt()) {
            val fraction = i.toFloat() / shadowHeight
            val alpha = (innerShadowAlpha * 255 * (1f - fraction)).toInt()
            innerShadowPaint.color = Color.argb(alpha, 0, 0, 0)
            canvas.drawRect(
                cornerRadius, i.toFloat(), w - cornerRadius, i + 1f,
                innerShadowPaint
            )
        }

        // 底部内阴影
        for (i in 0 until shadowHeight.toInt()) {
            val fraction = i.toFloat() / shadowHeight
            val alpha = (innerShadowAlpha * 255 * 0.4f * (1f - fraction)).toInt()
            innerShadowPaint.color = Color.argb(alpha, 0, 0, 0)
            val y = h - i - 1f
            canvas.drawRect(
                cornerRadius, y, w - cornerRadius, y + 1f,
                innerShadowPaint
            )
        }
    }

    /**
     * 底部漫反射光斑 — 模拟光线穿透玻璃后的散焦光斑
     */
    private fun drawBottomGlow(canvas: Canvas, w: Float, h: Float) {
        val glowHeight = h * 0.2f
        val gradient = LinearGradient(
            0f, h - glowHeight, 0f, h,
            Color.argb(0, 255, 255, 255),
            Color.argb((0.06f * 255).toInt(), 255, 255, 255),  // 底部微弱暖光
            Shader.TileMode.CLAMP
        )
        bottomGlowPaint.reset()
        bottomGlowPaint.isAntiAlias = true
        bottomGlowPaint.shader = gradient
        canvas.drawRoundRect(
            RectF(0f, h - glowHeight, w, h),
            cornerRadius, cornerRadius, bottomGlowPaint
        )
    }
}
