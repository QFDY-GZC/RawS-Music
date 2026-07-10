package com.rawsmusic.module.scanner

import android.content.Context
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.data.repository.MusicRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

object ScanDispatcher {

    private const val TAG = "ScanDispatcher"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var currentJob: Job? = null

    fun isScanning(): Boolean = currentJob?.isActive == true

    fun cancelCurrentScan() {
        val job = currentJob ?: return
        AppLogger.d(TAG, "cancelCurrentScan")
        job.cancel(CancellationException("Scan cancelled"))
        ScanStateBus.notifyError("扫描已取消")
    }

    fun dispatchDirScan(context: Context, fastScan: Boolean, onComplete: () -> Unit) {
        if (isScanning()) { AppLogger.d(TAG, "ignored: already scanning"); onComplete(); return }

        val appContext = context.applicationContext
        val job = scope.launch {
            try {
                val repository = LibraryScannerDependencies.repositoryOrNull(appContext)
                if (repository != null) {
                    dispatchUnifiedLibraryScan(appContext, repository, fastScan)
                } else {
                    dispatchLegacyDirScan(appContext, fastScan)
                }
            } catch (_: CancellationException) {
                AppLogger.d(TAG, "dir scan cancelled")
                ScanStateBus.notifyError("扫描已取消")
            } catch (e: Exception) {
                AppLogger.e(TAG, "dir scan exception", e)
                ScanStateBus.notifyError(e.message ?: "扫描失败")
            } finally {
                currentJob = null
                onComplete()
            }
        }
        currentJob = job
    }

    fun dispatchTagScan(context: Context, onComplete: () -> Unit) {
        if (isScanning()) { AppLogger.d(TAG, "tagScan ignored"); onComplete(); return }

        val job = scope.launch {
            try {
                val t0 = System.currentTimeMillis()
                val songs = MusicRepository.getAllSongs(); val total = songs.size
                var processed = 0; var updated = 0

                AppLogger.d(TAG, "tagScan: total=$total")
                ScanStateBus.notifyTagScanStarted(total)

                songs.chunked(50).forEach { batch ->
                    coroutineContext.ensureActive()
                    batch.forEach { song ->
                        coroutineContext.ensureActive()
                        try {
                            val enriched = MediaStoreScanner.enrichSong(song)
                            if (enriched != song) { MusicRepository.updateSong(enriched); updated++ }
                        } catch (_: Exception) {}
                        processed++
                    }
                    ScanStateBus.notifyScanning(processed, total, "读取标签 $processed/$total")
                }

                ScanStateBus.notifyCompleted(updated, System.currentTimeMillis() - t0)
                AppLogger.d(TAG, "tagScan done: updated=$updated")
            } catch (_: CancellationException) {
                AppLogger.d(TAG, "tagScan cancelled"); ScanStateBus.notifyError("扫描已取消")
            } catch (e: Exception) {
                AppLogger.e(TAG, "tagScan error", e); ScanStateBus.notifyError(e.message ?: "失败")
            } finally {
                currentJob = null; onComplete()
            }
        }
        currentJob = job
    }

    fun dispatchIncrementalScan(context: Context, onComplete: () -> Unit) {
        dispatchDirScan(context, fastScan = true, onComplete = onComplete)
    }

    private suspend fun dispatchUnifiedLibraryScan(
        context: Context,
        repository: AudioLibraryRepository,
        fastScan: Boolean
    ) {
        val startTime = System.currentTimeMillis()
        val customPaths = AppPreferences.UI.scanPaths.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val coordinator = LibraryScanCoordinator(repository)

        AppLogger.d(TAG, "dispatchUnifiedLibraryScan: fast=$fastScan, paths=${customPaths.size}")
        ScanStateBus.notifyDirScanStarted()

        val options = TwoStageMediaScanner.Options(
            scannerOptions = MediaStoreScanner.ScanOptions.fromPreferences(),
            customPaths = customPaths,
            expandCueTracks = true,
            emitEachSong = false,
            usePersistentCache = !fastScan,
            saveCacheAtEnd = !fastScan
        )

        coordinator.scanAndSync(context, options).collect { event ->
            coroutineContext.ensureActive()
            when (event) {
                is LibraryScanCoordinator.Event.ScannerEvent -> notifyFromTwoStageEvent(event.event)
                is LibraryScanCoordinator.Event.DatabaseSyncStarted -> {
                    val message = when (event.phase) {
                        LibraryScanCoordinator.SyncPhase.QUICK_VISIBLE -> "快速结果写入数据库：${event.newCount} 首"
                        LibraryScanCoordinator.SyncPhase.ENRICHED_BATCH -> "后台补全写入数据库：${event.newCount} 首"
                        LibraryScanCoordinator.SyncPhase.FINAL -> "最终同步数据库：${event.newCount} 首"
                    }
                    ScanStateBus.notifyScanning(0, event.newCount, message)
                }
                is LibraryScanCoordinator.Event.DatabaseSyncCompleted -> {
                    val message = when (event.phase) {
                        LibraryScanCoordinator.SyncPhase.QUICK_VISIBLE -> "快速结果已显示：新增/变更 ${event.upserted} 首"
                        LibraryScanCoordinator.SyncPhase.ENRICHED_BATCH -> "后台补全已写入：${event.upserted} 首"
                        LibraryScanCoordinator.SyncPhase.FINAL -> "数据库同步完成：更新 ${event.upserted}，删除 ${event.deleted}，未变 ${event.unchanged}"
                    }
                    ScanStateBus.notifyScanning(
                        event.upserted + event.deleted,
                        event.upserted + event.deleted + event.unchanged,
                        message
                    )
                }
                is LibraryScanCoordinator.Event.VisibleCompleted -> {
                    ScanStateBus.notifyCompleted(event.found, event.timeMs)
                    AppLogger.d(TAG, "quick visible done: found=${event.found}, elapsed=${event.timeMs}ms; enrichment continues")
                }
                is LibraryScanCoordinator.Event.Completed -> {
                    ScanStateBus.notifyCompleted(event.songs.size, System.currentTimeMillis() - startTime)
                    AppLogger.d(TAG, "unified scan done: found=${event.songs.size}, elapsed=${event.timeMs}ms")
                }
                is LibraryScanCoordinator.Event.Error -> {
                    ScanStateBus.notifyError(event.message)
                    throw IllegalStateException(event.message)
                }
            }
        }
    }

