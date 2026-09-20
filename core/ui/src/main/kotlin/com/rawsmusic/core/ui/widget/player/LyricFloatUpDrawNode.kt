package com.rawsmusic.core.ui.widget.player

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo

/**
 * Choreographer-driven lyric float-up draw owner.
 *
 * Per-frame work deliberately bypasses Snapshot State/Recomposer. Attached active word/glyph
 * nodes subscribe to the lyric surface's single Choreographer clock; the clock advances once per
 * vsync and each listener invalidates only its own DrawModifierNode. Text/layout inputs are updated
 * through the element when ordinary composition changes (word, typography, glyph slices).
 */
private class LyricFloatUpDrawNode(
    var clock: LyricDirectRenderClock?,
    var lineDispatcher: LyricLineRenderDispatcher?,
    var peakLiftPx: Float,
    var nodeTextScalePx: Float,
    var nodeSegmentIndex: Int,
    var lineGeometry: LyricLineFloatUpGeometry,
    var slices: List<LyricGlyphSliceSpec>,
    var wholeLocalCenterPx: Float?,
) : Modifier.Node(), DrawModifierNode, LyricDirectFrameListener, LyricLineFrameTarget {

    override val shouldAutoInvalidate: Boolean
        get() = false

    private var framePositionMs: Float = 0f
    private var samplingMode: LyricFrameSamplingMode = LyricFrameSamplingMode.STATIC_DOWN

    override val segmentIndex: Int
        get() = nodeSegmentIndex

    override val textScalePx: Float
        get() = nodeTextScalePx

    override fun onAttach() {
        super.onAttach()
        framePositionMs = currentOwnerPosition()
        samplingMode = currentSamplingMode(framePositionMs)
        val dispatcher = lineDispatcher
        if (dispatcher != null) {
            dispatcher.addFrameTarget(this)
        } else {
            clock?.addListener(this)
        }
    }

    override fun onDetach() {
        lineDispatcher?.removeFrameTarget(this)
        clock?.removeListener(this)
        super.onDetach()
    }

    override fun onLyricFrame(positionMs: Float) {
        if (!isAttached || lineDispatcher != null) return
        framePositionMs = positionMs
        val nextMode = currentSamplingMode(positionMs)
        val modeChanged = nextMode != samplingMode
        samplingMode = nextMode
        if (nextMode == LyricFrameSamplingMode.ANIMATED || modeChanged) invalidateDraw()
    }

    override fun onLyricLineFrame(positionMs: Float, mode: LyricFrameSamplingMode) {
        if (!isAttached) return
        framePositionMs = positionMs
        samplingMode = mode
        invalidateDraw()
    }

    fun update(
        clock: LyricDirectRenderClock?,
        lineDispatcher: LyricLineRenderDispatcher?,
        peakLiftPx: Float,
        textScalePx: Float,
        segmentIndex: Int,
        lineGeometry: LyricLineFloatUpGeometry,
        slices: List<LyricGlyphSliceSpec>,
        wholeLocalCenterPx: Float?,
    ) {
        val ownerChanged = this.clock !== clock || this.lineDispatcher !== lineDispatcher
        if (ownerChanged && isAttached) {
            this.lineDispatcher?.removeFrameTarget(this)
            this.clock?.removeListener(this)
        }
        this.clock = clock
        this.lineDispatcher = lineDispatcher
        if (ownerChanged && isAttached) {
            val dispatcher = this.lineDispatcher
            if (dispatcher != null) dispatcher.addFrameTarget(this) else this.clock?.addListener(this)
        }
        this.peakLiftPx = peakLiftPx
        this.nodeTextScalePx = textScalePx
        this.nodeSegmentIndex = segmentIndex
        this.lineGeometry = lineGeometry
        this.slices = slices
        this.wholeLocalCenterPx = wholeLocalCenterPx
        framePositionMs = currentOwnerPosition()
        samplingMode = currentSamplingMode(framePositionMs)
        lineDispatcher?.refreshFrameTarget(this)
        invalidateDraw()
    }

    private fun currentOwnerPosition(): Float =
        lineDispatcher?.currentPosition() ?: clock?.currentPosition() ?: 0f

    private fun currentSamplingMode(positionMs: Float): LyricFrameSamplingMode =
        lineGeometry.frameSamplingMode(
            slotIndex = segmentIndex,
            positionMs = positionMs,
            textScalePx = textScalePx,
        )

    override fun ContentDrawScope.draw() {
        val contentDrawScope = this
        when (samplingMode) {
            LyricFrameSamplingMode.STATIC_DOWN -> {
                // Exact plateau: no clipping, no per-glyph fraction work, one cached text draw.
                drawContent()
                return
            }
            LyricFrameSamplingMode.STATIC_UP -> {
                // Exact completed plateau: the whole shaped word shares the same final Y offset.
                translate(top = -peakLiftPx) { contentDrawScope.drawContent() }
                return
            }
            LyricFrameSamplingMode.ANIMATED -> Unit
        }

        val wholeCenter = wholeLocalCenterPx
        if (wholeCenter != null) {
            val fraction = lineGeometry.fractionForLocalCenter(
                slotIndex = segmentIndex,
                localCenterPx = wholeCenter,
                positionMs = framePositionMs,
                textScalePx = textScalePx,
            )
            translate(top = -peakLiftPx * fraction) { contentDrawScope.drawContent() }
            return
        }

        if (slices.isEmpty()) {
            drawContent()
            return
        }

        // The effect has exact 0/1 plateaus around its cosine band. Merge adjacent equal runs so only
        // the handful of slices actually crossing the spatial window require separate replays.
        var runLeft = slices.first().leftPx
        var runRight = slices.first().rightPx
        var runLift = fractionFor(slices.first())

        fun drawRun(left: Float, right: Float, lift: Float) {
            clipRect(
                left = left,
                top = -peakLiftPx - 1f,
                right = right,
                bottom = size.height + 1f,
            ) {
                translate(top = -peakLiftPx * lift) { contentDrawScope.drawContent() }
            }
        }

        for (index in 1 until slices.size) {
            val slice = slices[index]
            val lift = fractionFor(slice)
            if (lift == runLift) {
                runRight = slice.rightPx
            } else {
                drawRun(runLeft, runRight, runLift)
                runLeft = slice.leftPx
                runRight = slice.rightPx
                runLift = lift
            }
        }
        drawRun(runLeft, runRight, runLift)
    }

    private fun fractionFor(slice: LyricGlyphSliceSpec): Float =
        lineGeometry.fractionForLocalCenter(
            slotIndex = segmentIndex,
            localCenterPx = slice.logicalCenterPx,
            positionMs = framePositionMs,
            textScalePx = textScalePx,
        )


}

