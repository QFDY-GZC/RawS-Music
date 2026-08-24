package com.rawsmusic.module.player

import android.os.SystemClock
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.core.common.utils.OnlinePlaybackDiagnostics
import com.rawsmusic.module.data.source.playback.MusicSourceResolvedStreamRegistry
import com.rawsmusic.module.data.prefs.UsbBitPerfectMode
import com.rawsmusic.module.player.usb.UsbDeviceAudioCapabilities

/** Chooses the already-defined USB or Android output target for one playback session. */
internal class PlaybackTargetCoordinator(
    private val tag: String,
    private val usbResolver: UsbPlaybackTargetResolver,
    private val androidResolver: AndroidPlaybackTargetResolver,
) {
    data class Target(
        val usb: UsbPlaybackTargetResolver.Target? = null,
        val android: AndroidPlaybackTargetResolver.Target? = null,
    )

    fun resolve(
        sourcePath: String,
        usbExclusive: Boolean,
        usbBitPerfectPolicyMode: UsbBitPerfectMode,
        usbCapabilities: UsbDeviceAudioCapabilities? = null,
    ): Target {
        if (usbExclusive) {
            return Target(
                usb = usbResolver.resolve(
                    sourcePath = sourcePath,
                    usbBitPerfectPolicyMode = usbBitPerfectPolicyMode,
                    capabilities = usbCapabilities,
                ),
            )
        }

        val onlineEntry = MusicSourceResolvedStreamRegistry.lookup(sourcePath)
        onlineEntry?.let { entry ->
            AppLogger.i(
                tag,
                "${OnlinePlaybackDiagnostics.PREFIX} TARGET_RESOLVE_START " +
                    "generation=${entry.generation} lane=android",
            )
        }
        val startedAt = SystemClock.elapsedRealtime()
        val androidTarget = androidResolver.resolve(sourcePath)
        onlineEntry?.let { entry ->
            AppLogger.i(
                tag,
                "${OnlinePlaybackDiagnostics.PREFIX} TARGET_RESOLVE_END generation=${entry.generation} " +
                    "lane=android target=${androidTarget.sampleRate}Hz/" +
                    "${androidTarget.bitsPerSample}bit/${androidTarget.channels}ch " +
                    "elapsedMs=${SystemClock.elapsedRealtime() - startedAt}",
            )
        }
        return Target(android = androidTarget)
    }
}
