package com.rawsmusic.module.player

import com.rawsmusic.core.common.utils.AppLogger

/**
 * Owns the lifecycle boundary for the optional Android native PCM backend.
 *
 * FfmpegAudioPlayer should not directly close a NativeAudioEngine from many
 * scattered sites.  Detach first, then stop/flush/close through this helper so
 * route callbacks and playback recovery never see a half-released engine.
 */
internal class NativeAudioEngineLifecycleController(
    private val tag: String,
    private val currentProvider: () -> NativeAudioEngine?,
    private val detachCurrent: () -> NativeAudioEngine?
) {
    fun currentOrNull(): NativeAudioEngine? = currentProvider()

    fun startCurrent(reason: String): Boolean {
        val engine = currentProvider() ?: return false
        return try {
            val ok = engine.start()
            if (!ok) {
                AppLogger.w(tag, "NativeAudioEngine start returned false: reason=$reason")
            }
            ok
        } catch (t: Throwable) {
            AppLogger.w(tag, "NativeAudioEngine start failed: reason=$reason", t)
            false
        }
    }

    fun pauseCurrent(reason: String) {
        val engine = currentProvider() ?: return
        try {
            engine.pause()
        } catch (t: Throwable) {
            AppLogger.w(tag, "NativeAudioEngine pause failed: reason=$reason", t)
        }
    }

    fun stopCurrent(reason: String) {
        val engine = currentProvider() ?: return
        try {
            engine.stop()
        } catch (t: Throwable) {
            AppLogger.w(tag, "NativeAudioEngine stop failed: reason=$reason", t)
        }
    }

    fun flushCurrent(reason: String) {
        val engine = currentProvider() ?: return
        try {
            engine.flush()
        } catch (t: Throwable) {
            AppLogger.w(tag, "NativeAudioEngine flush failed: reason=$reason", t)
        }
    }

    fun setVolumeCurrent(volume: Float, reason: String) {
        val engine = currentProvider() ?: return
        try {
            engine.setVolume(volume)
        } catch (t: Throwable) {
            AppLogger.w(tag, "NativeAudioEngine setVolume failed: reason=$reason", t)
        }
    }

    fun detach(reason: String): NativeAudioEngine? {
        val engine = detachCurrent()
        if (engine != null) {
            AppLogger.d(tag, "NativeAudioEngine detached: reason=$reason mode=${engine.actualMode}")
        }
        return engine
    }

    fun detachAndClose(
        reason: String,
        stop: Boolean = true,
        flush: Boolean = false
    ): NativeAudioEngine? {
        val engine = detach(reason)
        closeDetached(engine, reason, stop, flush)
        return engine
    }

    fun closeDetached(
        engine: NativeAudioEngine?,
        reason: String,
        stop: Boolean = true,
        flush: Boolean = false
    ) {
        if (engine == null) return
        if (stop) {
            try {
                engine.stop()
            } catch (t: Throwable) {
                AppLogger.w(tag, "NativeAudioEngine detached stop failed: reason=$reason", t)
            }
        }
        if (flush) {
            try {
                engine.flush()
            } catch (t: Throwable) {
                AppLogger.w(tag, "NativeAudioEngine detached flush failed: reason=$reason", t)
            }
        }
        try {
            engine.close()
            AppLogger.i(tag, "NativeAudioEngine closed: reason=$reason mode=${engine.actualMode}")
        } catch (t: Throwable) {
            AppLogger.w(tag, "NativeAudioEngine close failed: reason=$reason", t)
        }
    }
}
