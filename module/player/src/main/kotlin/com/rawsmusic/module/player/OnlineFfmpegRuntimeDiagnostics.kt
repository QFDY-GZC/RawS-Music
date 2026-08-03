package com.rawsmusic.module.player

import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.core.common.utils.OnlinePlaybackDiagnostics
import java.util.concurrent.ConcurrentHashMap

/**
 * Diagnostics for the prebuilt librawsmusic_ffmpeg.so actually used by playback.
 *
 * The reference ffmpeg_bridge.cpp in app/src/main/cpp is not compiled into the app target,
 * so logging added there cannot reveal protocol/open failures from the imported library.
 */
internal object OnlineFfmpegRuntimeDiagnostics {
    private const val TAG = "OnlineFfmpegDiag"
    private val capabilityLogged = ConcurrentHashMap.newKeySet<String>()
    private val captureLock = Any()
    private val loaded: Boolean by lazy {
        runCatching {
            System.loadLibrary("rawscoreservice")
            true
        }.getOrElse { error ->
            AppLogger.e(
                TAG,
                "${OnlinePlaybackDiagnostics.PREFIX} DIAG_NATIVE_LOAD_FAIL " +
                    "error=${OnlinePlaybackDiagnostics.errorSummary(error)}",
                error,
            )
            false
        }
    }

    fun <T> captureOpen(url: String, block: () -> T): T {
        if (!isRemote(url) || !loaded) return block()
        return synchronized(captureLock) {
            val descriptor = runCatching { nativeBeginCapture(url) }
                .getOrElse { error ->
                    AppLogger.e(
                        TAG,
                        "${OnlinePlaybackDiagnostics.PREFIX} DIAG_BEGIN_FAIL " +
                            "url=${OnlinePlaybackDiagnostics.safeUrl(url)} " +
                            "error=${OnlinePlaybackDiagnostics.errorSummary(error)}",
                        error,
                    )
                    ""
                }
            val capabilityKey = descriptor.ifBlank { "unknown" }
            if (capabilityLogged.add(capabilityKey)) {
                AppLogger.i(
                    TAG,
                    "${OnlinePlaybackDiagnostics.PREFIX} FFMPEG_CAPABILITIES $descriptor " +
                        "${OnlinePlaybackDiagnostics.urlShape(url)} " +
                        "url=${OnlinePlaybackDiagnostics.safeUrl(url)}"
                )
            }
            try {
                block()
            } finally {
                runCatching { nativeEndCapture() }
                    .onFailure { error ->
                        AppLogger.w(
                            TAG,
                            "${OnlinePlaybackDiagnostics.PREFIX} DIAG_END_FAIL " +
                                "error=${OnlinePlaybackDiagnostics.errorSummary(error)}",
                            error,
                        )
                    }
            }
        }
    }

    private fun isRemote(value: String): Boolean =
        value.startsWith("http://", ignoreCase = true) ||
            value.startsWith("https://", ignoreCase = true)

    private external fun nativeBeginCapture(url: String): String
    private external fun nativeEndCapture()
}
