package com.rawsmusic.module.player.transition

import com.rawsmusic.core.common.utils.AppLogger

/** JNI-backed render-clock owner for track identity and pending-track position. */
internal class NativeTrackRenderTimeline private constructor(
    private val tag: String,
    @Volatile private var handle: Long,
) : AutoCloseable {

    enum class Event(val code: Int) {
        NONE(0),
        STARTED(1),
        COMMITTED(2),
        RETIRED(3),
        CANCELLED(4);

        companion object {
            fun fromCode(code: Int): Event = values().firstOrNull { it.code == code } ?: NONE
        }
    }

    data class Update(
        val event: Event,
        val positionFrames: Long,
    )

    companion object {
        private const val EVENT_SHIFT = 60
        private const val FRAME_MASK = (1L shl EVENT_SHIFT) - 1L

        private val libraryAvailable: Boolean by lazy {
            try {
                System.loadLibrary("rawscoreservice")
                true
            } catch (_: Throwable) {
                false
            }
        }

        fun createOrNull(tag: String): NativeTrackRenderTimeline? {
            if (!libraryAvailable) return null
            return try {
                val handle = nativeCreate()
                if (handle == 0L) null else NativeTrackRenderTimeline(tag, handle)
            } catch (t: Throwable) {
                AppLogger.w(tag, "Native track render timeline unavailable: ${t.message}")
                null
            }
        }

        @JvmStatic private external fun nativeCreate(): Long
        @JvmStatic private external fun nativeInstallCurrent(
            handle: Long,
            decoderSerial: Long,
            generation: Int,
            sampleRate: Int,
            positionFrames: Long,
        ): Boolean
        @JvmStatic private external fun nativeArmPending(
            handle: Long,
            decoderSerial: Long,
            generation: Int,
            sampleRate: Int,
        ): Boolean
        @JvmStatic private external fun nativeOnPendingFramesRendered(
            handle: Long,
            decoderSerial: Long,
            generation: Int,
            renderedFrames: Long,
        ): Long
        @JvmStatic private external fun nativeCommitPending(
            handle: Long,
            decoderSerial: Long,
            generation: Int,
        ): Long
        @JvmStatic private external fun nativeRetirePrevious(
            handle: Long,
            decoderSerial: Long,
            generation: Int,
        ): Long
        @JvmStatic private external fun nativeCancelPending(
            handle: Long,
            decoderSerial: Long,
            generation: Int,
        ): Long
        @JvmStatic private external fun nativeCurrentPositionFrames(handle: Long): Long
        @JvmStatic private external fun nativeSnapshot(handle: Long): String
        @JvmStatic private external fun nativeReset(handle: Long)
        @JvmStatic private external fun nativeClose(handle: Long)
    }

    fun installCurrent(
        decoderSerial: Long,
        generation: Int,
        sampleRate: Int,
        positionFrames: Long = 0L,
    ): Boolean {
        val h = handle
        if (h == 0L || decoderSerial <= 0L || generation < 0 || sampleRate <= 0) return false
        return try {
            nativeInstallCurrent(
                h,
                decoderSerial,
                generation,
                sampleRate,
                positionFrames.coerceAtLeast(0L),
            )
        } catch (t: Throwable) {
            AppLogger.w(tag, "Native track timeline install-current failed", t)
            false
        }
    }

    fun armPending(decoderSerial: Long, generation: Int, sampleRate: Int): Boolean {
        val h = handle
        if (h == 0L || decoderSerial <= 0L || generation < 0 || sampleRate <= 0) return false
        return try {
            nativeArmPending(h, decoderSerial, generation, sampleRate)
        } catch (t: Throwable) {
            AppLogger.w(tag, "Native track timeline arm failed", t)
            false
        }
    }

    fun onPendingFramesRendered(
        decoderSerial: Long,
        generation: Int,
        renderedFrames: Long,
    ): Update = callUpdate {
        nativeOnPendingFramesRendered(handle, decoderSerial, generation, renderedFrames)
    }

    fun commitPending(decoderSerial: Long, generation: Int): Update = callUpdate {
        nativeCommitPending(handle, decoderSerial, generation)
    }

    fun retirePrevious(decoderSerial: Long, generation: Int): Update = callUpdate {
        nativeRetirePrevious(handle, decoderSerial, generation)
    }

    fun cancelPending(decoderSerial: Long, generation: Int): Update = callUpdate {
        nativeCancelPending(handle, decoderSerial, generation)
    }

    fun currentPositionFrames(): Long {
        val h = handle
        return if (h == 0L) 0L else runCatching { nativeCurrentPositionFrames(h) }.getOrDefault(0L)
    }

    fun snapshot(): String {
        val h = handle
        return if (h == 0L) "track_timeline_closed" else runCatching { nativeSnapshot(h) }
            .getOrElse { "track_timeline_error:${it.javaClass.simpleName}" }
    }

    fun reset() {
        val h = handle
        if (h != 0L) runCatching { nativeReset(h) }
    }

    private inline fun callUpdate(block: () -> Long): Update {
        val h = handle
        if (h == 0L) return Update(Event.NONE, 0L)
        return try {
            unpack(block())
        } catch (t: Throwable) {
            AppLogger.w(tag, "Native track timeline call failed", t)
            Update(Event.NONE, 0L)
        }
    }

    private fun unpack(packed: Long): Update {
        val eventCode = ((packed ushr EVENT_SHIFT) and 0x0fL).toInt()
        return Update(
            event = Event.fromCode(eventCode),
            positionFrames = packed and FRAME_MASK,
        )
    }

    override fun close() {
        val h = handle
        handle = 0L
        if (h != 0L) runCatching { nativeClose(h) }
    }
}