    private fun notifyFromTwoStageEvent(event: TwoStageMediaScanner.Event) {
        when (event) {
            is TwoStageMediaScanner.Event.Started -> ScanStateBus.notifyScanning(0, event.totalEstimated, "快速扫描：准备")
            is TwoStageMediaScanner.Event.CacheLoaded -> ScanStateBus.notifyScanning(0, event.cachedCount, "读取元数据缓存：${event.cachedCount} 条")
            is TwoStageMediaScanner.Event.QuickProgress -> ScanStateBus.notifyScanning(event.scanned, event.total, "快速扫描：${event.scanned}/${event.total}")
            is TwoStageMediaScanner.Event.QuickCompleted -> ScanStateBus.notifyScanning(event.found, event.found, "快速扫描完成：${event.found} 首")
            is TwoStageMediaScanner.Event.EnrichProgress -> ScanStateBus.notifyScanning(event.processed, event.total, "补全音频信息：${event.processed}/${event.total}，缓存 ${event.cacheHits}")
            is TwoStageMediaScanner.Event.SongEnriched -> Unit
            is TwoStageMediaScanner.Event.EnrichBatchCompleted -> Unit
            is TwoStageMediaScanner.Event.FullyCompleted -> ScanStateBus.notifyScanning(event.found, event.found, "准备同步数据库：${event.found} 首")
            is TwoStageMediaScanner.Event.Error -> ScanStateBus.notifyError(event.message)
        }
    }

    private suspend fun dispatchLegacyDirScan(context: Context, fastScan: Boolean) {
        val startTime = System.currentTimeMillis()
        val customPaths = AppPreferences.UI.scanPaths.map { it.trim() }.filter { it.isNotBlank() }.distinct()

        AppLogger.d(TAG, "dispatchLegacyDirScan: fast=$fastScan, paths=${customPaths.size}")

        if (customPaths.isEmpty() && MusicRepository.getAllSongs().isEmpty()) {
            ScanStateBus.notifyFolderSelectionNeeded()
            return
        }

        ScanStateBus.notifyDirScanStarted()

        if (fastScan) {
            runScanPhase(context, customPaths, quickScan = true, "快速扫描", notifyFinal = true, startTime)
        } else {
            val qf = runScanPhase(context, customPaths, quickScan = true, "快速扫描", notifyFinal = false, startTime)
            coroutineContext.ensureActive()
            ScanStateBus.notifyScanning(qf, qf, "快速扫描完成，正在读取详细标签")
            runScanPhase(context, customPaths, quickScan = false, "详细扫描", notifyFinal = true, startTime)
        }
    }

    private suspend fun runScanPhase(
        context: Context, paths: List<String>, quickScan: Boolean,
        phaseName: String, notifyFinal: Boolean, globalStart: Long
    ): Int {
        var found = 0
        AppLogger.d(TAG, "runScanPhase: $phaseName, quick=$quickScan")

        ScanManager.startScan(context, paths, useMediaStore = !AppPreferences.Scanner.legacyFileAccessEnabled, quickScan = quickScan).collect { p ->
            coroutineContext.ensureActive()
            when (p) {
                is ScanProgress.Started -> ScanStateBus.notifyScanning(0, p.totalEstimated, "$phaseName：准备")
                is ScanProgress.Progress -> ScanStateBus.notifyScanning(p.scanned, p.total, "$phaseName：${p.scanned}/${p.total}")
                is ScanProgress.Completed -> {
                    found = p.found
                    val elapsed = System.currentTimeMillis() - globalStart
                    if (notifyFinal) ScanStateBus.notifyCompleted(p.found, elapsed)
                    else ScanStateBus.notifyScanning(p.found, p.found, "$phaseName 完成：${p.found} 首")
                    AppLogger.d(TAG, "$phaseName done: found=${p.found}, elapsed=${elapsed}ms, final=$notifyFinal")
                }
                is ScanProgress.Error -> { ScanStateBus.notifyError(p.message); throw IllegalStateException(p.message) }
            }
        }
        return found
    }
}
