package com.rawsmusic.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.rawsmusic.R
import com.rawsmusic.core.common.ui.AppNoticeBus
import com.rawsmusic.core.common.ui.AppNoticeIcon
import com.rawsmusic.ai.melody.AiMelodyModelStore
import com.rawsmusic.ai.melody.AiRecommendedMelodyModels
import com.rawsmusic.ai.instrument.AiRecommendedPianoPack
import com.rawsmusic.ai.instrument.AiRecommendedPianoPackInstaller
import com.rawsmusic.ai.instrument.AiInstrumentPackStore
import com.rawsmusic.separation.AiSeparationDownloadPhase
import com.rawsmusic.separation.AiSeparationDownloadService
import com.rawsmusic.separation.AiFastVocalAlignmentModel
import com.rawsmusic.separation.AiFastVocalAlignmentBundle
import com.rawsmusic.separation.AiSeparationJobPhase
import com.rawsmusic.separation.AiSeparationJobProgressBus
import com.rawsmusic.separation.AiSeparationJobService
import com.rawsmusic.separation.AiRecommendedModels
import com.rawsmusic.separation.AiSeparationPluginStore
import com.rawsmusic.separation.AiRecommendedRuntime
import com.rawsmusic.separation.AiSeparationPreferences
import com.rawsmusic.separation.AiSeparationProgressBus
import com.rawsmusic.separation.AiSeparationResult
import com.rawsmusic.separation.AiSeparationResultStore
import com.rawsmusic.separation.AiSeparationRuntimeBridge
import com.rawsmusic.separation.AiSeparationLivePlayer
import com.rawsmusic.separation.AiSeparationLiveStreamBus
import com.rawsmusic.separation.AiSeparationStem
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AiSeparationSettingsScreen(
    onBack: () -> Unit,
    initialAudioUri: Uri? = null,
    initialAudioName: String = "",
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember(context) { AiSeparationPluginStore.get(context) }
    val resultStore = remember(context) { AiSeparationResultStore.get(context) }
    val livePlayer = remember(context) { AiSeparationLivePlayer.get(context) }
    val melodyModelStore = remember(context) { AiMelodyModelStore.get(context) }
    val instrumentPackStore = remember(context) { AiInstrumentPackStore.get(context) }
    val recommendedPianoInstaller = remember(context) { AiRecommendedPianoPackInstaller.get(context) }
    val state by store.state.collectAsState()
    val melodyStoreRevision by melodyModelStore.revision.collectAsState()
    val instrumentPackRevision by instrumentPackStore.revision.collectAsState()
    val downloadProgress by AiSeparationProgressBus.state.collectAsState()
    val jobProgress by AiSeparationJobProgressBus.state.collectAsState()
    val liveStream by AiSeparationLiveStreamBus.state.collectAsState()
    val livePlayback by livePlayer.state.collectAsState()
    val results by resultStore.results.collectAsState()
    var runtimeStatus by remember { mutableStateOf(AiSeparationRuntimeBridge.status(context)) }
    var deleteTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    var deleteResultTarget by remember { mutableStateOf<String?>(null) }
    var repositoryUrlDialog by remember { mutableStateOf(false) }
    var repositoryUrl by remember { mutableStateOf("") }
    var selectedAudioUri by remember(initialAudioUri) { mutableStateOf(initialAudioUri) }
    var selectedAudioName by remember(initialAudioName) {
        mutableStateOf(initialAudioName.ifBlank { initialAudioUri?.lastPathSegment.orEmpty() })
    }
    var denoiseEnabled by remember(context) {
        mutableStateOf(AiSeparationPreferences.isDenoiseEnabled(context))
    }
    var liveStreamingEnabled by remember(context) {
        mutableStateOf(AiSeparationPreferences.isLiveStreamingEnabled(context))
    }
    var recommendedImportTarget by remember {
        mutableStateOf(
            AiRecommendedModels.UVR_9482_ID to AiRecommendedModels.UVR_9482_VERSION
        )
    }
    var melodyModelInstalled by remember { mutableStateOf<Boolean?>(null) }
    var melodyModelRevision by remember { mutableStateOf(0) }
    var recommendedPianoInstalled by remember { mutableStateOf<Boolean?>(null) }
    var recommendedPianoRevision by remember { mutableStateOf(0) }
    var observedMelodyStoreRevision by remember { mutableStateOf(-1L) }
    var observedInstrumentPackRevision by remember { mutableStateOf(-1L) }

    LaunchedEffect(
        downloadProgress.modelId,
        downloadProgress.modelVersion,
        downloadProgress.phase,
        melodyModelRevision,
        melodyStoreRevision,
    ) {
        val recommended = AiRecommendedMelodyModels.RMVPE_Q8
        val storeChanged = melodyStoreRevision != observedMelodyStoreRevision
        if (
            melodyModelInstalled == null || storeChanged ||
            downloadProgress.modelId == recommended.id &&
            downloadProgress.modelVersion == recommended.version
        ) {
            melodyModelInstalled = withContext(Dispatchers.IO) {
                melodyModelStore.resolveRecommended() != null
            }
        }
        observedMelodyStoreRevision = melodyStoreRevision
    }

    LaunchedEffect(
        downloadProgress.modelId,
        downloadProgress.modelVersion,
        downloadProgress.phase,
        recommendedPianoRevision,
        instrumentPackRevision,
    ) {
        val storeChanged = instrumentPackRevision != observedInstrumentPackRevision
        if (
            recommendedPianoInstalled == null || storeChanged ||
            downloadProgress.modelId == AiRecommendedPianoPack.ID &&
            downloadProgress.modelVersion == AiRecommendedPianoPack.VERSION
        ) {
            recommendedPianoInstalled = withContext(Dispatchers.IO) {
                recommendedPianoInstaller.resolveInstalled() != null
            }
        }
        observedInstrumentPackRevision = instrumentPackRevision
    }

    suspend fun repositoryImportMessage(
        result: Result<com.rawsmusic.separation.AiModelRepositoryDescriptor>,
    ): String {
        val descriptor = result.getOrNull()
        if (descriptor == null) {
            return result.exceptionOrNull()?.message
                ?: context.getString(R.string.settings_ai_repository_import_failed)
        }
        val refresh = store.refreshCatalog()
        return refresh.fold(
            onSuccess = {
                context.getString(
                    R.string.settings_ai_repository_imported_and_refreshed,
                    descriptor.name,
                    it.size,
                )
            },
            onFailure = {
                context.getString(R.string.settings_ai_repository_imported, descriptor.name) +
                    "\n" + (it.message
                    ?: context.getString(R.string.settings_ai_repository_refresh_failed))
            },
        )
    }

    fun downloadFirstFastAlignmentModel() {
        AiSeparationDownloadService.startFastVocalAlignment(context)
        AppNoticeBus.post(
            message = context.getString(
                R.string.settings_ai_fast_alignment_download_started,
                context.getString(R.string.ai_fast_alignment_bundle_name),
            ),
            icon = AppNoticeIcon.DOWNLOAD,
        )
    }

    fun downloadFirstLyricAlignmentModel() {
        scope.launch {
            val result = runCatching {
                val model = store.refreshLyricAlignmentCatalog().getOrThrow()
                    .minWithOrNull(
                        compareBy<com.rawsmusic.separation.AiLyricAlignmentCatalogEntry> {
                            it.estimatedMemoryMb
                        }.thenBy { it.modelSizeBytes + it.vocabularySizeBytes },
                    ) ?: error(
                        context.getString(R.string.settings_ai_lyric_alignment_no_downloadable_model),
                    )
                AiSeparationDownloadService.startLyricAlignment(
                    context,
                    model.id,
                    model.version,
                )
                model.name
            }
            showOperationNotice(
                context = context,
                success = result.isSuccess,
                message = result.fold(
                    onSuccess = {
                        context.getString(
                            R.string.settings_ai_lyric_alignment_download_started,
                            it,
                        )
                    },
                    onFailure = { it.message.orEmpty() },
                ),
            )
        }
    }

    val repositoryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = store.importRepository(uri)
            showOperationNotice(
                context = context,
                success = result.isSuccess,
                message = repositoryImportMessage(result),
            )
        }
    }

    val modelPackageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = store.importModelPackage(uri)
            showOperationNotice(
                context = context,
                success = result.isSuccess,
                message = result.fold(
                    onSuccess = { context.getString(R.string.settings_ai_model_imported, it.catalog.name) },
                    onFailure = { it.message ?: context.getString(R.string.settings_ai_model_import_failed) },
                ),
            )
        }
    }

    val runtimeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = store.importRuntime(uri)
            runtimeStatus = AiSeparationRuntimeBridge.status(context)
            showOperationNotice(
                context = context,
                success = result.isSuccess,
                message = result.fold(
                    onSuccess = {
                        context.getString(R.string.settings_ai_runtime_imported, it.name, it.version)
                    },
                    onFailure = {
                        it.message ?: context.getString(R.string.settings_ai_runtime_import_failed)
                    },
                ),
            )
        }
    }

    val recommendedModelLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = store.importRecommendedModel(
                uri,
                recommendedImportTarget.first,
                recommendedImportTarget.second,
            )
            showOperationNotice(
                context = context,
                success = result.isSuccess,
                message = result.fold(
                    onSuccess = { context.getString(R.string.settings_ai_recommended_imported, it.catalog.name) },
                    onFailure = {
                        it.message ?: context.getString(R.string.settings_ai_recommended_import_failed)
                    },
                ),
            )
        }
    }

    val audioLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        selectedAudioUri = uri
        selectedAudioName = queryDisplayName(context, uri).ifBlank { "audio" }
    }

    LaunchedEffect(Unit) {
        store.reload()
        resultStore.cleanupStaleStaging()
        resultStore.reload()
        runtimeStatus = AiSeparationRuntimeBridge.status(context)
    }

    LaunchedEffect(downloadProgress.phase, downloadProgress.message) {
        when (downloadProgress.phase) {
            AiSeparationDownloadPhase.COMPLETED,
            AiSeparationDownloadPhase.FAILED,
            AiSeparationDownloadPhase.CANCELLED -> {
                store.reload()
                runtimeStatus = AiSeparationRuntimeBridge.status(context)
                if (downloadProgress.message.isNotBlank()) {
                    if (downloadProgress.phase == AiSeparationDownloadPhase.FAILED) {
                        AppNoticeBus.error(downloadProgress.message)
                    } else if (downloadProgress.phase == AiSeparationDownloadPhase.COMPLETED) {
                        AppNoticeBus.post(
                            message = downloadProgress.message,
                            icon = AppNoticeIcon.DOWNLOAD,
                        )
                    } else {
                        Toast.makeText(context, downloadProgress.message, Toast.LENGTH_LONG).show()
                    }
                }
                delay(1_500L)
                AiSeparationProgressBus.clearCompleted()
            }
            else -> Unit
        }
    }

    LaunchedEffect(jobProgress.phase, jobProgress.resultId, jobProgress.message) {
        when (jobProgress.phase) {
            AiSeparationJobPhase.COMPLETED,
            AiSeparationJobPhase.FAILED,
            AiSeparationJobPhase.CANCELLED -> {
                resultStore.reload()
                if (jobProgress.message.isNotBlank()) {
                    if (jobProgress.phase == AiSeparationJobPhase.FAILED) {
                        AppNoticeBus.error(jobProgress.message)
                    } else if (jobProgress.phase == AiSeparationJobPhase.COMPLETED) {
                        AppNoticeBus.post(
                            message = jobProgress.message,
                            icon = AppNoticeIcon.AI,
                        )
                    } else {
                        Toast.makeText(context, jobProgress.message, Toast.LENGTH_LONG).show()
                    }
                }
                delay(2_000L)
                AiSeparationJobProgressBus.clearCompleted()
            }
            else -> Unit
        }
    }

    SettingsPage(
        title = stringResource(R.string.settings_ai_separation_title),
        onBack = onBack,
    ) {
        SettingsSection(stringResource(R.string.settings_ai_runtime_section)) {
            SettingsInfoEntry(
                title = if (runtimeStatus.onnxRuntimePresent) {
                    stringResource(R.string.settings_ai_runtime_ready)
                } else {
                    stringResource(R.string.settings_ai_runtime_not_linked)
                },
                description = if (runtimeStatus.onnxRuntimePresent) {
                    stringResource(
                        R.string.settings_ai_runtime_ready_summary,
                        runtimeStatus.abiVersion,
                        runtimeStatus.details,
                    )
                } else {
                    stringResource(R.string.settings_ai_runtime_not_linked_summary, runtimeStatus.details)
                },
            )
            SettingsNavigationEntry(
                title = stringResource(R.string.settings_ai_runtime_recheck),
                description = stringResource(R.string.settings_ai_runtime_recheck_summary),
                onClick = { runtimeStatus = AiSeparationRuntimeBridge.status(context) },
            )
            val runtimeEntry = state.runtimeCatalog
                .filter { it.abi in Build.SUPPORTED_ABIS }
                .maxByOrNull { it.version }
                ?: AiRecommendedRuntime.ONNX_RUNTIME_1_26
            val runtimeDownloadActive = downloadProgress.active &&
                downloadProgress.modelId == runtimeEntry.id &&
                downloadProgress.modelVersion == runtimeEntry.version
            if (!runtimeStatus.onnxRuntimePresent) {
                if (runtimeDownloadActive) {
                    SettingsInfoEntry(
                        title = stringResource(R.string.settings_ai_runtime_downloading),
                        description = buildDownloadProgressText(
                            downloadProgress.downloadedBytes,
                            downloadProgress.totalBytes,
                        ),
                    )
                } else {
                    SettingsNavigationEntry(
                        title = stringResource(R.string.settings_ai_runtime_download),
                        description = stringResource(
                            R.string.settings_ai_runtime_download_summary,
                            runtimeEntry.name,
                            runtimeEntry.version,
                            formatBytes(
                                if (state.runtimeCatalog.any {
                                        it.id == runtimeEntry.id &&
                                            it.version == runtimeEntry.version &&
                                            it.abi == runtimeEntry.abi
                                    }
                                ) {
                                    runtimeEntry.librarySizeBytes
                                } else {
                                    AiRecommendedRuntime.ARCHIVE_SIZE_BYTES
                                }
                            ),
                        ),
                        onClick = { AiSeparationDownloadService.startRuntime(context) },
                    )
                }
            }
            if (!runtimeStatus.onnxRuntimePresent) {
                SettingsNavigationEntry(
                    title = stringResource(R.string.settings_ai_runtime_import),
                    description = stringResource(R.string.settings_ai_runtime_import_summary),
                    onClick = {
                        runtimeLauncher.launch(
                            arrayOf("application/octet-stream", "application/x-sharedlib", "*/*")
                        )
                    },
                )
            }
        }

        SettingsSection(stringResource(R.string.settings_ai_recommended_section)) {
            AiRecommendedModels.all.forEach { recommended ->
                val installed = state.isInstalled(recommended)
                val offlineSelected = state.isSelected(recommended)
                val realtimeSelected = state.isRealtimeSelected(recommended)
                val realtimeCapable = AiRecommendedModels.isRealtime(recommended)
                val activeDownload = downloadProgress.active &&
                    downloadProgress.modelId == recommended.id &&
                    downloadProgress.modelVersion == recommended.version
                val selectionStatus = buildList {
                    if (offlineSelected) add(stringResource(R.string.settings_ai_offline_selected))
                    if (realtimeSelected) add(stringResource(R.string.settings_ai_realtime_selected))
                    if (isEmpty()) {
                        add(
                            stringResource(
                                if (installed) {
                                    R.string.settings_ai_model_installed
                                } else {
                                    R.string.settings_ai_model_not_installed
                                }
                            )
                        )
                    }
                }.joinToString(" · ")
                SettingsInfoEntry(
                    title = "${recommended.name} ${recommended.version}",
                    description = stringResource(
                        R.string.settings_ai_recommended_model_summary,
                        selectionStatus,
                        if (realtimeCapable) {
                            stringResource(R.string.settings_ai_model_realtime)
                        } else {
                            stringResource(R.string.settings_ai_model_offline_only)
                        },
                        formatBytes(recommended.modelSizeBytes),
                        recommended.estimatedMemoryMb,
                        recommended.description,
                    ),
                )
                when {
                    activeDownload -> SettingsInfoEntry(
                        title = stringResource(R.string.settings_ai_model_downloading),
                        description = buildDownloadProgressText(
                            downloadProgress.downloadedBytes,
                            downloadProgress.totalBytes,
                        ),
                    )
                    else -> {
                        SettingsNavigationEntry(
                            title = stringResource(
                                if (installed) {
                                    R.string.settings_ai_recommended_redownload
                                } else {
                                    R.string.settings_ai_recommended_download
                                },
                            ),
                            description = if (realtimeCapable) {
                                stringResource(R.string.settings_ai_recommended_download_summary)
                            } else {
                                stringResource(R.string.settings_ai_roformer_download_summary)
                            },
                            onClick = {
                                AiSeparationDownloadService.start(
                                    context,
                                    recommended.id,
                                    recommended.version,
                                )
                            },
                        )
                        if (!installed) {
                            SettingsNavigationEntry(
                                title = stringResource(R.string.settings_ai_recommended_import),
                                description = stringResource(
                                    R.string.settings_ai_recommended_import_named_summary,
                                    recommended.name,
                                ),
                                onClick = {
                                    recommendedImportTarget = recommended.id to recommended.version
                                    recommendedModelLauncher.launch(
                                        arrayOf(
                                            "application/octet-stream",
                                            "application/onnx",
                                            "*/*",
                                        )
                                    )
                                },
                            )
                        }
                    }
                }
                if (installed && !offlineSelected && !activeDownload) {
                    SettingsNavigationEntry(
                        title = stringResource(R.string.settings_ai_select_offline_model),
                        description = stringResource(R.string.settings_ai_select_offline_model_summary),
                        onClick = {
                            scope.launch {
                                val result = store.selectModel(recommended.id, recommended.version)
                                showOperationNotice(
                                    context = context,
                                    success = result.isSuccess,
                                    message = result.fold(
                                        onSuccess = {
                                            context.getString(R.string.settings_ai_offline_selected_toast)
                                        },
                                        onFailure = { it.message.orEmpty() },
                                    ),
                                )
                            }
                        },
                    )
                }
                if (installed && realtimeCapable && !realtimeSelected && !activeDownload) {
                    SettingsNavigationEntry(
                        title = stringResource(R.string.settings_ai_select_realtime_model),
                        description = stringResource(R.string.settings_ai_select_realtime_model_summary),
                        onClick = {
                            scope.launch {
                                val result = store.selectRealtimeModel(
                                    recommended.id,
                                    recommended.version,
                                )
                                showOperationNotice(
                                    context = context,
                                    success = result.isSuccess,
                                    message = result.fold(
                                        onSuccess = {
                                            context.getString(R.string.settings_ai_realtime_selected_toast)
                                        },
                                        onFailure = { it.message.orEmpty() },
                                    ),
                                )
                            }
                        },
                    )
                }
                if (installed && !activeDownload) {
                    SettingsNavigationEntry(
                        title = stringResource(R.string.settings_ai_model_delete),
                        description = stringResource(R.string.settings_ai_model_delete_summary),
                        onClick = { deleteTarget = recommended.id to recommended.version },
                    )
                }
            }
        }

        SettingsSection(stringResource(R.string.settings_ai_job_section)) {
            val selectedModel = state.selectedInstalledModel()
            SettingsInfoEntry(
                title = selectedModel?.let { "${it.catalog.name} ${it.catalog.version}" }
                    ?: stringResource(R.string.settings_ai_job_no_model),
                description = when {
                    selectedModel == null -> stringResource(R.string.settings_ai_job_no_model_summary)
                    !selectedModel.executable -> stringResource(R.string.settings_ai_job_legacy_model_summary)
                    else -> stringResource(
                        R.string.settings_ai_job_model_ready_summary,
                        selectedModel.catalog.sampleRate,
                        selectedModel.catalog.segmentSamples / selectedModel.catalog.sampleRate.toDouble(),
                    )
                },
            )
            if (selectedModel?.catalog?.contract?.supportsDenoise == true) {
                SwitchRow(
                    label = stringResource(R.string.settings_ai_job_denoise),
                    checked = denoiseEnabled,
                ) { checked ->
                    denoiseEnabled = checked
                    AiSeparationPreferences.setDenoiseEnabled(context, checked)
                }
                SettingsInfoEntry(
                    title = stringResource(R.string.settings_ai_job_denoise_status),
                    description = stringResource(
                        if (denoiseEnabled) {
                            R.string.settings_ai_job_denoise_enabled_summary
                        } else {
                            R.string.settings_ai_job_denoise_disabled_summary
                        }
                    ),
                )
            }
            SwitchRow(
                label = stringResource(R.string.settings_ai_live_streaming),
                checked = liveStreamingEnabled,
            ) { checked ->
                liveStreamingEnabled = checked
                AiSeparationPreferences.setLiveStreamingEnabled(context, checked)
            }
            SettingsInfoEntry(
                title = stringResource(R.string.settings_ai_live_streaming_mode),
                description = stringResource(
                    if (liveStreamingEnabled) {
                        R.string.settings_ai_live_streaming_enabled_summary
                    } else {
                        R.string.settings_ai_live_streaming_disabled_summary
                    }
                ),
            )
            SettingsNavigationEntry(
                title = if (selectedAudioName.isBlank()) {
                    stringResource(R.string.settings_ai_job_choose_audio)
                } else {
                    selectedAudioName
                },
                description = stringResource(R.string.settings_ai_job_choose_audio_summary),
                onClick = { audioLauncher.launch(arrayOf("audio/*", "application/ogg")) },
            )
            if (jobProgress.active) {
                SettingsInfoEntry(
                    title = stringResource(
                        R.string.settings_ai_job_progress,
                        jobProgress.processedSegments,
                        jobProgress.totalSegments,
                    ),
                    description = buildJobProgressText(jobProgress),
                )
                if (
                    liveStreamingEnabled &&
                    liveStream.taskId == jobProgress.taskId &&
                    liveStream.ready
                ) {
                    SettingsInfoEntry(
                        title = stringResource(R.string.settings_ai_live_ready),
                        description = stringResource(
                            if (livePlayback.waitingForData) {
                                R.string.settings_ai_live_buffering_summary
                            } else {
                                R.string.settings_ai_live_position_summary
                            },
                            formatDuration(livePlayback.positionMs),
                            formatDuration(livePlayback.bufferedMs),
                        ),
                    )
                    SettingsNavigationEntry(
                        title = stringResource(
                            if (
                                livePlayback.playing &&
                                livePlayback.stem == AiSeparationStem.VOCALS
                            ) {
                                R.string.settings_ai_live_playing_vocals
                            } else {
                                R.string.settings_ai_live_play_vocals
                            }
                        ),
                        description = stringResource(R.string.settings_ai_live_play_vocals_summary),
                        onClick = {
                            showLivePlaybackResult(
                                context,
                                livePlayer.playLive(AiSeparationStem.VOCALS),
                            )
                        },
                    )
                    SettingsNavigationEntry(
                        title = stringResource(
                            if (
                                livePlayback.playing &&
                                livePlayback.stem == AiSeparationStem.INSTRUMENTAL
                            ) {
                                R.string.settings_ai_live_playing_instrumental
                            } else {
                                R.string.settings_ai_live_play_instrumental
                            }
                        ),
                        description = stringResource(
                            R.string.settings_ai_live_play_instrumental_summary
                        ),
                        onClick = {
                            showLivePlaybackResult(
                                context,
                                livePlayer.playLive(AiSeparationStem.INSTRUMENTAL),
                            )
                        },
                    )
                    if (livePlayback.taskId == jobProgress.taskId) {
                        SettingsNavigationEntry(
                            title = stringResource(
                                if (livePlayback.playing) {
                                    R.string.settings_ai_live_pause
                                } else {
                                    R.string.settings_ai_live_resume
                                }
                            ),
                            description = stringResource(R.string.settings_ai_live_pause_summary),
                            onClick = {
                                showLivePlaybackResult(context, livePlayer.toggle())
                            },
                        )
                        SettingsNavigationEntry(
                            title = stringResource(R.string.settings_ai_live_stop),
                            description = stringResource(R.string.settings_ai_live_stop_summary),
                            onClick = { livePlayer.stop() },
                        )
                    }
                }
                SettingsNavigationEntry(
                    title = stringResource(R.string.settings_ai_job_cancel),
                    description = stringResource(R.string.settings_ai_job_cancel_summary),
                    onClick = { AiSeparationJobService.cancel(context, jobProgress.taskId) },
                )
            } else {
                SettingsNavigationEntry(
                    title = stringResource(R.string.settings_ai_job_start),
                    description = stringResource(R.string.settings_ai_job_start_summary),
                    onClick = {
                        val uri = selectedAudioUri
                        val failure = when {
                            !runtimeStatus.onnxRuntimePresent -> context.getString(R.string.settings_ai_job_runtime_missing)
                            selectedModel == null -> context.getString(R.string.settings_ai_job_no_model)
                            !selectedModel.executable -> context.getString(R.string.settings_ai_job_legacy_model_summary)
                            uri == null -> context.getString(R.string.settings_ai_job_choose_audio_first)
                            else -> null
                        }
                        if (failure != null) {
                            AppNoticeBus.error(failure)
                        } else {
                            val result = AiSeparationJobService.start(context, uri!!, selectedAudioName)
                            if (result.isFailure) {
                                AppNoticeBus.error(result.exceptionOrNull()?.message.orEmpty())
                            }
                        }
                    },
                )
            }
        }

        SettingsSection(stringResource(R.string.settings_ai_repository_section)) {
            val repository = state.repository
            SettingsInfoEntry(
                title = repository?.name ?: stringResource(R.string.settings_ai_repository_none),
                description = if (repository == null) {
                    stringResource(R.string.settings_ai_repository_none_summary)
                } else {
                    stringResource(R.string.settings_ai_repository_status, repository.id, state.catalog.size)
                },
            )
            SettingsNavigationEntry(
                title = stringResource(R.string.settings_ai_repository_import),
                description = stringResource(R.string.settings_ai_repository_import_summary),
                onClick = { repositoryLauncher.launch(arrayOf("application/json", "text/json", "text/plain")) },
            )
            SettingsNavigationEntry(
                title = stringResource(R.string.settings_ai_repository_import_url),
                description = stringResource(R.string.settings_ai_repository_import_url_summary),
                onClick = { repositoryUrlDialog = true },
            )
            if (repository != null) {
                SettingsNavigationEntry(
                    title = stringResource(R.string.settings_ai_repository_refresh),
                    description = stringResource(R.string.settings_ai_repository_refresh_summary),
                    onClick = {
                        scope.launch {
                            val result = store.refreshCatalog()
                            showOperationNotice(
                                context = context,
                                success = result.isSuccess,
                                message = result.fold(
                                    onSuccess = { context.getString(R.string.settings_ai_repository_refreshed, it.size) },
                                    onFailure = { it.message ?: context.getString(R.string.settings_ai_repository_refresh_failed) },
                                ),
                            )
                        }
                    },
                )
                SettingsNavigationEntry(
                    title = stringResource(R.string.settings_ai_repository_remove),
                    description = stringResource(R.string.settings_ai_repository_remove_summary),
                    onClick = {
                        scope.launch {
                            store.removeRepository()
                            AppNoticeBus.post(
                                message = context.getString(R.string.settings_ai_repository_removed),
                                icon = AppNoticeIcon.AI,
                            )
                        }
                    },
                )
            }
        }

        SettingsSection(stringResource(R.string.settings_ai_models_section)) {
            val offlineModels = state.catalog.filterNot(AiFastVocalAlignmentModel::isCandidate)
            if (offlineModels.isEmpty()) {
                SettingsInfoEntry(
                    title = stringResource(R.string.settings_ai_models_empty),
                    description = stringResource(R.string.settings_ai_models_empty_summary),
                )
            } else {
                offlineModels.forEach { model ->
                    val installed = state.isInstalled(model)
                    val offlineSelected = state.isSelected(model)
                    val realtimeSelected = state.isRealtimeSelected(model)
                    val realtimeCapable = AiRecommendedModels.isRealtime(model)
                    val activeDownload = downloadProgress.active &&
                        downloadProgress.modelId == model.id && downloadProgress.modelVersion == model.version
                    val status = when {
                        activeDownload -> buildDownloadProgressText(downloadProgress.downloadedBytes, downloadProgress.totalBytes)
                        offlineSelected && realtimeSelected ->
                            "${stringResource(R.string.settings_ai_offline_selected)} · " +
                                stringResource(R.string.settings_ai_realtime_selected)
                        offlineSelected -> stringResource(R.string.settings_ai_offline_selected)
                        realtimeSelected -> stringResource(R.string.settings_ai_realtime_selected)
                        installed -> stringResource(R.string.settings_ai_model_installed)
                        else -> stringResource(R.string.settings_ai_model_not_installed)
                    }
                    val executableStatus = if (model.executable) {
                        stringResource(R.string.settings_ai_model_executable)
                    } else {
                        stringResource(R.string.settings_ai_model_storage_only)
                    }
                    SettingsInfoEntry(
                        title = "${model.name} ${model.version}",
                        description = stringResource(
                            R.string.settings_ai_model_summary,
                            "$status · $executableStatus",
                            formatBytes(model.archiveSizeBytes),
                            model.estimatedMemoryMb,
                            model.description,
                        ),
                    )
                    when {
                        activeDownload -> SettingsInfoEntry(
                            title = stringResource(R.string.settings_ai_model_downloading),
                            description = downloadProgress.message,
                        )
                        !installed -> SettingsNavigationEntry(
                            title = stringResource(R.string.settings_ai_model_download),
                            description = stringResource(R.string.settings_ai_model_download_summary),
                            onClick = { AiSeparationDownloadService.start(context, model.id, model.version) },
                        )
                        !offlineSelected -> SettingsNavigationEntry(
                            title = stringResource(R.string.settings_ai_select_offline_model),
                            description = stringResource(R.string.settings_ai_select_offline_model_summary),
                            onClick = {
                                scope.launch {
                                    val result = store.selectModel(model.id, model.version)
                                    showOperationNotice(
                                        context = context,
                                        success = result.isSuccess,
                                        message = result.fold(
                                            onSuccess = { context.getString(R.string.settings_ai_model_selected_toast) },
                                            onFailure = { it.message.orEmpty() },
                                        ),
                                    )
                                }
                            },
                        )
                    }
                    if (
                        installed && realtimeCapable && !realtimeSelected && !activeDownload
                    ) {
                        SettingsNavigationEntry(
                            title = stringResource(R.string.settings_ai_select_realtime_model),
                            description = stringResource(R.string.settings_ai_select_realtime_model_summary),
                            onClick = {
                                scope.launch {
                                    val result = store.selectRealtimeModel(model.id, model.version)
                                    showOperationNotice(
                                        context = context,
                                        success = result.isSuccess,
                                        message = result.fold(
                                            onSuccess = {
                                                context.getString(
                                                    R.string.settings_ai_realtime_selected_toast
                                                )
                                            },
                                            onFailure = { it.message.orEmpty() },
                                        ),
                                    )
                                }
                            },
                        )
                    }
                    if (installed && !activeDownload) {
                        SettingsNavigationEntry(
                            title = stringResource(R.string.settings_ai_model_delete),
                            description = stringResource(R.string.settings_ai_model_delete_summary),
                            onClick = { deleteTarget = model.id to model.version },
                        )
                    }
                }
            }
            SettingsNavigationEntry(
                title = stringResource(R.string.settings_ai_model_import_local),
                description = stringResource(R.string.settings_ai_model_import_local_summary),
                onClick = {
                    modelPackageLauncher.launch(
                        arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed")
                    )
                },
            )
        }

        SettingsSection(stringResource(R.string.settings_ai_melody_model_section)) {
            val recommended = AiRecommendedMelodyModels.RMVPE_Q8
            val activeDownload = downloadProgress.active &&
                downloadProgress.modelId == recommended.id &&
                downloadProgress.modelVersion == recommended.version
            val installed = melodyModelInstalled == true
            val status = when {
                activeDownload -> buildDownloadProgressText(
                    downloadProgress.downloadedBytes,
                    downloadProgress.totalBytes,
                )
                melodyModelInstalled == null -> stringResource(R.string.settings_ai_melody_model_checking)
                installed -> stringResource(R.string.settings_ai_melody_model_installed)
                else -> stringResource(R.string.settings_ai_melody_model_not_installed)
            }
            SettingsInfoEntry(
                title = "${recommended.displayName} ${recommended.version}",
                description = stringResource(
                    R.string.settings_ai_melody_model_summary,
                    status,
                    formatBytes(recommended.modelSizeBytes),
                ),
            )
            if (activeDownload) {
                SettingsInfoEntry(
                    title = stringResource(R.string.settings_ai_melody_model_downloading),
                    description = downloadProgress.message,
                )
            } else if (!installed) {
                SettingsNavigationEntry(
                    title = stringResource(R.string.settings_ai_melody_model_download),
                    description = stringResource(R.string.settings_ai_melody_model_download_summary),
                    onClick = {
                        AiSeparationDownloadService.startMelody(context)
                        AppNoticeBus.post(
                            message = context.getString(R.string.settings_ai_melody_model_download_started),
                            icon = AppNoticeIcon.DOWNLOAD,
                        )
                    },
                )
            } else {
                SettingsNavigationEntry(
                    title = stringResource(R.string.settings_ai_melody_model_delete),
                    description = stringResource(R.string.settings_ai_melody_model_delete_summary),
                    onClick = {
                        scope.launch {
                            val result = melodyModelStore.removeRecommended()
                            if (result.isSuccess) melodyModelRevision++
                            showOperationNotice(
                                context = context,
                                success = result.isSuccess,
                                message = result.fold(
                                    onSuccess = { context.getString(R.string.settings_ai_melody_model_deleted) },
                                    onFailure = { it.message.orEmpty() },
                                ),
                            )
                        }
                    },
                )
            }
        }

        SettingsSection(stringResource(R.string.settings_ai_piano_pack_section)) {
            val activeDownload = downloadProgress.active &&
                downloadProgress.modelId == AiRecommendedPianoPack.ID &&
                downloadProgress.modelVersion == AiRecommendedPianoPack.VERSION
            val installed = recommendedPianoInstalled == true
            val status = when {
                activeDownload -> buildDownloadProgressText(
                    downloadProgress.downloadedBytes,
                    downloadProgress.totalBytes,
                )
                recommendedPianoInstalled == null -> stringResource(R.string.settings_ai_piano_pack_checking)
                installed -> stringResource(R.string.settings_ai_piano_pack_installed)
                else -> stringResource(R.string.settings_ai_piano_pack_not_installed)
            }
            SettingsInfoEntry(
                title = "${AiRecommendedPianoPack.DISPLAY_NAME} ${AiRecommendedPianoPack.VERSION}",
                description = stringResource(
                    R.string.settings_ai_piano_pack_summary,
                    status,
                    formatBytes(AiRecommendedPianoPack.ESTIMATED_DOWNLOAD_BYTES),
                    formatBytes(AiRecommendedPianoPack.ESTIMATED_INSTALLED_BYTES),
                ),
            )
            if (activeDownload) {
                SettingsInfoEntry(
                    title = stringResource(R.string.settings_ai_piano_pack_downloading),
                    description = downloadProgress.message,
                )
            } else if (!installed) {
                SettingsNavigationEntry(
                    title = stringResource(R.string.settings_ai_piano_pack_download),
                    description = stringResource(R.string.settings_ai_piano_pack_download_summary),
                    onClick = {
                        AiSeparationDownloadService.startRecommendedPianoPack(context)
                        AppNoticeBus.post(
                            message = context.getString(R.string.settings_ai_piano_pack_download_started),
                            icon = AppNoticeIcon.DOWNLOAD,
                        )
                    },
                )
            } else {
                SettingsNavigationEntry(
                    title = stringResource(R.string.settings_ai_piano_pack_delete),
                    description = stringResource(R.string.settings_ai_piano_pack_delete_summary),
                    onClick = {
                        scope.launch {
                            val result = recommendedPianoInstaller.removeRecommended()
                            if (result.isSuccess) recommendedPianoRevision++
                            showOperationNotice(
                                context = context,
                                success = result.isSuccess,
                                message = result.fold(
                                    onSuccess = { context.getString(R.string.settings_ai_piano_pack_deleted) },
                                    onFailure = { it.message.orEmpty() },
                                ),
                            )
                        }
                    },
                )
            }
        }

        SettingsSection(stringResource(R.string.settings_ai_fast_alignment_section)) {
            val directBundleDownloadActive = downloadProgress.active &&
                downloadProgress.modelId == AiFastVocalAlignmentBundle.ID &&
                downloadProgress.modelVersion == AiFastVocalAlignmentBundle.VERSION
            val directBundleStatus = when {
                directBundleDownloadActive -> buildDownloadProgressText(
                    downloadProgress.downloadedBytes,
                    downloadProgress.totalBytes,
                )
                state.fastVocalAlignmentBundleInstalled ->
                    stringResource(R.string.settings_ai_fast_alignment_direct_installed)
                else -> stringResource(R.string.settings_ai_fast_alignment_direct_not_installed)
            }
            SettingsInfoEntry(
                title = stringResource(R.string.settings_ai_fast_alignment_direct_title),
                description = stringResource(
                    R.string.settings_ai_fast_alignment_direct_summary,
                    directBundleStatus,
                    formatBytes(AiFastVocalAlignmentBundle.ARCHIVE_SIZE_BYTES),
                ),
            )
            if (directBundleDownloadActive) {
                SettingsInfoEntry(
                    title = stringResource(R.string.settings_ai_fast_alignment_downloading),
                    description = downloadProgress.message,
                )
            } else if (!state.fastVocalAlignmentBundleInstalled) {
                SettingsNavigationEntry(
                    title = stringResource(R.string.settings_ai_fast_alignment_direct_download),
                    description = stringResource(
                        R.string.settings_ai_fast_alignment_direct_download_summary,
                    ),
                    onClick = { downloadFirstFastAlignmentModel() },
                )
            }
            val fastModels = state.catalog
                .filter(AiFastVocalAlignmentModel::isCandidate)
                .sortedWith(
                    compareBy<com.rawsmusic.separation.AiSeparationCatalogEntry> {
                        if (AiFastVocalAlignmentModel.isPreferred(it)) 0 else 1
                    }.thenBy { it.estimatedMemoryMb }
                        .thenBy { it.name },
                )
            if (fastModels.isEmpty() && !state.fastVocalAlignmentBundleInstalled) {
                SettingsInfoEntry(
                    title = stringResource(R.string.settings_ai_fast_alignment_empty),
                    description = stringResource(R.string.settings_ai_fast_alignment_empty_summary),
                )
                SettingsNavigationEntry(
                    title = stringResource(R.string.settings_ai_fast_alignment_manage_catalog),
                    description = stringResource(R.string.settings_ai_fast_alignment_manage_catalog_summary),
                    onClick = {
                        scope.launch {
                            val result = store.refreshCatalog()
                            showOperationNotice(
                                context = context,
                                success = result.isSuccess,
                                message = result.fold(
                                    onSuccess = {
                                        context.getString(R.string.settings_ai_repository_refreshed, it.size)
                                    },
                                    onFailure = {
                                        it.message ?: context.getString(
                                            R.string.settings_ai_repository_refresh_failed,
                                        )
                                    },
                                ),
                            )
                        }
                    },
                )
            } else {
                fastModels.forEach { model ->
                    val installed = state.isInstalled(model)
                    val selected = store.selectedFastVocalAlignmentModel()?.catalog?.let {
                        it.id == model.id && it.version == model.version
                    } == true
                    val activeDownload = downloadProgress.active &&
                        downloadProgress.modelId == model.id &&
                        downloadProgress.modelVersion == model.version
                    val status = when {
                        activeDownload -> buildDownloadProgressText(
                            downloadProgress.downloadedBytes,
                            downloadProgress.totalBytes,
                        )
                        selected -> stringResource(R.string.settings_ai_fast_alignment_selected)
                        installed -> stringResource(R.string.settings_ai_fast_alignment_installed)
                        else -> stringResource(R.string.settings_ai_fast_alignment_not_installed)
                    }
                    val preference = if (AiFastVocalAlignmentModel.isPreferred(model)) {
                        stringResource(R.string.settings_ai_fast_alignment_preferred)
                    } else {
                        stringResource(R.string.settings_ai_fast_alignment_compatible)
                    }
                    SettingsInfoEntry(
                        title = "${model.name} ${model.version}",
                        description = stringResource(
                            R.string.settings_ai_fast_alignment_summary,
                            status,
                            formatBytes(model.archiveSizeBytes),
                            model.estimatedMemoryMb,
                            preference,
                            model.description,
                        ),
                    )
                    when {
                        activeDownload -> SettingsInfoEntry(
                            title = stringResource(R.string.settings_ai_fast_alignment_downloading),
                            description = downloadProgress.message,
                        )
                        !installed -> SettingsNavigationEntry(
                            title = stringResource(R.string.settings_ai_fast_alignment_download),
                            description = stringResource(
                                R.string.settings_ai_fast_alignment_download_summary,
                            ),
                            onClick = {
                                AiSeparationDownloadService.start(
                                    context,
                                    model.id,
                                    model.version,
                                )
                            },
                        )
                    }
                    if (installed && !activeDownload) {
                        SettingsNavigationEntry(
                            title = stringResource(R.string.settings_ai_model_delete),
                            description = stringResource(R.string.settings_ai_model_delete_summary),
                            onClick = { deleteTarget = model.id to model.version },
                        )
                    }
                }
            }
        }

        SettingsSection(stringResource(R.string.settings_ai_lyric_alignment_section)) {
            if (state.lyricAlignmentCatalog.isEmpty()) {
                SettingsInfoEntry(
                    title = stringResource(R.string.settings_ai_lyric_alignment_empty),
                    description = stringResource(R.string.settings_ai_lyric_alignment_empty_summary),
                )
                SettingsNavigationEntry(
                    title = stringResource(R.string.settings_ai_lyric_alignment_download_one_click),
                    description = stringResource(
                        R.string.settings_ai_lyric_alignment_download_one_click_summary,
                    ),
                    onClick = { downloadFirstLyricAlignmentModel() },
                )
            } else {
                state.lyricAlignmentCatalog.forEach { model ->
                    val installed = state.isLyricAlignmentInstalled(model)
                    val selected = state.selectedLyricAlignmentId == model.id &&
                        state.selectedLyricAlignmentVersion == model.version
                    val activeDownload = downloadProgress.active &&
                        downloadProgress.modelId == model.id &&
                        downloadProgress.modelVersion == model.version
                    val status = when {
                        activeDownload -> buildDownloadProgressText(
                            downloadProgress.downloadedBytes,
                            downloadProgress.totalBytes,
                        )
                        selected -> stringResource(R.string.settings_ai_lyric_alignment_selected)
                        installed -> stringResource(R.string.settings_ai_lyric_alignment_installed)
                        else -> stringResource(R.string.settings_ai_lyric_alignment_not_installed)
                    }
                    SettingsInfoEntry(
                        title = "${model.name} ${model.version}",
                        description = stringResource(
                            R.string.settings_ai_lyric_alignment_summary,
                            status,
                            formatBytes(model.modelSizeBytes + model.vocabularySizeBytes),
                            model.estimatedMemoryMb,
                            model.description,
                        ),
                    )
                    when {
                        activeDownload -> SettingsInfoEntry(
                            title = stringResource(R.string.settings_ai_lyric_alignment_downloading),
                            description = downloadProgress.message,
                        )
                        !installed -> SettingsNavigationEntry(
                            title = stringResource(R.string.settings_ai_lyric_alignment_download),
                            description = stringResource(
                                R.string.settings_ai_lyric_alignment_download_summary,
                            ),
                            onClick = {
                                AiSeparationDownloadService.startLyricAlignment(
                                    context,
                                    model.id,
                                    model.version,
                                )
                            },
                        )
                        !selected -> SettingsNavigationEntry(
                            title = stringResource(R.string.settings_ai_lyric_alignment_select),
                            description = stringResource(
                                R.string.settings_ai_lyric_alignment_select_summary,
                            ),
                            onClick = {
                                scope.launch {
                                    val result = store.selectLyricAlignmentModel(model.id, model.version)
                                    showOperationNotice(
                                        context = context,
                                        success = result.isSuccess,
                                        message = result.fold(
                                            onSuccess = {
                                                context.getString(
                                                    R.string.settings_ai_lyric_alignment_selected_toast,
                                                )
                                            },
                                            onFailure = { it.message.orEmpty() },
                                        ),
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }

        SettingsSection(stringResource(R.string.settings_ai_results_section)) {
            if (results.isEmpty()) {
                SettingsInfoEntry(
                    title = stringResource(R.string.settings_ai_results_empty),
                    description = stringResource(R.string.settings_ai_results_empty_summary),
                )
            } else {
                results.forEach { result ->
                    SettingsInfoEntry(
                        title = result.sourceName,
                        description = stringResource(
                            R.string.settings_ai_result_summary,
                            result.modelName,
                            result.modelVersion,
                            result.elapsedMs / 1000.0,
                            formatBytes(result.vocalsFile.length() + result.instrumentalFile.length()),
                        ),
                    )
                    SettingsNavigationEntry(
                        title = stringResource(R.string.settings_ai_result_play_vocals),
                        description = stringResource(R.string.settings_ai_result_play_vocals_summary),
                        onClick = {
                            showLivePlaybackResult(
                                context,
                                livePlayer.playResult(result, AiSeparationStem.VOCALS),
                            )
                        },
                    )
                    SettingsNavigationEntry(
                        title = stringResource(R.string.settings_ai_result_play_instrumental),
                        description = stringResource(R.string.settings_ai_result_play_instrumental_summary),
                        onClick = {
                            showLivePlaybackResult(
                                context,
                                livePlayer.playResult(result, AiSeparationStem.INSTRUMENTAL),
                            )
                        },
                    )
                    if (livePlayback.taskId == "result:${result.id}") {
                        SettingsNavigationEntry(
                            title = stringResource(
                                if (livePlayback.playing) {
                                    R.string.settings_ai_live_pause
                                } else {
                                    R.string.settings_ai_live_resume
                                }
                            ),
                            description = stringResource(R.string.settings_ai_live_pause_summary),
                            onClick = {
                                showLivePlaybackResult(context, livePlayer.toggle())
                            },
                        )
                    }
                    SettingsNavigationEntry(
                        title = stringResource(R.string.settings_ai_result_delete),
                        description = stringResource(R.string.settings_ai_result_delete_summary),
                        onClick = { deleteResultTarget = result.id },
                    )
                }
                SettingsNavigationEntry(
                    title = stringResource(R.string.settings_ai_results_clear),
                    description = stringResource(R.string.settings_ai_results_clear_summary),
                    onClick = {
                        scope.launch {
                            livePlayer.stop()
                            val result = resultStore.clear()
                            showOperationNotice(
                                context = context,
                                success = result.isSuccess,
                                message = result.fold(
                                    onSuccess = { context.getString(R.string.settings_ai_results_cleared) },
                                    onFailure = { it.message.orEmpty() },
                                ),
                            )
                        }
                    },
                )
            }
        }

        SettingsSection(stringResource(R.string.settings_ai_storage_section)) {
            val installedBytes = state.installed.sumOf { it.catalog.modelSizeBytes }
            SettingsInfoEntry(
                title = stringResource(R.string.settings_ai_storage_used, formatBytes(installedBytes)),
                description = stringResource(R.string.settings_ai_storage_summary, state.installed.size),
            )
            if (state.installed.isNotEmpty()) {
                SettingsNavigationEntry(
                    title = stringResource(R.string.settings_ai_storage_clear),
                    description = stringResource(R.string.settings_ai_storage_clear_summary),
                    onClick = {
                        scope.launch {
                            val result = store.clearAllModels()
                            showOperationNotice(
                                context = context,
                                success = result.isSuccess,
                                message = result.fold(
                                    onSuccess = { context.getString(R.string.settings_ai_storage_cleared) },
                                    onFailure = { it.message.orEmpty() },
                                ),
                            )
                        }
                    },
                )
            }
        }

        if (state.lastError.isNotBlank()) {
            SettingsSection(stringResource(R.string.settings_ai_error_section)) {
                SettingsInfoEntry(
                    title = stringResource(R.string.settings_ai_last_error),
                    description = state.lastError,
                )
            }
        }
    }

    if (repositoryUrlDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { repositoryUrlDialog = false },
            title = {
                androidx.compose.material3.Text(
                    stringResource(R.string.settings_ai_repository_import_url_title),
                )
            },
            text = {
                androidx.compose.foundation.layout.Column {
                    androidx.compose.material3.Text(
                        stringResource(R.string.settings_ai_repository_import_url_summary),
                    )
                    androidx.compose.material3.OutlinedTextField(
                        value = repositoryUrl,
                        onValueChange = { repositoryUrl = it },
                        label = {
                            androidx.compose.material3.Text(
                                stringResource(R.string.settings_ai_repository_import_url_label),
                            )
                        },
                        placeholder = {
                            androidx.compose.material3.Text(
                                stringResource(R.string.settings_ai_repository_import_url_hint),
                            )
                        },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    enabled = repositoryUrl.trim().startsWith("https://"),
                    onClick = {
                        val url = repositoryUrl.trim()
                        repositoryUrlDialog = false
                        repositoryUrl = ""
                        scope.launch {
                            val result = store.importRepositoryFromUrl(url)
                            showOperationNotice(
                                context = context,
                                success = result.isSuccess,
                                message = repositoryImportMessage(result),
                            )
                        }
                    },
                ) {
                    androidx.compose.material3.Text(
                        stringResource(R.string.settings_ai_repository_import_url_confirm),
                    )
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(
                    onClick = { repositoryUrlDialog = false },
                ) {
                    androidx.compose.material3.Text(stringResource(R.string.settings_cancel))
                }
            },
        )
    }

    deleteTarget?.let { (id, version) ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { androidx.compose.material3.Text(stringResource(R.string.settings_ai_model_delete_confirm_title)) },
            text = { androidx.compose.material3.Text(stringResource(R.string.settings_ai_model_delete_confirm_summary)) },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        deleteTarget = null
                        scope.launch {
                            val result = store.removeModel(id, version)
                            showOperationNotice(
                                context = context,
                                success = result.isSuccess,
                                message = result.fold(
                                    onSuccess = { context.getString(R.string.settings_ai_model_deleted) },
                                    onFailure = { it.message.orEmpty() },
                                ),
                            )
                        }
                    }
                ) { androidx.compose.material3.Text(stringResource(R.string.settings_ai_model_delete)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { deleteTarget = null }) {
                    androidx.compose.material3.Text(stringResource(R.string.settings_cancel))
                }
            },
        )
    }

    deleteResultTarget?.let { id ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { deleteResultTarget = null },
            title = { androidx.compose.material3.Text(stringResource(R.string.settings_ai_result_delete_confirm_title)) },
            text = { androidx.compose.material3.Text(stringResource(R.string.settings_ai_result_delete_confirm_summary)) },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        deleteResultTarget = null
                        scope.launch {
                            if (livePlayback.taskId == "result:$id") {
                                livePlayer.stop()
                            }
                            resultStore.remove(id)
                        }
                    }
                ) { androidx.compose.material3.Text(stringResource(R.string.settings_ai_result_delete)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { deleteResultTarget = null }) {
                    androidx.compose.material3.Text(stringResource(R.string.settings_cancel))
                }
            },
        )
    }
}

private fun queryDisplayName(context: android.content.Context, uri: Uri): String = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else ""
    }.orEmpty()
}.getOrDefault("")

