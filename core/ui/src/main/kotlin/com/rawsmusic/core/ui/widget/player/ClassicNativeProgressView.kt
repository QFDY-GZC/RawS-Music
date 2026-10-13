package com.rawsmusic.core.ui.widget.player

import android.content.Context
import android.graphics.Canvas
import android.os.Build
import android.view.View
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.core.ui.widget.bitmaps.playerActivity

/**
 * One native display list owns track, white head and conic light, as in COUIIntentSeekBar.
 * No independently cached Compose SCREEN texture can retain an older press/glow frame.
 * Touch remains with the existing Compose gesture owner; this view only renders.
 */
internal class ClassicNativeProgressView(context: Context) : View(context) {
    private val painter = ClassicProgressPainter(context)
    private var packet: ClassicNativeRenderPacket? = null
    private var lastBoundPhase = -1
    private var lastDrawnPhase = -1

    init {
        setLayerType(LAYER_TYPE_NONE, null)
        isClickable = false
        isLongClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setWillNotDraw(false)
    }

    fun bind(next: ClassicNativeRenderPacket) {
        if (!shouldRedrawClassicNative(packet, next)) return
        packet = next
        val phase = next.frame.phaseFlags or if (next.frame.settled) 32 else 0
        if (phase != lastBoundPhase) {
            lastBoundPhase = phase
            trace("bind", next)
        }
        // Explicit native invalidation also covers stationary seeks, shader-only changes and
        // final release frames. It does not depend on another page, recording or a lifecycle event.
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val data = packet ?: return
        val frame = data.frame
        val trackWidth = width.toFloat() - 600f
        if (trackWidth <= 0f || height <= 0) return
        val save = canvas.save()
        try {
            canvas.translate(300f + frame.translation, 0f)
            painter.draw(canvas, trackWidth, height / 2f + data.offsetY,
                (4f + 2f * frame.press) * data.density, frame.progress,
                frame.headLength, frame.headAlpha, data.trackArgb, data.fillArgb,
                frame.cone, data.rtl, data.lightEnabled)
        } finally { canvas.restoreToCount(save) }
        val phase = frame.phaseFlags or if (frame.settled) 32 else 0
        if (phase != lastDrawnPhase) {
            lastDrawnPhase = phase
            trace("draw", data, canvas.isHardwareAccelerated)
        }
    }

    fun release() { painter.release() }

    private fun trace(stage: String, data: ClassicNativeRenderPacket, hardware: Boolean? = null) {
        val frame = data.frame
        val windowMode = if (Build.VERSION.SDK_INT >= 26) context.playerActivity()?.window?.colorMode else null
        AppLogger.i("ClassicTimelineRender", "$stage flags=${frame.phaseFlags} settled=${frame.settled} " +
            "press=${frame.press} p=${frame.progress} alpha=${frame.cone.alpha1}/${frame.cone.alpha2} " +
            "height=${frame.cone.height} coneK=${frame.cone.coneK} light=${data.lightEnabled} " +
            "hardware=$hardware windowMode=$windowMode colorPipeline=srgb-to-canvas size=${width}x$height")
    }
}
