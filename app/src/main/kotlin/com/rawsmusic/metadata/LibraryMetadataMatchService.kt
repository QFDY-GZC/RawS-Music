package com.rawsmusic.metadata

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.MediaScannerConnection
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.rawsmusic.R
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.LyricData
import com.rawsmusic.core.common.taglib.TagLibBridge
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.core.ui.widget.bitmaps.BitmapProvider
import com.rawsmusic.lyrico.LyricoCoverCandidate
import com.rawsmusic.lyrico.LyricoPreferredSource
import com.rawsmusic.lyrico.LyricoSourceEngine
import com.rawsmusic.module.data.repository.MusicRepository
import com.rawsmusic.module.scanner.LyricReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Eight-lane foreground metadata matcher for local songs. */
class LibraryMetadataMatchService : Service() {
    private val serviceJob = SupervisorJob()
    private val scope = CoroutineScope(serviceJob + Dispatchers.IO)
    private val workerDispatcher = Executors.newFixedThreadPool(WORKER_COUNT) { runnable ->
        Thread(runnable, "raws-metadata-match").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private var activeJob: Job? = null
    private var lastNotificationUptimeMs: Long = 0L
    private lateinit var engine: LyricoSourceEngine

    override fun onCreate() {
        super.onCreate()
        engine = LyricoSourceEngine(applicationContext)
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val jobId = intent?.getStringExtra(LibraryMetadataMatchContract.EXTRA_JOB_ID).orEmpty()
        val request = LibraryMetadataMatchContract.readRequest(this, jobId)
        if (request == null || request.songs.isEmpty()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (activeJob?.isActive == true) {
            AppLogger.w(TAG, "A metadata matching job is already running")
            LibraryMetadataMatchContract.deleteRequest(this, request.id)
            return START_NOT_STICKY
        }

        LibraryMetadataMatchProgressBus.started(request.id, request.songs.size)
        startForeground(NOTIFICATION_ID, notification("准备自动匹配…", 0, request.songs.size, indeterminate = true))
        activeJob = scope.launch {
            runRequest(request, startId)
        }
        return START_REDELIVER_INTENT
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        workerDispatcher.close()
        super.onDestroy()
    }

    private suspend fun runRequest(request: LibraryMetadataMatchRequest, startId: Int) {
        val processed = AtomicInteger(0)
        val matched = AtomicInteger(0)
        val unchanged = AtomicInteger(0)
        val skipped = AtomicInteger(0)
        val failed = AtomicInteger(0)
        val sources = engine.preferredSources()

        if (sources.isEmpty()) {
            finishNotification("没有启用的歌词音源", success = false)
            LibraryMetadataMatchProgressBus.completed(
                jobId = request.id,
                total = request.songs.size,
                succeeded = 0,
                failed = request.songs.size,
            )
            LibraryMetadataMatchContract.deleteRequest(this, request.id)
            stopSelf(startId)
            return
        }

        try {
            val nextSongIndex = AtomicInteger(0)
            supervisorScope {
                List(minOf(WORKER_COUNT, request.songs.size)) {
                    async(workerDispatcher) {
                        while (true) {
                            val songIndex = nextSongIndex.getAndIncrement()
                            if (songIndex >= request.songs.size) break
                            val song = request.songs[songIndex]
                            val outcome = runCatching { processSong(song, request.mode, sources) }
                                .getOrElse { error ->
                                    AppLogger.e(TAG, "Metadata matching failed: ${song.path}", error)
                                    MatchOutcome.FAILED
                                }
                            when (outcome) {
                                MatchOutcome.MATCHED -> matched.incrementAndGet()
                                MatchOutcome.UNCHANGED -> unchanged.incrementAndGet()
                                MatchOutcome.SKIPPED -> skipped.incrementAndGet()
                                MatchOutcome.FAILED -> failed.incrementAndGet()
                            }
                            val done = processed.incrementAndGet()
                            val successCount = matched.get() + unchanged.get()
                            val failureCount = skipped.get() + failed.get()
                            val progress = LibraryMetadataMatchProgressBus.running(
                                jobId = request.id,
                                total = request.songs.size,
                                processed = done,
                                succeeded = successCount,
                                failed = failureCount,
                            )
                            updateNotification(
                                progress.processed,
                                progress.total,
                                progress.succeeded,
                                progress.failed,
                            )
                        }
                    }
                }.awaitAll()
            }

            val successCount = matched.get() + unchanged.get()
            val failureCount = skipped.get() + failed.get()
            val summary = "总计 ${processed.get()} 首 · 成功 $successCount · 失败 $failureCount"
            finishNotification(summary, success = failureCount == 0)
            LibraryMetadataMatchProgressBus.completed(
                jobId = request.id,
                total = processed.get(),
                succeeded = successCount,
                failed = failureCount,
            )
        } finally {
            withContext(Dispatchers.IO) {
                runCatching { MusicRepository.refreshAllSuspend() }
                    .onFailure { AppLogger.e(TAG, "Final library refresh failed", it) }
                engine.clearTransientCache()
                cleanupTransientFiles()
            }
            BitmapProvider.notifyLibraryArtworkChanged("metadata_match_complete")
            LibraryMetadataMatchContract.deleteRequest(this, request.id)
            activeJob = null
            stopSelf(startId)
        }
    }

    private suspend fun processSong(
        song: AudioFile,
        mode: LibraryMetadataMatchMode,
        sources: List<LyricoPreferredSource>,
    ): MatchOutcome {
        if (song.path.isBlank() || (!song.path.startsWith("content://") && !File(song.path).isFile)) {
            return MatchOutcome.FAILED
        }

        val hasLyrics = runCatching { !LyricReader.readLyrics(song).isEmpty }.getOrDefault(false)
        val hasCover = hasArtwork(song)
        val needLyrics = when (mode) {
            LibraryMetadataMatchMode.LYRICS_ONLY -> true
            LibraryMetadataMatchMode.FILL_MISSING -> !hasLyrics
            LibraryMetadataMatchMode.MATCH_CURRENT,
            LibraryMetadataMatchMode.REMATCH_ALL -> true
        }
        val needCover = when (mode) {
            LibraryMetadataMatchMode.LYRICS_ONLY -> false
            LibraryMetadataMatchMode.FILL_MISSING -> !hasCover
            LibraryMetadataMatchMode.MATCH_CURRENT,
            LibraryMetadataMatchMode.REMATCH_ALL -> true
        }
        if (!needLyrics && !needCover) return MatchOutcome.UNCHANGED

        var lyric: LyricData? = null
        var cover: LyricoCoverCandidate? = null
        val query = listOf(song.title, song.artist).filter(String::isNotBlank).joinToString(" ")
            .ifBlank { song.displayName }

        sources.forEach { source ->
            if ((!needLyrics || lyric != null) && (!needCover || cover != null)) return@forEach
            val candidates = engine.matchingCandidates(
                song,
                engine.searchSource(song, source.id, query)
            ).take(MAX_CANDIDATES_PER_SOURCE)
            if (candidates.isEmpty()) return@forEach

            if (needCover && cover == null) {
                cover = runCatching {
                    engine.highestResolutionCover(
                        candidates.flatMap { candidate ->
                            runCatching { engine.searchCovers(candidate) }.getOrDefault(emptyList())
                        }
                    )
                }.getOrNull()
            }
            if (needLyrics && lyric == null) {
                for (candidate in candidates) {
                    val fetched = runCatching { engine.getLyrics(candidate) }
                        .getOrNull()
                        ?.takeUnless { it.isEmpty }
                    if (fetched != null) {
                        lyric = fetched
                        break
                    }
                }
            }
        }

        if (mode == LibraryMetadataMatchMode.REMATCH_ALL && (lyric == null || cover == null)) {
            // Full rematch is all-or-nothing at the lookup stage. A source chain that only
            // provides one component must not overwrite the existing complete metadata.
            return MatchOutcome.SKIPPED
        }
        if (needLyrics && lyric == null && needCover && cover == null) return MatchOutcome.SKIPPED
        if (needLyrics && lyric == null && !needCover) return MatchOutcome.SKIPPED
        if (needCover && cover == null && !needLyrics) return MatchOutcome.SKIPPED

        var writes = 0
        var writeFailed = false

        // Preserve source priority independently per component: a high-priority cover may be
        // paired with lyrics from the next source. Both lookups complete before REMATCH_ALL writes.
        if (needCover && cover != null) {
            runCatching { engine.writeEmbeddedCover(song, requireNotNull(cover).url) }
                .onSuccess { writes++ }
                .onFailure {
                    writeFailed = true
                    AppLogger.e(TAG, "Cover write failed: ${song.path}", it)
                }
        }
        if (needLyrics && lyric != null) {
            runCatching { engine.writeOverride(song, requireNotNull(lyric)) }
                .onSuccess { writes++ }
                .onFailure {
                    writeFailed = true
                    AppLogger.e(TAG, "Lyric write failed: ${song.path}", it)
                }
        }

        if (writes > 0) {
            runCatching {
                MediaScannerConnection.scanFile(this, arrayOf(song.path), null, null)
            }
        }
        return when {
            writes > 0 && !writeFailed -> MatchOutcome.MATCHED
            mode == LibraryMetadataMatchMode.MATCH_CURRENT && writes > 0 -> MatchOutcome.MATCHED
            else -> MatchOutcome.FAILED
        }
    }

    private fun hasArtwork(song: AudioFile): Boolean {
        if (song.albumArtPath.isNotBlank() && File(song.albumArtPath).isFile) return true
        if (!TagLibBridge.isLoaded() || !TagLibBridge.isSupported(song.path)) return false
        val probe = File(cacheDir, "metadata_cover_probe_${song.id}_${System.nanoTime()}.img")
        return try {
            TagLibBridge.extractEmbeddedArtworkToFile(song.path, probe.absolutePath) && probe.length() > 1_024L
        } catch (_: Throwable) {
            false
        } finally {
            probe.delete()
        }
    }

    private fun cleanupTransientFiles() {
        cacheDir.listFiles().orEmpty().forEach { file ->
            if (file.name.startsWith("metadata_cover_probe_") ||
                file.name.startsWith("lyrico_cover_work_") ||
                file.name.startsWith("lyrico_cover_verify_")
            ) {
                runCatching { if (file.isDirectory) file.deleteRecursively() else file.delete() }
            }
        }
    }

    @Synchronized
    private fun updateNotification(done: Int, total: Int, matched: Int, failed: Int) {
        val now = SystemClock.elapsedRealtime()
        if (done < total && now - lastNotificationUptimeMs < NOTIFICATION_UPDATE_INTERVAL_MS) return
        lastNotificationUptimeMs = now
        val text = "已处理 $done/$total · 写入 $matched${if (failed > 0) " · 失败 $failed" else ""}"
        notificationManager().notify(
            NOTIFICATION_ID,
            notification(text, done, total, indeterminate = false)
        )
    }

    private fun finishNotification(text: String, success: Boolean) {
        notificationManager().notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_music_note)
                .setContentTitle(if (success) "自动匹配完成" else "自动匹配结束")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build()
        )
    }

    private fun notification(text: String, progress: Int, total: Int, indeterminate: Boolean): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentTitle("RawSMusic 正在匹配歌词与专辑图")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(total.coerceAtLeast(1), progress.coerceAtLeast(0), indeterminate)
            .build()

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        notificationManager().createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "歌词与专辑图自动匹配",
                NotificationManager.IMPORTANCE_LOW,
            )
        )
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(NotificationManager::class.java)

    private enum class MatchOutcome { MATCHED, UNCHANGED, SKIPPED, FAILED }

    private companion object {
        const val TAG = "MetadataMatchService"
        const val CHANNEL_ID = "library_metadata_match"
        const val NOTIFICATION_ID = 0x524D
        const val WORKER_COUNT = 8
        const val MAX_CANDIDATES_PER_SOURCE = 4
        const val NOTIFICATION_UPDATE_INTERVAL_MS = 250L
    }
}
