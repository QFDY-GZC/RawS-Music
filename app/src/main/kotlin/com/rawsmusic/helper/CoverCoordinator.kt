package com.rawsmusic.helper

import android.content.Context
import androidx.lifecycle.LifecycleOwner
import com.rawsmusic.core.common.model.AudioFile

/**
 * 封面解析 + 当前封面同步协调器。
 *
 * 管理 CoverUriResolver、封面提取事件监听、当前歌曲封面同步。
 * 第一阶段只拆"封面解析 + 当前封面同步"，
 * 第二阶段再迁入 shared transition bounds。
 */
class CoverCoordinator(
    context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val getCurrentSong: () -> AudioFile?,
    private val onMiniPlayerCoverNeedRefresh: () -> Unit,
    private val onMirrorCoverChanged: (String?) -> Unit
) {
    val resolver = CoverUriResolver(context.applicationContext)

    /**
     * 解析歌曲封面 URI。
     */
    fun resolve(song: AudioFile): String {
        return resolver.resolveCoverUri(song).ifBlank { song.coverKey }
    }

    /**
     * 启动封面提取事件监听。
     */
    fun start() {
        resolver.coverExtractedEvent.observe(lifecycleOwner) { event ->
            val (songPath, coverUri) = event ?: return@observe
            if (coverUri.isBlank()) return@observe

            val current = getCurrentSong()
            if (current != null && current.path == songPath) {
                resolver.updateCache(current, coverUri)
                onMiniPlayerCoverNeedRefresh()
                onMirrorCoverChanged(resolve(current).takeIf { it.isNotBlank() })
            }
        }
    }

    /**
     * 切歌时调用：更新镜像封面。
     */
    fun onCurrentSongChanged(song: AudioFile) {
        val cover = resolve(song)
        onMirrorCoverChanged(cover.ifBlank { null })
    }
}
