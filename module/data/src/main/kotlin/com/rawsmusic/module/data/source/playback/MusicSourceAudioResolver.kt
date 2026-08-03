package com.rawsmusic.module.data.source.playback

import android.content.Context
import com.rawsmusic.core.common.source.RawResolvedAudioSource
import com.rawsmusic.core.common.source.RawSourceMediaItem
import com.rawsmusic.core.common.source.RawSourceQuality
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.core.common.utils.OnlinePlaybackDiagnostics
import android.os.SystemClock
import com.rawsmusic.module.data.source.LxSourcePluginStore
import com.rawsmusic.module.data.source.MusicSourcePluginStore
import com.rawsmusic.module.data.source.runtime.LxCatalogSearchService
import com.rawsmusic.module.data.source.runtime.MusicSourceRuntimeClient
import kotlinx.coroutines.CancellationException

/** Shared resolver used by independent online playback and download flows. */
object MusicSourceAudioResolver {
    val qualityOrderHighToLow: List<RawSourceQuality> = listOf(
        RawSourceQuality.HiRes,
        RawSourceQuality.Lossless,
        RawSourceQuality.Super,
        RawSourceQuality.High,
        RawSourceQuality.Standard,
    )

    val qualityOrderLowToHigh: List<RawSourceQuality> = qualityOrderHighToLow.reversed()

    fun preferredQuality(
        item: RawSourceMediaItem,
        preferred: RawSourceQuality,
    ): RawSourceQuality {
        val available = item.availableQualities.ifEmpty { setOf(RawSourceQuality.Standard) }
        if (preferred in available) return preferred
        return qualityCandidates(item, preferred).firstOrNull() ?: RawSourceQuality.Standard
    }

    fun qualityCandidates(
        item: RawSourceMediaItem,
        preferred: RawSourceQuality,
    ): List<RawSourceQuality> {
        val available = item.availableQualities.ifEmpty { setOf(RawSourceQuality.Standard) }
        val preferredIndex = qualityOrderLowToHigh.indexOf(preferred).coerceAtLeast(0)
        val atOrBelow = qualityOrderLowToHigh
            .take(preferredIndex + 1)
            .asReversed()
            .filter { it in available }
        if (atOrBelow.isNotEmpty()) return atOrBelow

        // This only happens when a restored preference is not advertised by the current source.
        return qualityOrderLowToHigh
            .drop(preferredIndex + 1)
            .filter { it in available }
            .ifEmpty { listOf(RawSourceQuality.Standard) }
    }

    suspend fun resolve(
        context: Context,
        item: RawSourceMediaItem,
        requestedQuality: RawSourceQuality,
    ): RawResolvedAudioSource {
        val startedAt = SystemClock.elapsedRealtime()
        val musicFreeSource = MusicSourcePluginStore.sources.value.firstOrNull {
            it.id == item.sourceId && it.enabled
        }
        val lxSource = LxSourcePluginStore.sources.value.firstOrNull {
            it.id == item.sourceId && it.enabled
        }
        val backend = when {
            musicFreeSource != null -> "musicfree"
            lxSource != null -> "lx"
            else -> "missing"
        }
        AppLogger.i(
            TAG,
            "${OnlinePlaybackDiagnostics.PREFIX} RESOLVE_START sourceId=${item.sourceId} " +
                "remoteId=${item.remoteId.take(160)} backend=$backend requested=$requestedQuality " +
                "available=${item.availableQualities.joinToString()}"
        )
        if (musicFreeSource == null && lxSource == null) {
            AppLogger.e(
                TAG,
                "${OnlinePlaybackDiagnostics.PREFIX} RESOLVE_FAIL stage=source_lookup " +
                    "sourceId=${item.sourceId} elapsedMs=${SystemClock.elapsedRealtime() - startedAt}"
            )
            throw IllegalStateException("对应音源不存在或已停用")
        }

        return try {
            val resolved = when {
                musicFreeSource != null -> resolveMusicFree(context, musicFreeSource.id, item, requestedQuality)
                lxSource != null -> resolveLx(context, lxSource.id, item, requestedQuality)
                else -> error("对应音源不存在或已停用")
            }
            musicFreeSource?.let { MusicSourcePluginStore.setLastError(it.id, "") }
            lxSource?.let { LxSourcePluginStore.setLastError(it.id, "") }
            AppLogger.i(
                TAG,
                "${OnlinePlaybackDiagnostics.PREFIX} RESOLVE_OK sourceId=${item.sourceId} " +
                    "quality=${resolved.quality} headers=${OnlinePlaybackDiagnostics.headerNames(resolved.headers)} " +
                    "ua=${!resolved.userAgent.isNullOrBlank()} url=${OnlinePlaybackDiagnostics.safeUrl(resolved.url)} " +
                    "elapsedMs=${SystemClock.elapsedRealtime() - startedAt}"
            )
            resolved
        } catch (cancelled: CancellationException) {
            AppLogger.w(
                TAG,
                "${OnlinePlaybackDiagnostics.PREFIX} RESOLVE_CANCEL sourceId=${item.sourceId} " +
                    "elapsedMs=${SystemClock.elapsedRealtime() - startedAt}"
            )
            throw cancelled
        } catch (error: Throwable) {
            val message = error.message.orEmpty().ifBlank { "音频地址解析失败" }.take(1_024)
            musicFreeSource?.let { MusicSourcePluginStore.setLastError(it.id, message) }
            lxSource?.let { LxSourcePluginStore.setLastError(it.id, message) }
            AppLogger.e(
                TAG,
                "${OnlinePlaybackDiagnostics.PREFIX} RESOLVE_FAIL sourceId=${item.sourceId} " +
                    "backend=$backend error=${OnlinePlaybackDiagnostics.errorSummary(error)} " +
                    "elapsedMs=${SystemClock.elapsedRealtime() - startedAt}",
                error,
            )
            throw error
        }
    }

