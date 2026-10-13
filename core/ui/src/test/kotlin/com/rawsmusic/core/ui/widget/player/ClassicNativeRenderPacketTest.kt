package com.rawsmusic.core.ui.widget.player

import org.junit.Assert.*
import org.junit.Test

class ClassicNativeRenderPacketTest {
    private val initial = ClassicNativeRenderPacket(
        ClassicTimelineFrame(0.5f, press = 1f,
            cone = ClassicConeFrame(0.2f, 2f, 0f, 0.014f, 0.3f, 0.3f)),
        0x33ffffff, 0xfff0b0a0.toInt(), 3.5f, 14f, false, true)

    @Test fun releaseMustRepaintEvenWhenPlaybackPositionIsFrozen() {
        val released = initial.copy(frame = initial.frame.copy(press = 0f,
            cone = ClassicConeFrame(0.6f, 0.6f, 0.2f, 0.008f, 1f, 0.6f)))
        assertTrue(shouldRedrawClassicNative(initial, released))
    }

    @Test fun shaderOnlyChangeMustNotDependOnPlaybackOrLifecycleInvalidation() {
        val coneChanged = initial.copy(frame = initial.frame.copy(
            cone = initial.frame.cone.copy(alpha1 = 0.7f)))
        assertTrue(shouldRedrawClassicNative(initial, coneChanged))
        assertTrue(shouldRedrawClassicNative(initial, initial.copy(fillArgb = -1)))
        assertFalse(shouldRedrawClassicNative(initial, initial.copy()))
    }
}