private data class LyricFloatUpDrawElement(
    val clock: LyricDirectRenderClock?,
    val lineDispatcher: LyricLineRenderDispatcher?,
    val peakLiftPx: Float,
    val textScalePx: Float,
    val segmentIndex: Int,
    val lineGeometry: LyricLineFloatUpGeometry,
    val slices: List<LyricGlyphSliceSpec>,
    val wholeLocalCenterPx: Float?,
) : ModifierNodeElement<LyricFloatUpDrawNode>() {
    override fun create(): LyricFloatUpDrawNode = LyricFloatUpDrawNode(
        clock = clock,
        lineDispatcher = lineDispatcher,
        peakLiftPx = peakLiftPx,
        nodeTextScalePx = textScalePx,
        nodeSegmentIndex = segmentIndex,
        lineGeometry = lineGeometry,
        slices = slices,
        wholeLocalCenterPx = wholeLocalCenterPx,
    )

    override fun update(node: LyricFloatUpDrawNode) {
        node.update(
            clock = clock,
            lineDispatcher = lineDispatcher,
            peakLiftPx = peakLiftPx,
            textScalePx = textScalePx,
            segmentIndex = segmentIndex,
            lineGeometry = lineGeometry,
            slices = slices,
            wholeLocalCenterPx = wholeLocalCenterPx,
        )
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "lyricFloatUpDraw"
    }
}

internal fun Modifier.lyricFloatUpDraw(
    clock: LyricDirectRenderClock? = null,
    lineDispatcher: LyricLineRenderDispatcher? = null,
    peakLiftPx: Float,
    textScalePx: Float,
    segmentIndex: Int,
    lineGeometry: LyricLineFloatUpGeometry,
    slices: List<LyricGlyphSliceSpec> = emptyList(),
    wholeLocalCenterPx: Float? = null,
): Modifier {
    require(clock != null || lineDispatcher != null) { "Lyric float-up requires a render owner" }
    return this then LyricFloatUpDrawElement(
        clock = clock,
        lineDispatcher = lineDispatcher,
        peakLiftPx = peakLiftPx,
    textScalePx = textScalePx,
    segmentIndex = segmentIndex,
    lineGeometry = lineGeometry,
    slices = slices,
    wholeLocalCenterPx = wholeLocalCenterPx,
    )
}
