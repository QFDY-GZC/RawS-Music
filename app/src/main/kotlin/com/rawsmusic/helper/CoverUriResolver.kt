package com.rawsmusic.helper

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.rawsmusic.core.common.artwork.EmbeddedArtworkRegion
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.taglib.TagLibBridge
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.core.ui.widget.bitmaps.BitmapProvider
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
     * 2. 歌曲真实路径，让 BitmapProvider 解内嵌封面 / folder.jpg / FFmpeg fallback
     * 3. albumArtPath = content:// 仅作为无真实路径时的兜底
     *
     * MIUI / MediaStore albumart content URI often returns a tiny provider thumbnail first and may
     * make the high-res path go through an extra album-id query. For the player surface we want the
     * stable audio-file key so all 192/512/1024 requests share the same embedded/folder-cover source.
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

        if (song.path.isNotBlank()) {
            val stableKey = "audio://${song.path}|${song.fileSize}|${song.dateModified}"
            coverKeyCache[cacheKey] = stableKey
            return stableKey
        }

        if (albumArtPath.startsWith("content://")) {
            // 不信任 MediaStore albumart URI：这类 provider 往往先返回小缩略图，
            // 会绕过稳定 audio source key / native region / source cache 生命周期。
            if (albumArtPath.contains("albumart", ignoreCase = true)) {
                AppLogger.d("CoverUriResolver", "Skipping MediaStore albumart URI: $albumArtPath")
            } else {
                coverKeyCache[cacheKey] = albumArtPath
                return albumArtPath
            }
        }

        return ""
    }

    fun updateCache(
        song: AudioFile,
        coverUri: String
    ) {
        val stableKey = stableSongCacheKey(song)
        val previous = coverKeyCache.put(stableKey, coverUri)
        if (previous != coverUri) {
            BitmapProvider.invalidateArtworkForSong(
                path = song.path,
                fileSize = song.fileSize,
                dateModified = song.dateModified,
                albumArtPath = coverUri,
                reason = "cover_resolver_update"
            )
        }
    }

    @Deprecated("Use updateCache(song, coverUri), path key is unstable", replaceWith = ReplaceWith("updateCache(song, coverUri)"))
    fun updateCache(
        songPath: String,
        song: AudioFile,
        coverUri: String
    ) {
        if (songPath.isBlank()) return
        updateCache(song, coverUri)
    }

    @Deprecated("Use updateCache(song, coverUri), path key is unstable", replaceWith = ReplaceWith("updateCache(song, coverUri)"))
    fun updateCache(
        songPath: String,
        coverUri: String
    ) {
        if (songPath.isBlank()) return
        coverKeyCache[songPath] = coverUri
    }

    @Deprecated("Use invalidate(song)", replaceWith = ReplaceWith("invalidate(song)"))
    fun invalidate(songPath: String, song: AudioFile) {
        if (songPath.isBlank()) return
        invalidate(song)
    }

    fun clear() {
        coverKeyCache.clear()
        extractionInFlight.clear()
    }

    fun invalidate(song: AudioFile) {
        coverKeyCache.remove(stableSongCacheKey(song))
        BitmapProvider.invalidateArtworkForSong(
            path = song.path,
            fileSize = song.fileSize,
            dateModified = song.dateModified,
            albumArtPath = song.albumArtPath,
            reason = "cover_resolver_invalidate"
        )
    }

    /**
     * 异步提取内嵌封面到缓存文件，完成后通过 coverExtractedEvent 通知。
     */
    fun requestExtraction(song: AudioFile) {
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
                if (!result.isNullOrBlank()) {
                    BitmapProvider.invalidateArtworkForSong(
                        path = song.path,
                        fileSize = song.fileSize,
                        dateModified = song.dateModified,
                        albumArtPath = result,
                        reason = "cover_async_extract_done"
                    )
                }
                _coverExtractedEvent.value = song.path to (result ?: resolveCoverUri(song))
            }
        }
    }

    private fun extractCoverToCacheFile(song: AudioFile): String? {
        val path = song.path
        val sourceFile = File(path)
        val cacheName = "song_${path.hashCode()}_${sourceFile.length()}_${sourceFile.lastModified()}.jpg"
        val coverFile = File(context.cacheDir, "albumart/$cacheName")
        coverFile.parentFile?.mkdirs()

        if (coverFile.exists() && coverFile.length() > 1024) {
            return "file://${coverFile.absolutePath}"
        }

        val ext = path.substringAfterLast(".", "").lowercase()
        val useFfmpeg = ext in setOf("wav", "dsf", "dff", "aiff", "aif")

        EmbeddedArtworkRegion.find(path)?.let { region ->
            val regionTmp = File(coverFile.parentFile, "${coverFile.name}.region.tmp")
            if (regionTmp.exists()) regionTmp.delete()
            try {
                region.openStream().use { input ->
                    regionTmp.outputStream().use { output -> input.copyTo(output) }
                }
                if (regionTmp.exists() && regionTmp.length() > 1024) {
                    if (coverFile.exists()) coverFile.delete()
                    if (regionTmp.renameTo(coverFile)) {
                        return "file://${coverFile.absolutePath}"
                    }
                }
            } catch (e: Exception) {
                AppLogger.d("CoverUriResolver", "region cover copy failed: ${path.takeLast(80)} ${e.message}")
            } finally {
                if (regionTmp.exists()) regionTmp.delete()
            }
        }

        if (TagLibBridge.isLoaded()) {
            val nativeTmp = File(coverFile.parentFile, "${coverFile.name}.taglib.tmp")
            if (nativeTmp.exists()) nativeTmp.delete()
            val ok = TagLibBridge.extractEmbeddedArtworkToFile(path, nativeTmp.absolutePath)
            if (ok && nativeTmp.exists() && nativeTmp.length() > 1024) {
                if (coverFile.exists()) coverFile.delete()
                if (nativeTmp.renameTo(coverFile)) {
                    return "file://${coverFile.absolutePath}"
                }
            }
            nativeTmp.delete()
        }

        if (useFfmpeg) {
            val ret = com.rawsmusic.core.common.ffmpeg.FFmpegBridge.extractCover(path, coverFile.absolutePath)
            if (ret == 0 && coverFile.exists() && coverFile.length() > 1024) {
                return "file://${coverFile.absolutePath}"
            }
            coverFile.delete()
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
