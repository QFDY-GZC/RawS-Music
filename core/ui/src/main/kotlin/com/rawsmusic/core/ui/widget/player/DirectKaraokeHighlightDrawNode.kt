package com.rawsmusic.core.ui.widget.player

import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint as AndroidPaint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo

/**
 * Direct display-frame owner for the karaoke highlight sweep.
 *
 * Ordinary timed words register through one line dispatcher instead of each becoming another
 * surface-clock listener. The highlighted Text stays composition-stable; only this draw mask moves.
 * The feather also reuses one Android shader/matrix/paint for the node, avoiding the old per-frame
 * Brush/list/Paint allocation on the 120 Hz hot path.
 */
private class DirectKaraokeHighlightDrawNode(
    var clock: LyricDirectRenderClock?,
    var lineDispatcher: LyricLineRenderDispatcher?,
    var beginMs: Long,
    var endMs: Long,
    var featherPx: Float,
    var rtl: Boolean,
) : Modifier.Node(), DrawModifierNode, LyricDirectFrameListener, LyricLineHighlightFrameTarget {

    override val shouldAutoInvalidate: Boolean
        get() = false

    override val highlightBeginMs: Long
        get() = beginMs

    override val highlightEndMs: Long
        get() = endMs

    private var framePositionMs: Float = 0f
    private var lastProgress: Float = Float.NaN

    private val shaderMatrix = Matrix()
    private val ltrMaskShader = LinearGradient(
        0f,
        0f,
        1f,
        0f,
        intArrayOf(0xFFFFFFFF.toInt(), 0x00FFFFFF),
        null,
        Shader.TileMode.CLAMP,
    )
    private val rtlMaskShader = LinearGradient(
        0f,
        0f,
        1f,
        0f,
        intArrayOf(0x00FFFFFF, 0xFFFFFFFF.toInt()),
        null,
        Shader.TileMode.CLAMP,
    )
    private val maskPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }

    override fun onAttach() {
        super.onAttach()
        framePositionMs = currentOwnerPosition()
        lastProgress = progressFor(framePositionMs)
        val dispatcher = lineDispatcher
        if (dispatcher != null) {
            dispatcher.addHighlightTarget(this)
        } else {
            clock?.addListener(this)
        }
    }

    override fun onDetach() {
        lineDispatcher?.removeHighlightTarget(this)
        clock?.removeListener(this)
        super.onDetach()
    }

    override fun onLyricFrame(positionMs: Float) {
        if (!isAttached || lineDispatcher != null) return
        framePositionMs = positionMs
        val progress = progressFor(positionMs)
        if (progress == lastProgress && (progress <= 0f || progress >= 1f)) return
        lastProgress = progress
        invalidateDraw()
    }

    override fun onHighlightLineFrame(positionMs: Float) {
        if (!isAttached) return
        framePositionMs = positionMs
        lastProgress = progressFor(positionMs)
        invalidateDraw()
    }

    fun update(
        clock: LyricDirectRenderClock?,
        lineDispatcher: LyricLineRenderDispatcher?,
        beginMs: Long,
        endMs: Long,
        featherPx: Float,
        rtl: Boolean,
    ) {
        val ownerChanged = this.clock !== clock || this.lineDispatcher !== lineDispatcher
        if (ownerChanged && isAttached) {
            this.lineDispatcher?.removeHighlightTarget(this)
            this.clock?.removeListener(this)
        }
        this.clock = clock
        this.lineDispatcher = lineDispatcher
        if (ownerChanged && isAttached) {
            val dispatcher = this.lineDispatcher
            if (dispatcher != null) dispatcher.addHighlightTarget(this) else this.clock?.addListener(this)
        }
        this.beginMs = beginMs
        this.endMs = endMs.coerceAtLeast(beginMs + 1L)
        this.featherPx = featherPx.coerceAtLeast(0f)
        this.rtl = rtl
        framePositionMs = currentOwnerPosition()
        lastProgress = progressFor(framePositionMs)
        lineDispatcher?.refreshHighlightTarget(this)
        invalidateDraw()
    }

    override fun ContentDrawScope.draw() {
        val progress = progressFor(framePositionMs)
        if (progress <= 0f || size.width <= 0f || size.height <= 0f) return
        if (progress >= 1f) {
            drawContent()
            return
        }

        val contentDrawScope = this
        val width = size.width.coerceAtLeast(1f)
        val feather = featherPx.coerceIn(0f, width)
        val edge = if (rtl) width * (1f - progress) else width * progress

        if (!rtl) {
            val featherStart = (edge - feather).coerceIn(0f, width)
            if (featherStart > 0f) {
                clipRect(left = 0f, top = 0f, right = featherStart, bottom = size.height) {
                    contentDrawScope.drawContent()
                }
            }
            if (edge > featherStart) {
                drawFeatherMask(
                    contentDrawScope = contentDrawScope,
                    left = featherStart,
                    right = edge,
                    shader = ltrMaskShader,
                )
            }
        } else {
            val featherEnd = (edge + feather).coerceIn(0f, width)
            if (featherEnd < width) {
                clipRect(left = featherEnd, top = 0f, right = width, bottom = size.height) {
                    contentDrawScope.drawContent()
                }
            }
            if (featherEnd > edge) {
                drawFeatherMask(
                    contentDrawScope = contentDrawScope,
                    left = edge,
                    right = featherEnd,
                    shader = rtlMaskShader,
                )
            }
        }
    }

    private fun ContentDrawScope.drawFeatherMask(
        contentDrawScope: ContentDrawScope,
        left: Float,
        right: Float,
        shader: LinearGradient,
    ) {
        val stripWidth = (right - left).coerceAtLeast(0.001f)
        val nativeCanvas = drawContext.canvas.nativeCanvas
        val checkpoint = nativeCanvas.saveLayer(left, 0f, right, size.height, null)
        clipRect(left = left, top = 0f, right = right, bottom = size.height) {
            contentDrawScope.drawContent()
        }
        shaderMatrix.reset()
        shaderMatrix.setScale(stripWidth, 1f)
        shaderMatrix.postTranslate(left, 0f)
        shader.setLocalMatrix(shaderMatrix)
        maskPaint.shader = shader
        nativeCanvas.drawRect(left, 0f, right, size.height, maskPaint)
        nativeCanvas.restoreToCount(checkpoint)
    }

    private fun currentOwnerPosition(): Float =
        lineDispatcher?.currentPosition() ?: clock?.currentPosition() ?: 0f

    private fun progressFor(positionMs: Float): Float =
        DirectKaraokeHighlightSpec.progress(positionMs, beginMs, endMs)
}

