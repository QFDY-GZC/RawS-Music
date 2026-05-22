package com.rawsmusic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.request.Options
import coil.size.Dimension
import coil.size.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 自定义 Coil Fetcher：从音频文件内嵌封面提取高清原图
 *
 * 当 URI 为 content://media/external/audio/albumart/{albumId} 时，
 * 不使用 MediaStore 提供的缩略图，而是直接用 MediaMetadataRetriever
 * 从音频文件中提取原始内嵌封面（可能是 3000x3000 或更高分辨率）。
 * 如果提取失败，则回退到原始 content URI（低分辨率缩略图）。
 */
class EmbeddedArtworkFetcher(
    private val context: Context,
    private val uri: Uri,
    private val options: Options
) : Fetcher {

    override suspend fun fetch(): FetchResult = withContext(Dispatchers.IO) {
        // 支持 song:// scheme：直接从歌曲文件提取封面
        if (uri.scheme == "song") {
            val songPath = uri.schemeSpecificPart
            if (songPath.isNotBlank()) {
                val result = tryExtractFromSongFile(songPath)
                if (result != null) return@withContext result
            }
        }

        // 尝试提取内嵌高清封面（专辑级）
        val embeddedResult = tryExtractEmbedded()
        if (embeddedResult != null) return@withContext embeddedResult

        // 回退：从 content URI 加载（MediaStore 缩略图）
        val bitmap = loadFromContentUri()
        if (bitmap != null) {
            return@withContext DrawableResult(
                drawable = BitmapDrawable(context.resources, bitmap),
                isSampled = true,
                dataSource = DataSource.DISK
            )
        }

        // 最终兜底：查找同目录下的 folder.jpg / cover.jpg
        val folderCover = tryFolderCover()
        if (folderCover != null) {
            return@withContext DrawableResult(
                drawable = BitmapDrawable(context.resources, folderCover),
                isSampled = true,
                dataSource = DataSource.DISK
            )
        }

        // 所有方法都失败
        throw IllegalStateException("Cannot load cover: $uri")
    }

    /**
     * 从特定歌曲文件提取内嵌封面
     */
    private fun tryExtractFromSongFile(songPath: String): FetchResult? {
        val ext = songPath.substringAfterLast(".", "").uppercase()
        val isWavLike = ext in listOf("WAV", "DSF", "DFF", "AIFF", "AIF")

        if (isWavLike) {
            // WAV/DSF/DFF/AIFF: 使用 FFmpeg 提取
            val bitmap = tryFfmpegCover(songPath)
            if (bitmap != null) {
                android.util.Log.d("CoverDebug", "tryExtractFromSongFile: FFmpeg extracted cover for $songPath")
                return DrawableResult(
                    drawable = BitmapDrawable(context.resources, bitmap),
                    isSampled = true,
                    dataSource = DataSource.DISK
                )
            }
        } else {
            // 其他格式: 使用 MediaMetadataRetriever
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(songPath)
                val bytes = retriever.embeddedPicture
                if (bytes != null && bytes.size > 1024) {
                    val targetSize = options.size
                    val bitmap = if (targetSize != Size.ORIGINAL) {
                        val w = (targetSize.width as? Dimension.Pixels)?.px ?: Int.MAX_VALUE
                        val h = (targetSize.height as? Dimension.Pixels)?.px ?: Int.MAX_VALUE
                        decodeSampledBitmap(bytes, w, h)
                    } else {
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }
                    if (bitmap != null) {
                        android.util.Log.d("CoverDebug", "tryExtractFromSongFile: extracted cover from $songPath")
                        return DrawableResult(
                            drawable = BitmapDrawable(context.resources, bitmap),
                            isSampled = true,
                            dataSource = DataSource.DISK
                        )
                    }
                }
            } catch (_: Exception) {} finally {
                try { retriever.release() } catch (_: Exception) {}
            }
        }
        return null
    }

    private fun tryExtractEmbedded(): FetchResult? {
        // 从 URI 提取 albumId: content://media/external/audio/albumart/{albumId}
        val albumId = uri.lastPathSegment?.toLongOrNull() ?: return null

        // 查询该专辑的音频文件路径
        val audioPaths = queryAudioPathsForAlbum(albumId)
        if (audioPaths.isEmpty()) return null

        for (audioPath in audioPaths) {
            val ext = audioPath.substringAfterLast(".", "").uppercase()

            // WAV 文件：使用 FFmpegKit 提取封面
            if (ext == "WAV" || ext == "DSF" || ext == "DFF" || ext == "AIFF") {
                val bitmap = tryFfmpegCover(audioPath)
                if (bitmap != null) {
                    android.util.Log.d("CoverDebug", "FFmpeg extracted cover for $ext file: ${bitmap.width}x${bitmap.height}")
                    return DrawableResult(
                        drawable = BitmapDrawable(context.resources, bitmap),
                        isSampled = true,
                        dataSource = DataSource.DISK
                    )
                }
                continue  // FFmpeg 没找到，尝试其他文件
            }

            // 其他格式：使用 MediaMetadataRetriever
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(audioPath)
                val bytes = retriever.embeddedPicture ?: continue

                val targetSize = options.size
                val bitmap = if (targetSize != Size.ORIGINAL) {
                    val w = (targetSize.width as? Dimension.Pixels)?.px ?: Int.MAX_VALUE
                    val h = (targetSize.height as? Dimension.Pixels)?.px ?: Int.MAX_VALUE
                    decodeSampledBitmap(bytes, w, h)
                } else {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                } ?: continue

                return DrawableResult(
                    drawable = BitmapDrawable(context.resources, bitmap),
                    isSampled = true,
                    dataSource = DataSource.DISK
                )
            } catch (_: Exception) { continue }
            finally { try { retriever.release() } catch (_: Exception) {} }
        }
        return null
    }

    private fun tryFfmpegCover(audioPath: String): Bitmap? {
        return try {
            val coverFile = java.io.File(context.cacheDir, "albumart/cover_${audioPath.hashCode()}.jpg")
            val coverDir = coverFile.parentFile
            if (coverDir != null && !coverDir.exists()) coverDir.mkdirs()

            // 检查缓存（与 resolveCoverUri 共享缓存文件）
            if (coverFile.exists() && coverFile.length() > 1024) {
                val bitmap = BitmapFactory.decodeFile(coverFile.absolutePath)
                if (bitmap != null) {
                    android.util.Log.d("CoverDebug", "tryFfmpegCover: cache hit ${coverFile.length()} bytes")
                    return bitmap
                }
            }

            android.util.Log.d("CoverDebug", "tryFfmpegCover: extracting with FFmpegBridge from $audioPath")
            val ret = com.rawsmusic.core.common.ffmpeg.FFmpegBridge.extractCover(audioPath, coverFile.absolutePath)
            if (ret == 0 && coverFile.exists() && coverFile.length() > 1024) {
                android.util.Log.d("CoverDebug", "tryFfmpegCover: extracted cover ${coverFile.length()} bytes")
                BitmapFactory.decodeFile(coverFile.absolutePath)
                // 不删除缓存文件，保留给 resolveCoverUri 后续使用
            } else {
                android.util.Log.d("CoverDebug", "tryFfmpegCover: no cover found in $audioPath")
                if (coverFile.exists()) coverFile.delete()
                null
            }
        } catch (e: Exception) {
            android.util.Log.e("CoverDebug", "tryFfmpegCover: failed", e)
            null
        }
    }

    /**
     * 降采样解码 ByteArray 中的图片，避免加载过大 Bitmap
     */
    private fun decodeSampledBitmap(bytes: ByteArray, reqWidth: Int, reqHeight: Int): Bitmap? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)

        val sampleSize = calculateSampleSize(options.outWidth, options.outHeight, reqWidth, reqHeight)
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
    }

    private fun calculateSampleSize(width: Int, height: Int, reqWidth: Int, reqHeight: Int): Int {
        if (reqWidth <= 0 || reqHeight <= 0) return 1
        var sampleSize = 1
        if (width > reqWidth || height > reqHeight) {
            val halfW = width / 2
            val halfH = height / 2
            while (halfW / sampleSize >= reqWidth && halfH / sampleSize >= reqHeight) {
                sampleSize *= 2
            }
        }
        return sampleSize
    }

    private fun loadFromContentUri(): Bitmap? {
        try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val bytes = inputStream.readBytes()
            inputStream.close()

            val targetSize = options.size
            return if (targetSize != Size.ORIGINAL) {
                val w = (targetSize.width as? Dimension.Pixels)?.px ?: Int.MAX_VALUE
                val h = (targetSize.height as? Dimension.Pixels)?.px ?: Int.MAX_VALUE
                decodeSampledBitmap(bytes, w, h)
            } else {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        } catch (_: Exception) {
            return null
        }
    }

    private fun queryAudioPathsForAlbum(albumId: Long?): List<String> {
        if (albumId == null || albumId <= 0) return emptyList()
        val projection = arrayOf(MediaStore.Audio.Media.DATA)
        val selection = "${MediaStore.Audio.Media.ALBUM_ID} = ?"
        val selectionArgs = arrayOf(albumId.toString())

        val paths = mutableListOf<String>()
        try {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection, selection, selectionArgs, null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val p = cursor.getString(0)
                    if (p != null) paths.add(p)
                }
            }
        } catch (_: Exception) {}
        return paths
    }

    /**
     * 查找音频文件所在目录的 folder.jpg / cover.jpg
     */
    private fun tryFolderCover(): Bitmap? {
        val albumId = uri.lastPathSegment?.toLongOrNull() ?: return null
        val audioPaths = queryAudioPathsForAlbum(albumId)
        if (audioPaths.isEmpty()) return null

        // 取第一个文件的目录
        val dir = java.io.File(audioPaths.first()).parentFile ?: return null
        android.util.Log.d("CoverDebug", "tryFolderCover: searching in $dir")

        // 常见文件夹封面文件名
        val candidates = listOf("folder.jpg", "Folder.jpg", "cover.jpg", "Cover.jpg", "album.jpg", "Album.jpg")
        for (name in candidates) {
            val file = java.io.File(dir, name)
            if (file.exists() && file.length() > 1024) {  // 至少 1KB 才是有效图片
                android.util.Log.d("CoverDebug", "tryFolderCover: found ${file.absolutePath}, size=${file.length()}")
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                if (bitmap != null) {
                    android.util.Log.d("CoverDebug", "tryFolderCover: bitmap decoded, size=${bitmap.width}x${bitmap.height}")
                    return bitmap
                }
            }
        }
        android.util.Log.d("CoverDebug", "tryFolderCover: no folder cover found")
        return null
    }

    class Factory(private val context: Context) : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: coil.ImageLoader): Fetcher? {
            // 拦截 song:// scheme（歌曲文件封面提取）
            if (data.scheme == "song") {
                return EmbeddedArtworkFetcher(context.applicationContext, data, options)
            }
            // 拦截 albumart content URI（专辑级封面提取）
            if (data.scheme == "content" && data.toString().contains("albumart")) {
                return EmbeddedArtworkFetcher(context.applicationContext, data, options)
            }
            return null
        }
    }
}
