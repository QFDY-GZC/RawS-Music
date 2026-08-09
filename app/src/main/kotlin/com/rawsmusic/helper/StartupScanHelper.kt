package com.rawsmusic.helper

import android.content.Context
import android.widget.Toast
import com.rawsmusic.R
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.module.data.repository.MusicRepository
import com.rawsmusic.module.scanner.AudioFileListUpdater
import com.rawsmusic.module.scanner.AudioLibraryRepository
import com.rawsmusic.module.scanner.LibraryScanCoordinator
import com.rawsmusic.module.scanner.LibraryScannerDependencies
import com.rawsmusic.module.scanner.MediaStoreChangeObserver
import com.rawsmusic.module.scanner.MediaStoreObserver
import com.rawsmusic.module.scanner.MediaStoreScanner
import com.rawsmusic.module.scanner.ScanScheduler
import com.rawsmusic.module.scanner.ScanUiState
import com.rawsmusic.module.scanner.TwoStageMediaScanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 统一扫描调度器。
 * 手动扫描和自动监听共用一个入口，互不冲突。
 */
class StartupScanHelper(
    private val context: Context,
    private val audioRepository: AudioLibraryRepository? = null
) {
    private var mediaStoreObserver: MediaStoreObserver? = null
    private var autoObserverJob: Job? = null
    private var currentScanJob: Job? = null
    private var pendingScanReason: String? = null
    private var _enrichedCount = 0  // enriched 节流计数器
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var scanCoordinator: LibraryScanCoordinator? = null
    private val resolvedRepository: AudioLibraryRepository? by lazy {
        audioRepository ?: LibraryScannerDependencies.repositoryOrNull(context)
    }

    private val _scanUiState = MutableStateFlow(ScanUiState.idle())
    val scanUiState: StateFlow<ScanUiState> = _scanUiState.asStateFlow()

    private val _songs = MutableStateFlow<List<AudioFile>>(emptyList())
    val songs: StateFlow<List<AudioFile>> = _songs.asStateFlow()

    fun start() {
        AppLogger.d(TAG, "start")
        if (resolvedRepository != null) {
            startAutoMediaStoreScan()
            scope.launch {
                val existingCount = runCatching {
                    resolvedRepository?.getAllSongs()?.size ?: 0
                }.getOrDefault(0)
                if (existingCount <= 0) {
                    // 空库不自动扫描，显示空态提示用户选择文件夹
                    AppLogger.d(TAG, "library empty, waiting for user to select folders")
                    _scanUiState.value = ScanUiState.idle().copy(
                        message = context.getString(R.string.scan_library_empty)
                    )
                } else {
                    AppLogger.d(TAG, "skip startup auto scan, existing songs=$existingCount")
                    _scanUiState.value = ScanUiState.idle().copy(
                        message = context.getString(R.string.scan_library_ready),
                        found = existingCount
                    )
                }
            }
        } else {
            registerMediaStoreObserver()
            AppLogger.w(TAG, "LibraryScannerDependencies not installed, fallback to legacy ScanScheduler")
            AppLogger.d(TAG, "legacy initial auto scan skipped; wait for manual scan")
        }
    }

    fun scanLibrary(restartIfRunning: Boolean = true) {
        if (resolvedRepository != null) requestScan(context, "手动扫描", restartIfRunning)
        else ScanScheduler.requestDirScan(context, "手动扫描")
    }

    fun cancelScan() {
        currentScanJob?.cancel(CancellationException("User cancelled scan"))
        currentScanJob = null
        ScanScheduler.cancelCurrentScan()
        _scanUiState.value = ScanUiState.cancelled()
        AppLogger.d(TAG, "scan cancelled by user")
    }

    private fun registerMediaStoreObserver() {
        try {
            val observer = MediaStoreObserver(context)
            observer.register()
            mediaStoreObserver = observer
            AppLogger.d(TAG, "MediaStoreObserver registered")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to register MediaStoreObserver", e)
        }
    }

    private fun startAutoMediaStoreScan() {
        val repository = resolvedRepository
        if (repository == null) {
            AppLogger.d(TAG, "No AudioLibraryRepository, skipping auto scan")
            return
        }
        scanCoordinator = LibraryScanCoordinator(repository)

        autoObserverJob = scope.launch {
            MediaStoreChangeObserver.observeAudio(context, debounceMs = 2_500L)
                .collectLatest { change ->
                    AppLogger.d(TAG, "MediaStore changed: uri=${change.uri}")
                    _scanUiState.value = _scanUiState.value.copy(
                        pendingScan = true,
                        message = context.getString(R.string.scan_media_changed)
                    )
                }
        }
        AppLogger.d(TAG, "Auto MediaStore scan started")
    }

    private fun requestScan(context: Context, reason: String, restartIfRunning: Boolean) {
        val running = currentScanJob?.isActive == true
        if (running) {
            if (restartIfRunning) {
                AppLogger.d(TAG, "restart scan: $reason")
                currentScanJob?.cancel(CancellationException("Restart: $reason"))
            } else {
                pendingScanReason = reason
                _scanUiState.value = _scanUiState.value.copy(
                    pendingScan = true,
                    message = context.getString(R.string.scan_pending, _scanUiState.value.message)
                )
                AppLogger.d(TAG, "scan running, mark pending: $reason")
                return
            }
        }
        currentScanJob = scope.launch { runScanLoop(context, reason) }
    }

    private suspend fun runScanLoop(context: Context, initialReason: String) {
        var reason = initialReason
        while (true) {
            pendingScanReason = null
            _enrichedCount = 0  // 重置 enriched 节流计数器
            try {
                runSingleScan(context, reason)
            } catch (e: CancellationException) {
                _scanUiState.value = ScanUiState.cancelled()
                AppLogger.d(TAG, "scan cancelled: ${e.message}")
                throw e
            } catch (e: Exception) {
                _scanUiState.value = _scanUiState.value.copy(
                    isScanning = false, canCancel = false,
                    stage = context.getString(R.string.scan_stage_error), error = e.message,
                    message = context.getString(R.string.scan_failed, e.message.orEmpty())
                )
                AppLogger.e(TAG, "scan failed", e)
                return
            }
            val pending = pendingScanReason ?: return
            reason = pending
            AppLogger.d(TAG, "run pending scan: $reason")
        }
    }

    private suspend fun runSingleScan(context: Context, reason: String) {
        // 检查用户是否已选择扫描文件夹
        val scanPaths = com.rawsmusic.module.data.prefs.AppPreferences.UI.scanPaths
        val safFolders = com.rawsmusic.module.data.prefs.AppPreferences.Scanner.musicFolderUris
        if (scanPaths.isEmpty() && safFolders.isEmpty()) {
            AppLogger.w(TAG, "runSingleScan: no filesystem path or SAF folder selected")
            _scanUiState.value = ScanUiState.idle().copy(
                message = context.getString(R.string.scan_select_folder)
            )
            // 触发文件夹选择需求
            com.rawsmusic.module.scanner.ScanStateBus.notifyFolderSelectionNeeded()
            return
        }

        _scanUiState.value = ScanUiState.starting(reason)
        val coordinator = scanCoordinator ?: resolvedRepository?.let { LibraryScanCoordinator(it).also { c -> scanCoordinator = c } } ?: return
        var finalSync: LibraryScanCoordinator.Event.DatabaseSyncCompleted? = null

        coordinator.scanAndSync(
            context = context,
            options = TwoStageMediaScanner.Options(
                scannerOptions = MediaStoreScanner.ScanOptions.fromPreferences(),
                customPaths = scanPaths,
                sourceMode = TwoStageMediaScanner.SourceMode.fromPreferences(),
                expandCueTracks = true, emitEachSong = false, usePersistentCache = true
            )
        ).collect { event ->
            when (event) {
                is LibraryScanCoordinator.Event.ScannerEvent -> handleScannerEvent(reason, event.event)
                is LibraryScanCoordinator.Event.DatabaseSyncStarted -> {
                    val stageText = when (event.phase) {
                        LibraryScanCoordinator.SyncPhase.QUICK_VISIBLE -> context.getString(R.string.scan_stage_quick_write)
                        LibraryScanCoordinator.SyncPhase.ENRICHED_BATCH -> context.getString(R.string.scan_stage_background_enrich)
                        LibraryScanCoordinator.SyncPhase.FINAL -> context.getString(R.string.scan_stage_final_sync)
                    }
                    _scanUiState.value = _scanUiState.value.copy(
                        isScanning = true, canCancel = true, stage = stageText,
                        message = context.getString(R.string.scan_sync_progress, reason, stageText, event.newCount)
                    )
                }
                is LibraryScanCoordinator.Event.DatabaseSyncCompleted -> {
                    if (event.phase == LibraryScanCoordinator.SyncPhase.FINAL) finalSync = event
                    val message = when (event.phase) {
                        LibraryScanCoordinator.SyncPhase.QUICK_VISIBLE -> context.getString(R.string.scan_quick_visible, reason, event.upserted)
                        LibraryScanCoordinator.SyncPhase.ENRICHED_BATCH -> context.getString(R.string.scan_background_enriched, reason, event.upserted)
                        LibraryScanCoordinator.SyncPhase.FINAL -> context.getString(R.string.scan_database_synced, reason, event.upserted, event.deleted, event.unchanged)
                    }
                    _scanUiState.value = _scanUiState.value.copy(
                        isScanning = true, canCancel = true, stage = context.getString(R.string.scan_stage_database_complete),
                        dbUpserted = event.upserted, dbDeleted = event.deleted, dbUnchanged = event.unchanged,
                        message = message
                    )
                }
                is LibraryScanCoordinator.Event.VisibleCompleted -> {
                    _songs.value = event.songs
                    MusicRepository.publishTransientScanSongs(event.songs)
                    _scanUiState.value = _scanUiState.value.copy(
                        isScanning = true, canCancel = true, pendingScan = false,
                        stage = context.getString(R.string.scan_stage_visible), progress = _scanUiState.value.progress.coerceAtLeast(0.40f),
                        found = event.found, timeMs = event.timeMs,
                        message = context.getString(R.string.scan_visible_progress, reason, event.found)
                    )
                }
                is LibraryScanCoordinator.Event.Completed -> {
                    _songs.value = event.songs
                    _scanUiState.value = _scanUiState.value.copy(
                        isScanning = false, canCancel = false, pendingScan = false,
                        stage = context.getString(R.string.scan_stage_complete), progress = 1f, found = event.songs.size, timeMs = event.timeMs,
                        message = context.getString(R.string.scan_completed, reason, event.songs.size, event.timeMs)
                    )
                    val stats = finalSync
                    val elapsedSeconds = event.timeMs / 1000.0
                    val toastText = if (stats == null ||
                        (stats.added == 0 && stats.updated == 0 && stats.deleted == 0)
                    ) {
                        context.getString(
                            R.string.scan_complete_no_changes,
                            event.songs.size,
                            elapsedSeconds
                        )
                    } else {
                        context.getString(
                            R.string.scan_complete_summary,
                            stats.added,
                            stats.updated,
                            stats.deleted,
                            stats.unchanged,
                            event.songs.size,
                            elapsedSeconds
                        )
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context.applicationContext, toastText, Toast.LENGTH_LONG).show()
                    }
                }
                is LibraryScanCoordinator.Event.Error -> {
                    _scanUiState.value = _scanUiState.value.copy(
                        isScanning = false, canCancel = false, stage = context.getString(R.string.scan_stage_error),
                        error = event.message, message = context.getString(R.string.scan_error_with_reason, reason, event.message)
                    )
                }
            }
        }
    }

    private fun handleScannerEvent(reason: String, event: TwoStageMediaScanner.Event) {
        when (event) {
            is TwoStageMediaScanner.Event.Started -> {
                _scanUiState.value = _scanUiState.value.copy(
                    isScanning = true, canCancel = true, reason = reason, stage = context.getString(R.string.scan_stage_start),
                    total = event.totalEstimated, scanned = 0, progress = 0f,
                    message = context.getString(R.string.scan_started, reason, event.totalEstimated)
                )
            }
            is TwoStageMediaScanner.Event.CacheLoaded -> {
                _scanUiState.value = _scanUiState.value.copy(
                    stage = context.getString(R.string.scan_stage_cache), message = context.getString(R.string.scan_cache_loaded, reason, event.cachedCount)
                )
            }
            is TwoStageMediaScanner.Event.QuickProgress -> {
                val p = if (event.total > 0) event.scanned.toFloat() / event.total * 0.35f else 0f
                _scanUiState.value = _scanUiState.value.copy(
                    isScanning = true, canCancel = true, stage = context.getString(R.string.scan_stage_quick),
                    scanned = event.scanned, total = event.total, progress = p.coerceIn(0f, 0.35f),
                    message = context.getString(R.string.scan_progress, reason, event.message, event.scanned, event.total)
                )
            }
            is TwoStageMediaScanner.Event.QuickCompleted -> {
                _songs.value = event.songs
                // 快速扫描完成：立即发布到 MusicRepository 让 UI 显示
                MusicRepository.publishTransientScanSongs(event.songs)
                _scanUiState.value = _scanUiState.value.copy(
                    isScanning = true, canCancel = true, stage = context.getString(R.string.scan_stage_quick_complete),
                    found = event.found, progress = 0.35f,
                    message = context.getString(R.string.scan_quick_found, reason, event.found)
                )
            }
            is TwoStageMediaScanner.Event.EnrichProgress -> {
                val ep = if (event.total > 0) event.processed.toFloat() / event.total else 0f
                _scanUiState.value = _scanUiState.value.copy(
                    isScanning = true, canCancel = true, stage = context.getString(R.string.scan_stage_enrich),
                    scanned = event.processed, total = event.total,
                    progress = (0.35f + ep * 0.55f).coerceIn(0.35f, 0.90f),
                    cacheHits = event.cacheHits, enrichedCount = event.enrichedCount,
                    message = context.getString(R.string.scan_enrich_progress, reason, event.message, event.processed, event.total, event.cacheHits, event.enrichedCount)
                )
            }
            is TwoStageMediaScanner.Event.SongEnriched -> {
                val updated = AudioFileListUpdater.applyEnrichedResult(
                    _songs.value, event.originalSongId, event.originalPath, event.songs
                )
                _songs.value = updated
                // 节流发布到 MusicRepository（每 20 首发布一次）
                if (_enrichedCount % 20 == 0) {
                    MusicRepository.publishTransientScanSongs(updated)
                }
                _enrichedCount++
            }
            is TwoStageMediaScanner.Event.EnrichBatchCompleted -> {
                // 后台懒同步批次，UI 进度由 EnrichProgress 统一更新。
            }
            is TwoStageMediaScanner.Event.FullyCompleted -> {
                _songs.value = event.songs
                // 强制发布最终扫描结果到 MusicRepository
                MusicRepository.publishTransientScanSongs(event.songs)
                _scanUiState.value = _scanUiState.value.copy(
                    isScanning = true, canCancel = true, stage = context.getString(R.string.scan_stage_prepare_sync),
                    progress = 0.90f, found = event.found,
                    cacheHits = event.cacheHits, enrichedCount = event.enrichedCount,
                    message = context.getString(R.string.scan_enrich_completed, reason, event.found, event.cacheHits, event.enrichedCount)
                )
            }
            is TwoStageMediaScanner.Event.Error -> {
                _scanUiState.value = _scanUiState.value.copy(
                    isScanning = false, canCancel = false, stage = context.getString(R.string.scan_stage_error),
                    error = event.message, message = context.getString(R.string.scan_error_with_reason, reason, event.message)
                )
            }
        }
    }

    fun destroy() {
        mediaStoreObserver?.unregister()
        mediaStoreObserver = null
        autoObserverJob?.cancel()
        currentScanJob?.cancel()
        scope.cancel()
    }

    companion object {
        private const val TAG = "StartupScanHelper"
    }
}
