package com.rawsmusic.module.data.source.playback

import com.rawsmusic.core.common.source.RawResolvedAudioSource
import com.rawsmusic.core.common.source.RawSourceMediaItem
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.core.common.utils.OnlinePlaybackDiagnostics
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.StateFlow

const val MUSIC_SOURCE_ONLINE_ENCODING_MARKER = "rawsmusic-online-resolved"

enum class MusicSourceBackendStatus {
    Idle,
    Preparing,
    Playing,
    Paused,
    Completed,
    Error,
}

data class MusicSourcePlaybackRequest(
    val generation: Long,
    val item: RawSourceMediaItem,
    val source: RawResolvedAudioSource,
    val startPositionMs: Long = 0L,
    val autoPlay: Boolean = true,
)

data class MusicSourceBackendSnapshot(
    val generation: Long = 0L,
    val sourceUrl: String = "",
    val status: MusicSourceBackendStatus = MusicSourceBackendStatus.Idle,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val error: String = "",
)

/**
 * Pluggable playback boundary for online sources.
 *
 * module:data owns source resolution and portal UI state, while module:player installs an
 * implementation backed by PlayerController/FfmpegAudioPlayer. HTTP(S) responses are materialized
 * as local cache files before this boundary, so online PCM passes through the same DSP renderer as
 * local PCM without requiring network protocols in the bundled FFmpeg library.
 */
interface MusicSourcePlaybackBackend {
    val snapshot: StateFlow<MusicSourceBackendSnapshot>

    fun play(request: MusicSourcePlaybackRequest)
    fun pause()
    fun resume()
    fun seekTo(positionMs: Long)
    fun stop()
}

/**
 * Header/options bridge used by the player module when FFmpeg opens a resolved HTTP(S) URL.
 * Entries are intentionally bounded and removed when a backend session is stopped/replaced.
 */
object MusicSourceResolvedStreamRegistry {
    data class Entry(
        val generation: Long,
        val item: RawSourceMediaItem,
        val source: RawResolvedAudioSource,
        val registeredAtMs: Long = System.currentTimeMillis(),
    )

    private const val MAX_ENTRIES = 16
    private val entries = ConcurrentHashMap<String, Entry>()

    fun register(request: MusicSourcePlaybackRequest) {
        entries[request.source.url] = Entry(
            generation = request.generation,
            item = request.item,
            source = request.source,
        )
        AppLogger.i(
            TAG,
            "${OnlinePlaybackDiagnostics.PREFIX} REGISTRY_REGISTER generation=${request.generation} " +
                "quality=${request.source.quality} headers=${OnlinePlaybackDiagnostics.headerNames(request.source.headers)} " +
                "url=${OnlinePlaybackDiagnostics.safeUrl(request.source.url)} size=${entries.size}"
        )
        trim()
    }

    fun lookup(path: String): Entry? = entries[path]

    fun remove(path: String, generation: Long? = null) {
        if (generation == null) {
            val removed = entries.remove(path)
            if (removed != null) {
                AppLogger.i(
                    TAG,
                    "${OnlinePlaybackDiagnostics.PREFIX} REGISTRY_REMOVE generation=${removed.generation} " +
                        "url=${OnlinePlaybackDiagnostics.safeUrl(path)} size=${entries.size}"
                )
            }
            return
        }
        var removed = false
        entries.computeIfPresent(path) { _, current ->
            if (current.generation == generation) {
                removed = true
                null
            } else {
                current
            }
        }
        if (removed) {
            AppLogger.i(
                TAG,
                "${OnlinePlaybackDiagnostics.PREFIX} REGISTRY_REMOVE generation=$generation " +
                    "url=${OnlinePlaybackDiagnostics.safeUrl(path)} size=${entries.size}"
            )
        }
    }

    private fun trim() {
        if (entries.size <= MAX_ENTRIES) return
        entries.entries
            .sortedBy { it.value.registeredAtMs }
            .take((entries.size - MAX_ENTRIES).coerceAtLeast(0))
            .forEach { entries.remove(it.key, it.value) }
    }

    private const val TAG = "OnlineStreamRegistry"
}
