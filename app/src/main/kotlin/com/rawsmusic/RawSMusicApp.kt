package com.rawsmusic

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.rawsmusic.core.common.CoreInit
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.module.data.DataModule
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.data.prefs.FontManager
import com.rawsmusic.module.data.repository.MusicRepository

class RawSMusicApp : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            AppLogger.e("Crash", "${thread.name}: ${throwable.message}", throwable)
            previousHandler?.uncaughtException(thread, throwable)
        }
        CoreInit.init(this)
        DataModule.init(this)
        AppLogger.init()

        // 版本更新检查：覆盖安装后清除旧歌曲数据，强制重新扫描以获取完整元数据
        val currentVersion = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (_: Exception) { "" }
        if (currentVersion.isNotBlank() && AppPreferences.UI.appVersion != currentVersion) {
            AppPreferences.UI.appVersion = currentVersion
            MusicRepository.clearAll()
        }

        // 启动时清理旧版遗留的缓存文件
        cleanupLegacyCache()
    }

    /**
     * 清理缓存文件，防止无限增长：
     * - 根目录下的 resampled_*.pcm（旧版时间戳命名）
     * - ffmpeg_audio 目录超过 500MB 时清理最旧文件
     * - resampled_pcm 目录超过 500MB 时清理最旧文件
     * - albumart 目录超过 200MB 时清理最旧文件
     */
    private fun cleanupLegacyCache() {
        try {
            // 1. 清除根目录下旧版 resampled_*.pcm 文件
            cacheDir.listFiles()?.filter {
                it.isFile && it.name.startsWith("resampled_") && it.name.endsWith(".pcm")
            }?.forEach { it.delete() }

            // 2. 限制 ffmpeg_audio 目录大小（500MB）
            trimCacheDir(java.io.File(cacheDir, "ffmpeg_audio"), 500L * 1024 * 1024)

            // 3. 限制 resampled_pcm 目录大小（500MB）
            trimCacheDir(java.io.File(cacheDir, "resampled_pcm"), 500L * 1024 * 1024)

            // 4. 限制 albumart 目录大小（200MB）
            trimCacheDir(java.io.File(cacheDir, "albumart"), 200L * 1024 * 1024)
        } catch (_: Exception) {}
    }

    /**
     * 清理指定目录，使其总大小不超过 maxSize 字节。
     * 按最后修改时间排序，删除最旧的文件。
     */
    private fun trimCacheDir(dir: java.io.File, maxSize: Long) {
        if (!dir.exists()) return
        val files = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: return
        var totalSize = files.sumOf { it.length() }
        for (file in files) {
            if (totalSize <= maxSize) break
            totalSize -= file.length()
            file.delete()
        }
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                add(EmbeddedArtworkFetcher.Factory(this@RawSMusicApp))
            }
            .crossfade(true)
            .build()
    }
}
