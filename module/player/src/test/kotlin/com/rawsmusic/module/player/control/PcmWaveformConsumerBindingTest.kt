package com.rawsmusic.module.player.control

import org.junit.Assert.*
import org.junit.Test

class PcmWaveformConsumerBindingTest {
    private class Source {
        var callback: Any? = null
        var active = false
        fun binding(owner: Any) = PcmWaveformConsumerBinding(owner,
            { callback }, { callback = it }, { active = it })
    }

    @Test fun oldActivityStoppingCannotDisableNewFullscreenOwner() {
        val source = Source()
        val main = source.binding(Any())
        val fullscreenOwner = Any()
        val fullscreen = source.binding(fullscreenOwner)
        main.update(true)
        fullscreen.update(true)
        main.update(false)
        assertTrue(source.active)
        assertSame(fullscreenOwner, source.callback)
    }

    @Test fun oldFullscreenClosingCannotDisableResumedMainOwner() {
        val source = Source()
        val mainOwner = Any()
        val main = source.binding(mainOwner)
        val fullscreen = source.binding(Any())
        main.update(true)
        fullscreen.update(true)
        main.update(true)
        fullscreen.release()
        assertTrue(source.active)
        assertSame(mainOwner, source.callback)
    }

    @Test fun releasingCurrentOwnerRestoresPreviousCallbackAndStopsCapture() {
        val source = Source()
        val previous = Any()
        source.callback = previous
        val fullscreen = source.binding(Any())
        fullscreen.update(true)
        fullscreen.update(true)
        fullscreen.release()
        assertFalse(source.active)
        assertSame(previous, source.callback)
    }

    @Test fun returningOwnerReacquiresCallbackAndRestartsCapture() {
        val source = Source()
        val mainOwner = Any()
        val main = source.binding(mainOwner)
        val fullscreen = source.binding(Any())
        main.update(true)
        fullscreen.update(true)
        fullscreen.release()
        main.update(true)
        assertTrue(source.active)
        assertSame(mainOwner, source.callback)
    }

    @Test fun permanentMainOwnerDoesNotRestoreAnObsoleteFullscreenCallback() {
        val source = Source()
        source.callback = Any()
        val main = PcmWaveformConsumerBinding(Any(), { source.callback },
            { source.callback = it }, { source.active = it }, restorePreviousCallback = false)
        main.update(true)
        main.release()
        assertNull(source.callback)
        assertFalse(source.active)
    }
}