private fun showLivePlaybackResult(context: android.content.Context, result: Result<Unit>) {
    result.exceptionOrNull()?.let { error ->
        AppNoticeBus.error(
            error.message ?: context.getString(R.string.settings_ai_live_failed)
        )
    }
}

private fun showOperationNotice(
    context: android.content.Context,
    success: Boolean,
    message: String,
) {
    if (success) {
        AppNoticeBus.post(
            message = message,
            icon = AppNoticeIcon.AI,
        )
    } else {
        AppNoticeBus.error(message)
    }
}

private fun buildJobProgressText(progress: com.rawsmusic.separation.AiSeparationJobProgress): String {
    val percent = if (progress.totalFrames > 0L) "%.1f%%".format(progress.fraction * 100f) else "--"
    val rtf = if (progress.realtimeFactor > 0.0) " · %.2f× 实时".format(progress.realtimeFactor) else ""
    return "$percent$rtf · ${progress.message}"
}

private fun buildDownloadProgressText(downloaded: Long, total: Long): String = if (total > 0L) {
    "${formatBytes(downloaded)} / ${formatBytes(total)}"
} else {
    "准备中"
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> String.format("%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)
    bytes >= 1024L * 1024L -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> String.format("%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}

private fun formatDuration(milliseconds: Long): String {
    val totalSeconds = milliseconds.coerceAtLeast(0L) / 1000L
    return "%d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
}