    private suspend fun resolveMusicFree(
        context: Context,
        sourceId: String,
        item: RawSourceMediaItem,
        requestedQuality: RawSourceQuality,
    ): RawResolvedAudioSource {
        val source = MusicSourcePluginStore.sources.value.firstOrNull { it.id == sourceId }
            ?: throw IllegalStateException("对应音源已被删除")
        var lastError: Throwable? = null
        for (quality in qualityCandidates(item, requestedQuality)) {
            val startedAt = SystemClock.elapsedRealtime()
            AppLogger.i(
                TAG,
                "${OnlinePlaybackDiagnostics.PREFIX} RESOLVE_ATTEMPT backend=musicfree " +
                    "sourceId=$sourceId quality=$quality"
            )
            try {
                return MusicSourceRuntimeClient.resolveAudio(context, source, item, quality)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                lastError = error
                AppLogger.w(
                    TAG,
                    "${OnlinePlaybackDiagnostics.PREFIX} RESOLVE_ATTEMPT_FAIL backend=musicfree " +
                        "sourceId=$sourceId quality=$quality error=${OnlinePlaybackDiagnostics.errorSummary(error)} " +
                        "elapsedMs=${SystemClock.elapsedRealtime() - startedAt}",
                    error,
                )
            }
        }
        throw lastError ?: IllegalStateException("音源没有返回可用播放地址")
    }

    private suspend fun resolveLx(
        context: Context,
        sourceId: String,
        item: RawSourceMediaItem,
        requestedQuality: RawSourceQuality,
    ): RawResolvedAudioSource {
        val source = LxSourcePluginStore.sources.value.firstOrNull { it.id == sourceId }
            ?: throw IllegalStateException("对应 LX 解析源已被删除")
        var lastError: Throwable? = null
        for (quality in qualityCandidates(item, requestedQuality)) {
            val startedAt = SystemClock.elapsedRealtime()
            AppLogger.i(
                TAG,
                "${OnlinePlaybackDiagnostics.PREFIX} RESOLVE_ATTEMPT backend=lx " +
                    "sourceId=$sourceId quality=$quality"
            )
            try {
                return MusicSourceRuntimeClient.resolveLxAudio(context, source, item, quality)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                lastError = error
                AppLogger.w(
                    TAG,
                    "${OnlinePlaybackDiagnostics.PREFIX} RESOLVE_ATTEMPT_FAIL backend=lx " +
                        "sourceId=$sourceId quality=$quality error=${OnlinePlaybackDiagnostics.errorSummary(error)} " +
                        "elapsedMs=${SystemClock.elapsedRealtime() - startedAt}",
                    error,
                )
            }
        }
        try {
            return LxCatalogSearchService.resolveFallback(item, requestedQuality)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (fallbackError: Throwable) {
            val resolverMessage = lastError?.message.orEmpty()
            val fallbackMessage = fallbackError.message.orEmpty()
            throw IllegalStateException(
                listOf(resolverMessage, fallbackMessage)
                    .filter(String::isNotBlank)
                    .joinToString("；")
                    .ifBlank { "LX 解析源没有返回可用播放地址" },
                fallbackError,
            )
        }
    }
    private const val TAG = "MusicSourceResolver"
}
