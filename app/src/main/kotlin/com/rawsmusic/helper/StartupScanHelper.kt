package com.rawsmusic.helper

import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import android.os.SystemClock
import com.rawsmusic.module.scanner.AutomaticScanScheduler
import com.rawsmusic.R
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.locale.localizedErrorMessage
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.scanner.AudioLibraryRepository
import com.rawsmusic.module.scanner.LibraryScanCoordinator
import com.rawsmusic.module.scanner.LibraryScanSummary
import com.rawsmusic.module.scanner.LibraryScanToast
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
import java.util.concurrent.atomic.AtomicBoolean

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
    private var mountReceiver: BroadcastReceiver? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requestScheduler = AutomaticScanScheduler(
        scope = scope,
        automaticEnabled = {
            AppPreferences.Scanner.automaticScanEnabled &&
                (AppPreferences.UI.scanPaths.isNotEmpty() || AppPreferences.Scanner.musicFolderUris.isNotEmpty())
        },
        nowMs = { System.currentTimeMillis() },
        uptimeMs = { SystemClock.uptimeMillis() },
        immediate = { AppPreferences.Scanner.immediateAutoScanEnabled },
        startupToken = coldStartScanRequested,
        scan = { request -> runScanLoop(context, ScanRequest(request.reason, request.automatic)) },
        externallyScanning = { LibraryScanCoordinator.isScanning }
    )
    private var scanCoordinator: LibraryScanCoordinator? = null
    private val resolvedRepository: AudioLibraryRepository? by lazy {
        audioRepository ?: LibraryScannerDependencies.repositoryOrNull(context)
    }

    private val _scanUiState = MutableStateFlow(ScanUiState.idle())
    val scanUiState: StateFlow<ScanUiState> = _scanUiState.asStateFlow()

    private val _songs = MutableStateFlow<List<AudioFile>>(emptyList())
    val songs: StateFlow<List<AudioFile>> = _songs.asStateFlow()

    fun start() {
        if (autoObserverJob != null || mediaStoreObserver != null) return
        AppLogger.d(TAG, "start")
        if (resolvedRepository != null) {
            startAutoMediaStoreScan()
            registerMountReceiver()
            scope.launch {
                val existingCount = runCatching {
                    resolvedRepository?.getAllSongs()?.size ?: 0
                }.getOrDefault(0)
                val hasSelectedFolders = AppPreferences.UI.scanPaths.isNotEmpty() ||
                    AppPreferences.Scanner.musicFolderUris.isNotEmpty()
                val shouldAutoScan = AppPreferences.Scanner.automaticScanEnabled &&
                    AppPreferences.Scanner.coldStartAutoScanEnabled && hasSelectedFolders

                if (shouldAutoScan) {
                    AppLogger.d(TAG, "request cold-start incremental scan, existing songs=$existingCount")
                    requestScheduler.startup(context.getString(R.string.scan_reason_cold_start), true)
                } else if (existingCount <= 0) {
                    requestScheduler.startup(context.getString(R.string.scan_reason_cold_start), false)
                    // 没有可扫描来源时保持空态，不从冷启动流程主动拉起系统授权。
                    AppLogger.d(TAG, "library empty, waiting for user to select folders")
                    _scanUiState.value = ScanUiState.idle().copy(
                        message = context.getString(R.string.scan_library_empty)
                    )
                } else {
                    requestScheduler.startup(context.getString(R.string.scan_reason_cold_start), false)
                    AppLogger.d(
                        TAG,
                        "cold-start scan skipped: enabled=${AppPreferences.Scanner.coldStartAutoScanEnabled}, " +
                            "selectedFolders=$hasSelectedFolders, existing songs=$existingCount"
                    )
                    _scanUiState.value = ScanUiState.idle().copy(
                        message = context.getString(R.string.scan_library_ready),
                        found = existingCount
                    )
                }
            }
        } else {
            if (AppPreferences.Scanner.automaticScanEnabled) registerMediaStoreObserver()
            AppLogger.w(TAG, "LibraryScannerDependencies not installed, fallback to legacy ScanScheduler")
            if (AppPreferences.Scanner.automaticScanEnabled && AppPreferences.Scanner.coldStartAutoScanEnabled) {
                ScanScheduler.scheduleInitialScan(context)
            }
        }
    }

    fun scanLibrary(restartIfRunning: Boolean = true) {
        val reason = context.getString(R.string.scan_settings_rescan_reason_manual)
        if (resolvedRepository != null) requestScheduler.manual(reason)
        else ScanScheduler.requestDirScan(context, reason)
    }

    fun cancelScan() {
        requestScheduler.cancel()
        ScanScheduler.cancelCurrentScan()
        _scanUiState.value = ScanUiState.cancelled(
            stage = context.getString(R.string.scan_stage_cancelled),
            message = context.getString(R.string.scan_cancelled),
        )
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

    private fun registerMountReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_MEDIA_MOUNTED) {
                    requestScheduler.mounted(this@StartupScanHelper.context.getString(R.string.scan_media_changed))
                }
            }
        }
        val filter = IntentFilter(Intent.ACTION_MEDIA_MOUNTED).apply { addDataScheme("file") }
        ContextCompat.registerReceiver(context.applicationContext, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        mountReceiver = receiver
    }

    private fun startAutoMediaStoreScan() {
        val repository = resolvedRepository
        if (repository == null) {
            AppLogger.d(TAG, "No AudioLibraryRepository, skipping auto scan")
            return
        }
        scanCoordinator = LibraryScanCoordinator(repository)

        autoObserverJob = scope.launch {
            MediaStoreChangeObserver.observeAudio(context, debounceMs = 0L)
                .collectLatest { change ->
                    if (!AppPreferences.Scanner.automaticScanEnabled) return@collectLatest
                    AppLogger.d(TAG, "MediaStore changed: uri=${change.uri}")
                    _scanUiState.value = _scanUiState.value.copy(
                        pendingScan = true,
                        message = context.getString(R.string.scan_media_changed)
                    )
                    requestScheduler.systemChanged(context.getString(R.string.scan_media_changed))
                }
        }
        AppLogger.d(TAG, "Auto MediaStore scan started")
    }

    private suspend fun runScanLoop(context: Context, request: ScanRequest) {
        try {
            runSingleScan(context, request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _scanUiState.value = _scanUiState.value.copy(
                isScanning = false, canCancel = false,
                stage = context.getString(R.string.scan_stage_error), error = e.message,
                message = context.getString(
                    R.string.scan_failed,
                    context.localizedErrorMessage(e, R.string.ui_unknown_error),
                )
            )
            AppLogger.e(TAG, "scan failed", e)
        }
    }

    private suspend fun runSingleScan(context: Context, request: ScanRequest) {
        val reason = request.reason
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

        _scanUiState.value = ScanUiState.starting(
            reason = reason,
            stage = context.getString(R.string.scan_stage_start),
            message = context.getString(R.string.scan_preparing, reason),
        )
        val coordinator = scanCoordinator ?: resolvedRepository?.let { LibraryScanCoordinator(it).also { c -> scanCoordinator = c } } ?: return
        var finalSync: LibraryScanCoordinator.Event.DatabaseSyncCompleted? = null

        coordinator.scanAndSync(
            context = context,
            options = TwoStageMediaScanner.Options(
                scannerOptions = MediaStoreScanner.ScanOptions.fromPreferences(),
                customPaths = scanPaths,
                sourceMode = if (request.coldStart) {
                    TwoStageMediaScanner.SourceMode.SELECTED_FOLDERS
                } else {
                    TwoStageMediaScanner.SourceMode.fromPreferences()
                },
                expandCueTracks = true,
                emitEachSong = false,
                usePersistentCache = !request.coldStart,
                saveCacheAtEnd = !request.coldStart,
                incrementalMetadataOnly = request.coldStart,
                publishQuickVisible = !request.coldStart
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
                    LibraryScanToast.show(
                        context,
                        LibraryScanSummary(
                            scanned = event.songs.size,
                            added = stats?.added ?: 0,
                            updated = stats?.updated ?: 0,
                            removed = stats?.deleted ?: 0,
                            elapsedMs = event.timeMs,
                            mode = if (event.incremental) {
                                LibraryScanSummary.Mode.INCREMENTAL
                            } else {
                                LibraryScanSummary.Mode.FULL
                            },
                            skippedDirectories = event.skippedDirectoryCount,
                            reused = event.baselineHits,
                            metadataParsed = event.metadataParsed
                        )
                    )
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
                // QuickCompleted still contains one physical row per media file and has not
                // expanded embedded/external CUE yet. Publishing it here can temporarily erase
                // already-persisted CUE virtual tracks that share the same path. The coordinator
                // publishes VisibleCompleted immediately after the quick Room sync; only that
                // repository snapshot is safe to expose to the library UI.
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
                // Progress only: library rows become visible through committed repository state.
            }
            is TwoStageMediaScanner.Event.EnrichBatchCompleted -> {
                // 后台懒同步批次，UI 进度由 EnrichProgress 统一更新。
            }
            is TwoStageMediaScanner.Event.FullyCompleted -> {
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
        requestScheduler.destroy()
        mountReceiver?.let { runCatching { context.applicationContext.unregisterReceiver(it) } }
        mountReceiver = null
        mediaStoreObserver?.unregister()
        mediaStoreObserver = null
        autoObserverJob?.cancel()
        scope.cancel()
    }

    companion object {
        private const val TAG = "StartupScanHelper"
        private val coldStartScanRequested = AtomicBoolean(false)
    }

    private data class ScanRequest(val reason: String, val coldStart: Boolean)
}
