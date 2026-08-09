package com.rawsmusic.core.ui.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class RawMiuixOverlayBackRuntimeTest {
    @Test
    fun visibilityIsReferenceCountedWithoutOwningDismissActions() {
        val first = Any()
        val second = Any()
        try {
            MiuixOverlayBackRuntime.attach(first)
            MiuixOverlayBackRuntime.attach(second)
            assertEquals(2, MiuixOverlayBackRuntime.activeCount)

            MiuixOverlayBackRuntime.detach(second)
            assertEquals(1, MiuixOverlayBackRuntime.activeCount)
        } finally {
            MiuixOverlayBackRuntime.detach(second)
            MiuixOverlayBackRuntime.detach(first)
        }
        assertEquals(0, MiuixOverlayBackRuntime.activeCount)
    }

    @Test
    fun duplicateAttachAndDetachAreIdempotent() {
        val token = Any()
        try {
            MiuixOverlayBackRuntime.attach(token)
            MiuixOverlayBackRuntime.attach(token)
            assertEquals(1, MiuixOverlayBackRuntime.activeCount)
        } finally {
            MiuixOverlayBackRuntime.detach(token)
            MiuixOverlayBackRuntime.detach(token)
        }
        assertEquals(0, MiuixOverlayBackRuntime.activeCount)
    }
}
