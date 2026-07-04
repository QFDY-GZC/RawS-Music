package com.rawsmusic.helper

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.utils.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 封面 key 解析器。
 *
 * 修复点：
 * 1. 不再强制先异步提取到 file://cache；优先返回稳定 key，让 BitmapProvider 统一解码。
 * 2. 同一路径文件变更后，缓存 key 会变化，避免旧封面残留。
 * 3. inFlight finally remove，失败后后续可重试。
 */
class CoverUriResolver(
    private val context: Context
) {

    private val coverKeyCache = ConcurrentHashMap<String, String>()
    private val extractionInFlight = ConcurrentHashMap.newKeySet<String>()

    private val _coverExtractedEvent = MutableLiveData<Pair<String, String>?>()
    val coverExtractedEvent: LiveData<Pair<String, String>?> get() = _coverExtractedEvent

    /**
     * 返回给 BitmapProvider 使用的 key。
     *
     * 优先级：
     * 1. albumArtPath = file:// 且存在
     * 2. albumArtPath = content://
     * 3. 歌曲真实路径，让 BitmapProvider 解内嵌封面 / folder.jpg / FFmpeg fallback
     */
    fun resolveCoverUri(song: AudioFile): String {
        val cacheKey = stableSongCacheKey(song)

        coverKeyCache[cacheKey]?.let { cached ->
            if (cached.isNotBlank()) return cached
        }

        val albumArtPath = song.albumArtPath.trim()
        val ext = song.path.substringAfterLast('.', "").lowercase()
        val preferEmbedded = ext in setOf("dsf", "dff", "wav", "aiff", "aif", "ape")

        if (preferEmbedded && song.path.isNotBlank()) {
            val stableKey = "audio://${song.path}|${song.fileSize}|${song.dateModified}"
            coverKeyCache[cacheKey] = stableKey
            return stableKey
        }

        if (albumArtPath.startsWith("file://")) {
            val filePath = albumArtPath.removePrefix("file://")
            if (File(filePath).exists()) {
                coverKeyCache[cacheKey] = albumArtPath
                return albumArtPath
            }
        }

        if (albumArtPath.startsWith("content://")) {
            coverKeyCache[cacheKey] = albumArtPath
            return albumArtPath
        }

        if (song.path.isNotBlank()) {
            val stableKey = "audio://${song.path}|${song.fileSize}|${song.dateModified}"
            coverKeyCache[cacheKey] = stableKey
            return stableKey
        }

        return ""
    }

    fun updateCache(
        song: AudioFile,
        coverUri: String
    ) {
        coverKeyCache[stableSongCacheKey(song)] = coverUri
    }

    @Deprecated("Use updateCache(song, coverUri), path key is unstable", replaceWith = ReplaceWith("updateCache(song, coverUri)"))
    fun updateCache(
        songPath: String,
        coverUri: String
    ) {
        if (songPath.isBlank()) return
        coverKeyCache[songPath] = coverUri
    }

    fun invalidate(song: AudioFile) {
        coverKeyCache.remove(stableSongCacheKey(song))
    }

    fun clear() {
        coverKeyCache.clear()
        extractionInFlight.clear()
    }

    /**
     * 兼容旧链路：需要时可以后台提取成 cache jpg。
     * 新 UI 推荐直接用 resolveCoverUri(song) 返回的 key 交给 BitmapProvider。
     */
    fun tryAsyncExtractCover(song: AudioFile) {
        val stableKey = stableSongCacheKey(song)

        if (!extractionInFlight.add(stableKey)) return

        CoroutineScope(Dispatchers.IO).launch {
            val result = try {
                extractCoverToCacheFile(song)
            } catch (e: Exception) {
                AppLogger.w("CoverUriResolver", "extract failed: ${song.path}", e)
                null
            } finally {
                extractionInFlight.remove(stableKey)
            }

            if (!result.isNullOrBlank()) {
                coverKeyCache[stableKey] = result
            }

            withContext(Dispatchers.Main) {
                _coverExtractedEvent.value = song.path to (result ?: resolveCoverUri(song))
            }
        }
    }

    private fun extractCoverToCacheFile(song: AudioFile): String? {
        val path = song.path
        if (path.isBlank()) return null

        val sourceFile = File(path)
        if (!sourceFile.exists() || !sourceFile.canRead()) return null

        val cacheName = "song_${path.hashCode()}_${sourceFile.length()}_${sourceFile.lastModified()}.jpg"
        val coverFile = File(context.cacheDir, "albumart/$cacheName")
        coverFile.parentFile?.mkdirs()

        if (coverFile.exists() && coverFile.length() > 1024) {
            return "file://${coverFile.absolutePath}"
        }

        val ext = path.substringAfterLast(".", "").lowercase()
        val useFfmpeg = ext in setOf(
            "wav",
            "dsf",
            "dff",
            "aiff",
            "aif",
            "ape"
        )

        if (useFfmpeg) {
            val ret = com.rawsmusic.core.common.ffmpeg.FFmpegBridge.extractCover(
                path,
                coverFile.absolutePath
            )

            if (ret == 0 && coverFile.exists() && coverFile.length() > 1024) {
                return "file://${coverFile.absolutePath}"
            }

            coverFile.delete()
            return null
        }

        val retriever = android.media.MediaMetadataRetriever()

        try {
            retriever.setDataSource(path)
            val art = retriever.embeddedPicture

            if (art != null && art.size > 1024) {
                coverFile.writeBytes(art)
                return "file://${coverFile.absolutePath}"
            }
        } catch (e: Exception) {
            AppLogger.w("CoverUriResolver", "MMR extract failed: $path", e)
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }

        coverFile.delete()
        return null
    }

    private fun stableSongCacheKey(song: AudioFile): String {
        val path = song.path
        val file = if (path.isNotBlank()) File(path) else null

        val size = when {
            song.fileSize > 0L -> song.fileSize
            file != null && file.exists() -> file.length()
            else -> 0L
        }

        val modified = when {
            song.dateModified > 0L -> song.dateModified
            file != null && file.exists() -> file.lastModified()
            else -> 0L
        }

        return "$path|$size|$modified|${song.albumArtPath}"
    }
}