private data class DirectKaraokeHighlightElement(
    val clock: LyricDirectRenderClock?,
    val lineDispatcher: LyricLineRenderDispatcher?,
    val beginMs: Long,
    val endMs: Long,
    val featherPx: Float,
    val rtl: Boolean,
) : ModifierNodeElement<DirectKaraokeHighlightDrawNode>() {
    override fun create(): DirectKaraokeHighlightDrawNode = DirectKaraokeHighlightDrawNode(
        clock = clock,
        lineDispatcher = lineDispatcher,
        beginMs = beginMs,
        endMs = endMs,
        featherPx = featherPx,
        rtl = rtl,
    )

    override fun update(node: DirectKaraokeHighlightDrawNode) {
        node.update(
            clock = clock,
            lineDispatcher = lineDispatcher,
            beginMs = beginMs,
            endMs = endMs,
            featherPx = featherPx,
            rtl = rtl,
        )
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "directKaraokeHighlight"
        properties["beginMs"] = beginMs
        properties["endMs"] = endMs
    }
}

internal fun Modifier.directKaraokeHighlight(
    clock: LyricDirectRenderClock? = null,
    lineDispatcher: LyricLineRenderDispatcher? = null,
    beginMs: Long,
    endMs: Long,
    featherPx: Float,
    rtl: Boolean,
): Modifier {
    require(clock != null || lineDispatcher != null) { "Direct karaoke highlight requires a render owner" }
    return this.then(
        DirectKaraokeHighlightElement(
            clock = clock,
            lineDispatcher = lineDispatcher,
            beginMs = beginMs,
            endMs = endMs,
            featherPx = featherPx,
            rtl = rtl,
        )
    )
}
