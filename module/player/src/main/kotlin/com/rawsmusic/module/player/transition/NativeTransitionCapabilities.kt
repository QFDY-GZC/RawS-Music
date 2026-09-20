package com.rawsmusic.module.player.transition

import com.rawsmusic.core.common.utils.AppLogger

/**
 * Runtime proof that the Phase 0-9A3 native translation units are present in the
 * loaded rawscoreservice library. This is intentionally separate from route
 * telemetry: a compiled feature may still be bypassed by policy, which is
 * reported by the individual processors.
 */
internal object NativeTransitionCapabilities {
    private const val TRACE = 1L shl 0
    private const val TRACK_SLOTS = 1L shl 1
    private const val PCM_SLOTS = 1L shl 2
    private const val HANDOFF_BARRIER = 1L shl 3
    private const val TRANSPORT_FADE = 1L shl 4
    private const val SEEK_BARRIER = 1L shl 5
    private const val MANUAL_CROSSFADE = 1L shl 6
    private const val AUTO_CROSSFADE = 1L shl 7
    private const val GAPLESS_STITCHER = 1L shl 8
    private const val USB_GAIN_OWNER = 1L shl 9
    private const val CONTINUOUS_PCM_QUEUE = 1L shl 10
    private const val TRACK_RENDER_TIMELINE = 1L shl 11
    private const val ATOMIC_PENDING_QUEUE_BIND = 1L shl 12

    private const val REQUIRED = TRACE or TRACK_SLOTS or PCM_SLOTS or HANDOFF_BARRIER or
        TRANSPORT_FADE or SEEK_BARRIER or MANUAL_CROSSFADE or AUTO_CROSSFADE or
        GAPLESS_STITCHER or USB_GAIN_OWNER or CONTINUOUS_PCM_QUEUE or TRACK_RENDER_TIMELINE or
        ATOMIC_PENDING_QUEUE_BIND

    private val libraryLoaded: Boolean by lazy {
        runCatching { System.loadLibrary("rawscoreservice") }.isSuccess
    }

    data class Snapshot(
        val libraryLoaded: Boolean,
        val compiledMask: Long,
        val missingMask: Long,
    ) {
        val complete: Boolean get() = libraryLoaded && missingMask == 0L
    }

    private val cachedSnapshot: Snapshot by lazy {
        val mask = if (libraryLoaded) {
            runCatching { nativeCompiledFeatureMask() }.getOrDefault(0L)
        } else {
            0L
        }
        Snapshot(
            libraryLoaded = libraryLoaded,
            compiledMask = mask,
            missingMask = REQUIRED and mask.inv(),
        )
    }

    val complete: Boolean get() = cachedSnapshot.complete
    val atomicPendingQueueBind: Boolean
        get() = cachedSnapshot.libraryLoaded &&
            (cachedSnapshot.compiledMask and ATOMIC_PENDING_QUEUE_BIND) != 0L

    fun verify(tag: String): Snapshot {
        val snapshot = cachedSnapshot
        val message = "TRANSITION_NATIVE_CAPS loaded=${snapshot.libraryLoaded} " +
            "complete=${snapshot.complete} mask=0x${snapshot.compiledMask.toString(16)} " +
            "missing=${describe(snapshot.missingMask)}"
        if (snapshot.complete) AppLogger.i(tag, message) else AppLogger.e(tag, message)
        return snapshot
    }

    private fun describe(mask: Long): String {
        if (mask == 0L) return "none"
        val names = buildList {
            if (mask and TRACE != 0L) add("trace")
            if (mask and TRACK_SLOTS != 0L) add("track_slots")
            if (mask and PCM_SLOTS != 0L) add("pcm_slots")
            if (mask and HANDOFF_BARRIER != 0L) add("handoff_barrier")
            if (mask and TRANSPORT_FADE != 0L) add("transport_fade")
            if (mask and SEEK_BARRIER != 0L) add("seek_barrier")
            if (mask and MANUAL_CROSSFADE != 0L) add("manual_crossfade")
            if (mask and AUTO_CROSSFADE != 0L) add("auto_crossfade")
            if (mask and GAPLESS_STITCHER != 0L) add("gapless_stitcher")
            if (mask and USB_GAIN_OWNER != 0L) add("usb_gain_owner")
            if (mask and CONTINUOUS_PCM_QUEUE != 0L) add("continuous_pcm_queue")
            if (mask and TRACK_RENDER_TIMELINE != 0L) add("track_render_timeline")
            if (mask and ATOMIC_PENDING_QUEUE_BIND != 0L) add("atomic_pending_queue_bind")
        }
        return names.joinToString(",")
    }

    @JvmStatic private external fun nativeCompiledFeatureMask(): Long
}
