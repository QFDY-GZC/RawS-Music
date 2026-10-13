package com.rawsmusic.core.ui.widget.player

internal data class ClassicNativeRenderPacket(
    val frame: ClassicTimelineFrame,
    val trackArgb: Int,
    val fillArgb: Int,
    val density: Float,
    val offsetY: Float,
    val rtl: Boolean,
    val lightEnabled: Boolean,
)

internal fun shouldRedrawClassicNative(previous: ClassicNativeRenderPacket?, next: ClassicNativeRenderPacket) =
    previous != next
