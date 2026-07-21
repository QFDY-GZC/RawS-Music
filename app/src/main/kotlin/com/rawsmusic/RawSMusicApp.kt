package com.rawsmusic

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.rawsmusic.core.common.CoreInit
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.core.ui.theme.ThemeManager
import com.rawsmusic.module.data.DataModule
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.player.PlayerService
import com.rawsmusic.module.scanner.LibraryScannerDependencies
import com.rawsmusic.module.scanner.MusicRepositoryAudioLibraryRepository
import com.rawsmusic.memory.FairRuntimeMemoryManager
import com.rawsmusic.ui.songs.PlayerHolder

class RawSMusicApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // 进程启动追踪：检测是否被系统杀死后重建
        val pid = android.os.Process.myPid()
        val bootElapsed = android.os.SystemClock.elapsedRealtime()
        AppLogger.w("ProcessBootTracker", "PROCESS_BOOT pid=$pid elapsed=${bootElapsed}ms")

        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            AppLogger.e("Crash", "${thread.name}: ${throwable.message}", throwable)
            previousHandler?.uncaughtException(thread, throwable)
        }
        CoreInit.init(this)
        DataModule.init(this)
        LibraryScannerDependencies.install { MusicRepositoryAudioLibraryRepository() }
        AppLogger.init()
        ThemeManager.applyStoredTheme()

        PlayerService.ensureRuntimeService(
            this,
            "app_process_create_bootstrap"
        )

        // 只启动后台封面线程，保持首屏封面请求可用；重型解码仍在 BitmapProvider worker 中执行。
        com.rawsmusic.core.ui.widget.bitmaps.BitmapProvider.init(this)
        FairRuntimeMemoryManager.initialize(this)

        // 版本号只用于记录覆盖安装，不再按 appVersion 清空曲库。
        // 数据结构变化交给 Room Migration，避免升级后丢失曲库、收藏和播放统计。
        val currentVersion = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (_: Exception) { "" }
        if (currentVersion.isNotBlank() && AppPreferences.UI.appVersion != currentVersion) {
            AppPreferences.UI.appVersion = currentVersion
        }

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                val handled = PlayerService.dispatchAppProcessForeground(
                    this@RawSMusicApp,
                    "process_lifecycle_on_start"
                )
                if (!handled) {
                    (PlayerService.currentRuntimeController() ?: PlayerHolder.controller)
                        ?.onAppForegroundResumed()
                }
            }

            override fun onStop(owner: LifecycleOwner) {
                val handled = PlayerService.dispatchAppProcessBackground(
                    this@RawSMusicApp,
                    "process_lifecycle_on_stop"
                )
                if (!handled) {
                    (PlayerService.currentRuntimeController() ?: PlayerHolder.controller)
                        ?.onAppWentBackground()
                }
            }
        })

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityPaused(activity: Activity) {
                val handled = PlayerService.dispatchActivityPaused(
                    this@RawSMusicApp,
                    "activity_paused:${activity.javaClass.simpleName}"
                )
                if (!handled) {
                    (PlayerService.currentRuntimeController() ?: PlayerHolder.controller)
                        ?.onAppMaybeLeavingForeground()
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })

        scheduleDeferredProcessInit()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        FairRuntimeMemoryManager.onAndroidTrimMemory(level)
    }

    private fun scheduleDeferredProcessInit() {
        Thread {
            cleanupLegacyCache()
        }.start()
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

}
