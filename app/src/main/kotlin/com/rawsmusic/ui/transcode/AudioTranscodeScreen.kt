package com.rawsmusic.ui.transcode

import android.content.Context
import android.content.Intent
import android.widget.Toast
import android.os.Environment
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.content.FileProvider
import androidx.annotation.StringRes
import com.rawsmusic.core.common.ui.AppNoticeBus
import com.rawsmusic.core.common.ui.AppNoticeIcon
import com.rawsmusic.R
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.utils.CjkSortUtils
import com.rawsmusic.core.ui.widget.RawMiuixOverlayDialog
import com.rawsmusic.core.ui.widget.RawWindowDropdownPreference
import com.rawsmusic.core.ui.widget.background.CustomMediaBackgroundState
import com.rawsmusic.core.ui.widget.bitmaps.ArtworkSurface
import com.rawsmusic.core.ui.widget.bitmaps.BitmapImage
import com.rawsmusic.core.ui.widget.bitmaps.resolvePlaybackArtworkKey
import com.rawsmusic.locale.localizedErrorMessage
import com.rawsmusic.transcode.AudioTranscodeCapability
import com.rawsmusic.transcode.AudioTranscodeDsdRate
import com.rawsmusic.transcode.AudioTranscodeFailureReason
import com.rawsmusic.transcode.AudioTranscodeForegroundService
import com.rawsmusic.transcode.AudioTranscodeFormat
import com.rawsmusic.transcode.AudioTranscodeManager
import com.rawsmusic.transcode.AudioTranscodeMetadataPolicy
import com.rawsmusic.transcode.AudioTranscodeQueue
import com.rawsmusic.transcode.AudioTranscodeQueueEntry
import com.rawsmusic.transcode.AudioTranscodeQueueState
import com.rawsmusic.transcode.AudioTranscodeRequest
import com.rawsmusic.transcode.AudioTranscodeResult
import com.rawsmusic.transcode.AudioTranscodeStage
import com.rawsmusic.transcode.AudioTranscodeVerificationMode
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

private enum class TranscodeTab(@StringRes val labelRes: Int) {
    SETTINGS(R.string.transcode_tab_settings),
    QUEUE(R.string.transcode_tab_queue),
    RESULTS(R.string.transcode_tab_results),
}

private enum class LibraryPickerSort(@StringRes val labelRes: Int) {
    LOSSLESS(R.string.transcode_sort_lossless),
    LOSSY(R.string.transcode_sort_lossy),
    DSD(R.string.transcode_sort_dsd),
    BIT_DEPTH(R.string.transcode_sort_bit_depth),
    SAMPLE_RATE(R.string.transcode_sort_sample_rate),
}

private data class TranscodeUiOptions(
    val format: AudioTranscodeFormat = AudioTranscodeFormat.FLAC,
    /** null = follow source whenever the runtime profile supports it. */
    val sampleRateHz: Int? = null,
    /** null = follow source. Ignored by DSD/lossy codecs. */
    val bitDepth: Int? = null,
    /** null = runtime recommended bitrate. */
    val bitRateKbps: Int? = null,
    val dsdRate: AudioTranscodeDsdRate = AudioTranscodeDsdRate.DSD64,
    val flacCompressionLevel: Int = 8,
    val strictMetadata: Boolean = true,
    val hardwareAcceleration: Boolean = false,
    val deleteSourceOnSuccess: Boolean = false,
    val autoScanOnSuccess: Boolean = false,
)

private object TranscodeUiOptionsStore {
    private const val PREFS = "audio_transcode_ui_options"
    private const val KEY_FORMAT = "format"
    private const val KEY_SAMPLE_RATE = "sample_rate_hz"
    private const val KEY_BIT_DEPTH = "bit_depth"
    private const val KEY_BIT_RATE = "bit_rate_kbps"
    private const val KEY_DSD_RATE = "dsd_rate"
    private const val KEY_FLAC_LEVEL = "flac_compression_level"
    private const val KEY_STRICT_METADATA = "strict_metadata"
    private const val KEY_HARDWARE_ACCELERATION = "hardware_acceleration"
    private const val KEY_DELETE_SOURCE = "delete_source_on_success"
    private const val KEY_AUTO_SCAN = "auto_scan_on_success"
    private const val NULL_INT = Int.MIN_VALUE

    fun load(context: Context): TranscodeUiOptions {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        fun nullableInt(key: String): Int? =
            prefs.getInt(key, NULL_INT).takeUnless { it == NULL_INT }
        return TranscodeUiOptions(
            format = runCatching {
                AudioTranscodeFormat.valueOf(prefs.getString(KEY_FORMAT, null).orEmpty())
            }.getOrDefault(AudioTranscodeFormat.FLAC),
            sampleRateHz = nullableInt(KEY_SAMPLE_RATE),
            bitDepth = nullableInt(KEY_BIT_DEPTH),
            bitRateKbps = nullableInt(KEY_BIT_RATE),
            dsdRate = runCatching {
                AudioTranscodeDsdRate.valueOf(prefs.getString(KEY_DSD_RATE, null).orEmpty())
            }.getOrDefault(AudioTranscodeDsdRate.DSD64),
            flacCompressionLevel = prefs.getInt(KEY_FLAC_LEVEL, 8).coerceIn(0, 12),
            strictMetadata = prefs.getBoolean(KEY_STRICT_METADATA, true),
            hardwareAcceleration = prefs.getBoolean(KEY_HARDWARE_ACCELERATION, false),
            deleteSourceOnSuccess = prefs.getBoolean(KEY_DELETE_SOURCE, false),
            autoScanOnSuccess = prefs.getBoolean(KEY_AUTO_SCAN, false),
        )
    }

    fun save(context: Context, options: TranscodeUiOptions) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_FORMAT, options.format.name)
            .putInt(KEY_SAMPLE_RATE, options.sampleRateHz ?: NULL_INT)
            .putInt(KEY_BIT_DEPTH, options.bitDepth ?: NULL_INT)
            .putInt(KEY_BIT_RATE, options.bitRateKbps ?: NULL_INT)
            .putString(KEY_DSD_RATE, options.dsdRate.name)
            .putInt(KEY_FLAC_LEVEL, options.flacCompressionLevel.coerceIn(0, 12))
            .putBoolean(KEY_STRICT_METADATA, options.strictMetadata)
            .putBoolean(KEY_HARDWARE_ACCELERATION, options.hardwareAcceleration)
            .putBoolean(KEY_DELETE_SOURCE, options.deleteSourceOnSuccess)
            .putBoolean(KEY_AUTO_SCAN, options.autoScanOnSuccess)
            .apply()
    }
}

private data class TranscodeDraftItem(
    val sourcePath: String,
    val title: String,
    val artist: String,
    val sourceFormat: String,
    val sampleRateHz: Int,
    val bitDepth: Int,
    val channels: Int,
    val fileSize: Long,
    val artworkKey: String,
    val selected: Boolean = true,
    val overrideOptions: TranscodeUiOptions? = null,
)

@Composable
fun AudioTranscodeScreen(
    librarySongs: List<AudioFile>,
    initialPaths: List<String>,
    importedPaths: List<String>,
    onImportedPathsConsumed: () -> Unit,
    hasDirectOutputStorageAccess: Boolean,
    onRequestDirectOutputStorageAccess: () -> Unit,
    onPickExternalFiles: () -> Unit,
    onAuthorizeSourceDelete: (taskId: String, sourcePath: String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val ensureDirectOutputStorageAccess: () -> Boolean = {
        if (hasDirectOutputStorageAccess) {
            true
        } else {
            onRequestDirectOutputStorageAccess()
            AppNoticeBus.error(context.getString(R.string.transcode_storage_permission_required))
            false
        }
    }
    CustomMediaBackgroundState.ensureInitialized(context)
    @Suppress("UNUSED_VARIABLE")
    val customBackgroundRevision = CustomMediaBackgroundState.revision
    val isDark = MiuixTheme.colorScheme.background.luminance() < 0.5f
    val settingsBackground = if (isDark) Color(0xFF101014) else Color(0xFFF4F4F7)
    val pageBackground = if (
        CustomMediaBackgroundState.enabled && CustomMediaBackgroundState.showOnSettings
    ) {
        Color.Transparent
    } else {
        settingsBackground
    }
    val importedSourceRoot = remember(context) {
        File(context.cacheDir, "transcode-import").absolutePath
    }
    val importedOutputDir = remember {
        File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            "RawSMusic/Converted",
        )
    }
    val drafts = remember { mutableStateListOf<TranscodeDraftItem>() }
    val pagerState = rememberPagerState(
        initialPage = TranscodeTab.SETTINGS.ordinal,
        pageCount = { TranscodeTab.entries.size },
    )
    val pagerScope = rememberCoroutineScope()
    val tab = TranscodeTab.entries[pagerState.currentPage.coerceIn(0, TranscodeTab.entries.lastIndex)]
    val openTab: (TranscodeTab) -> Unit = { destination ->
        pagerScope.launch {
            pagerState.animateScrollToPage(destination.ordinal)
        }
    }
    var showLibraryPicker by remember { mutableStateOf(false) }
    var editingPath by remember { mutableStateOf<String?>(null) }
    var globalOptions by remember(context) {
        mutableStateOf(TranscodeUiOptionsStore.load(context))
    }
    var capabilities by remember { mutableStateOf<List<AudioTranscodeCapability>>(emptyList()) }
    val queueEntries by AudioTranscodeQueue.entries.collectAsState()

    val libraryByPath = remember(librarySongs) { librarySongs.associateBy(AudioFile::path) }

    LaunchedEffect(Unit) {
        capabilities = withContext(Dispatchers.IO) { AudioTranscodeManager.capabilities() }
    }

    LaunchedEffect(capabilities) {
        if (capabilities.isEmpty()) return@LaunchedEffect
        val supported = supportedFormats(capabilities)
        if (globalOptions.format !in supported && supported.isNotEmpty()) {
            globalOptions = globalOptions.copy(format = supported.first())
        }
        if (globalOptions.format == AudioTranscodeFormat.SACD_ISO && globalOptions.dsdRate != AudioTranscodeDsdRate.DSD64) {
            globalOptions = globalOptions.copy(dsdRate = AudioTranscodeDsdRate.DSD64)
        }
    }

    LaunchedEffect(globalOptions) {
        TranscodeUiOptionsStore.save(context, globalOptions)
    }

    LaunchedEffect(initialPaths, libraryByPath) {
        if (initialPaths.isEmpty()) return@LaunchedEffect
        val missing = initialPaths.distinct().filter { path -> drafts.none { it.sourcePath == path } }
        if (missing.isEmpty()) return@LaunchedEffect
        val resolved = withContext(Dispatchers.IO) {
            missing.mapNotNull { path ->
                libraryByPath[path]?.toDraftItem() ?: probeDraft(path)
            }
        }
        resolved.forEach { item ->
            if (drafts.none { it.sourcePath == item.sourcePath }) drafts += item
        }
    }

    LaunchedEffect(importedPaths) {
        if (importedPaths.isEmpty()) return@LaunchedEffect
        val missing = importedPaths.distinct().filter { path -> drafts.none { it.sourcePath == path } }
        val resolved = withContext(Dispatchers.IO) { missing.mapNotNull(::probeDraft) }
        resolved.forEach { item -> if (drafts.none { it.sourcePath == item.sourcePath }) drafts += item }
        onImportedPathsConsumed()
        if (missing.isNotEmpty() && resolved.isEmpty()) {
            AppNoticeBus.error(context.getString(R.string.transcode_no_usable_audio_stream))
        }
    }

    val selectedDrafts = drafts.filter(TranscodeDraftItem::selected)
    val selectedBytes = selectedDrafts.sumOf(TranscodeDraftItem::fileSize)
    val currentCapability = capabilityFor(globalOptions.format, capabilities)
    val editingItem = editingPath?.let { path -> drafts.firstOrNull { it.sourcePath == path } }
    val activeQueueCount = queueEntries.count {
        it.state == AudioTranscodeQueueState.QUEUED || it.state == AudioTranscodeQueueState.RUNNING
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(pageBackground),
    ) {
        SmallTopAppBar(
            title = stringResource(R.string.transcode_screen_title),
            color = pageBackground,
            titleColor = MiuixTheme.colorScheme.onBackground,
            navigationIcon = {
                IconButton(onClick = {
                    onBack()
                }) {
                    Icon(
                        imageVector = MiuixIcons.Regular.Back,
                        contentDescription = stringResource(R.string.common_back),
                        tint = MiuixTheme.colorScheme.onSurface,
                    )
                }
            },
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 0,
            ) { page ->
                val pageTab = TranscodeTab.entries[page]
                when (pageTab) {
                    TranscodeTab.SETTINGS -> {
                        Box(modifier = Modifier.fillMaxSize()) {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                    start = 14.dp,
                                    end = 14.dp,
                                    // Keep the first row below the floating entry. While scrolling,
                                    // content naturally passes behind the feather below it.
                                    top = 106.dp,
                                    // Reserve the complete feather + floating action island area so the
                                    // last preview row can scroll fully above the fixed bottom controls.
                                    bottom = 142.dp,
                                ),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                item {
                                    SourceSummaryCard(
                                        drafts = drafts,
                                        selectedCount = selectedDrafts.size,
                                        selectedBytes = selectedBytes,
                                        onOpenLibrary = { showLibraryPicker = true },
                                        onPickFiles = onPickExternalFiles,
                                    )
                                }
                                item {
                                    OptionsPanel(
                                        title = stringResource(
                                            if (selectedDrafts.size > 1) {
                                                R.string.transcode_batch_settings
                                            } else {
                                                R.string.transcode_settings
                                            }
                                        ),
                                        options = globalOptions,
                                        capabilities = capabilities,
                                        onOptions = { globalOptions = it },
                                    )
                                }
                                item {
                                    BatchPreviewCard(
                                        selected = selectedDrafts,
                                        options = globalOptions,
                                        overriddenCount = selectedDrafts.count { it.overrideOptions != null },
                                    )
                                }
                            }

                            BottomBatchActions(
                                modifier = Modifier.align(Alignment.BottomCenter),
                                selectedCount = selectedDrafts.size,
                                fixedAreaColor = settingsBackground,
                                onConvert = {
                                    if (ensureDirectOutputStorageAccess()) {
                                        val selectedPaths = selectedDrafts.mapTo(hashSetOf()) { it.sourcePath }
                                        val requests = buildRequests(
                                            selectedDrafts,
                                            globalOptions,
                                            importedSourceRoot,
                                            importedOutputDir,
                                        )
                                        if (requests.isEmpty()) {
                                            AppNoticeBus.error(context.getString(R.string.transcode_select_audio_first))
                                        } else {
                                            AudioTranscodeForegroundService.enqueueAllNow(context, requests).fold(
                                                onSuccess = {
                                                    drafts.removeAll { it.sourcePath in selectedPaths }
                                                    AppNoticeBus.post(
                                                        message = context.getString(R.string.transcode_started_count, it.size),
                                                        icon = AppNoticeIcon.TRANSCODE,
                                                    )
                                                    openTab(TranscodeTab.QUEUE)
                                                },
                                                onFailure = {
                                                    AppNoticeBus.error(
                                                        context.localizedErrorMessage(it, R.string.transcode_service_start_failed)
                                                    )
                                                },
                                            )
                                        }
                                    }
                                },
                            )
                        }
                    }

                    TranscodeTab.QUEUE, TranscodeTab.RESULTS -> {
                        QueueManagementPage(
                            modifier = Modifier.fillMaxSize(),
                            drafts = if (pageTab == TranscodeTab.RESULTS) emptyList() else drafts,
                            queueEntries = queueEntries.filter {
                                val active = it.state == AudioTranscodeQueueState.QUEUED ||
                                    it.state == AudioTranscodeQueueState.RUNNING
                                if (pageTab == TranscodeTab.RESULTS) !active else active
                            },
                            resultsOnly = pageTab == TranscodeTab.RESULTS,
                            globalOptions = globalOptions,
                            fixedAreaColor = settingsBackground,
                            onToggleDraft = { path ->
                                drafts.indexOfFirst { it.sourcePath == path }.takeIf { it >= 0 }?.let { index ->
                                    drafts[index] = drafts[index].copy(selected = !drafts[index].selected)
                                }
                            },
                            onEditDraft = { editingPath = it },
                            onRemoveDraft = { path -> drafts.removeAll { it.sourcePath == path } },
                            onConvertDraft = { path ->
                                if (!ensureDirectOutputStorageAccess()) return@QueueManagementPage
                                val item = drafts.firstOrNull { it.sourcePath == path } ?: return@QueueManagementPage
                                val request = buildRequests(
                                    listOf(item),
                                    globalOptions,
                                    importedSourceRoot,
                                    importedOutputDir,
                                ).firstOrNull()
                                    ?: return@QueueManagementPage
                                AudioTranscodeForegroundService.enqueueNow(context, request).fold(
                                    onSuccess = {
                                        drafts.removeAll { it.sourcePath == path }
                                        AppNoticeBus.post(
                                            message = context.getString(R.string.transcode_started_priority),
                                            icon = AppNoticeIcon.TRANSCODE,
                                        )
                                    },
                                    onFailure = {
                                        AppNoticeBus.error(
                                            context.localizedErrorMessage(it, R.string.transcode_service_start_failed)
                                        )
                                    },
                                )
                            },
                            onQueueDraft = { path ->
                                if (!ensureDirectOutputStorageAccess()) return@QueueManagementPage
                                val item = drafts.firstOrNull { it.sourcePath == path } ?: return@QueueManagementPage
                                val request = buildRequests(
                                    listOf(item),
                                    globalOptions,
                                    importedSourceRoot,
                                    importedOutputDir,
                                ).firstOrNull() ?: return@QueueManagementPage
                                AudioTranscodeForegroundService.enqueue(context, request).fold(
                                    onSuccess = {
                                        drafts.removeAll { it.sourcePath == path }
                                        AppNoticeBus.post(
                                            message = context.getString(R.string.transcode_added_to_queue),
                                            icon = AppNoticeIcon.QUEUE,
                                        )
                                    },
                                    onFailure = {
                                        AppNoticeBus.error(
                                            context.localizedErrorMessage(it, R.string.transcode_queue_add_failed)
                                        )
                                    },
                                )
                            },
                            onSelectAllDrafts = {
                                val shouldSelect = drafts.any { !it.selected }
                                drafts.indices.forEach { index -> drafts[index] = drafts[index].copy(selected = shouldSelect) }
                            },
                            onRemoveSelectedDrafts = { drafts.removeAll(TranscodeDraftItem::selected) },
                            onQueueSelectedDrafts = {
                                if (!ensureDirectOutputStorageAccess()) return@QueueManagementPage
                                val selected = drafts.filter(TranscodeDraftItem::selected)
                                val selectedPaths = selected.mapTo(hashSetOf()) { it.sourcePath }
                                val requests = buildRequests(
                                    selected,
                                    globalOptions,
                                    importedSourceRoot,
                                    importedOutputDir,
                                )
                                if (requests.isNotEmpty()) {
                                    AudioTranscodeForegroundService.enqueueAll(context, requests).fold(
                                        onSuccess = {
                                            drafts.removeAll { it.sourcePath in selectedPaths }
                                            AppNoticeBus.post(
                                                message = context.getString(R.string.transcode_added_to_queue_count, it.size),
                                                icon = AppNoticeIcon.QUEUE,
                                            )
                                        },
                                        onFailure = {
                                            AppNoticeBus.error(
                                                context.localizedErrorMessage(it, R.string.transcode_queue_add_failed)
                                            )
                                        },
                                    )
                                }
                            },
                            onConvertSelectedDrafts = {
                                if (!ensureDirectOutputStorageAccess()) return@QueueManagementPage
                                val selected = drafts.filter(TranscodeDraftItem::selected)
                                val selectedPaths = selected.mapTo(hashSetOf()) { it.sourcePath }
                                val requests = buildRequests(
                                    selected,
                                    globalOptions,
                                    importedSourceRoot,
                                    importedOutputDir,
                                )
                                if (requests.isNotEmpty()) {
                                    AudioTranscodeForegroundService.enqueueAllNow(context, requests).fold(
                                        onSuccess = {
                                            drafts.removeAll { it.sourcePath in selectedPaths }
                                            AppNoticeBus.post(
                                                message = context.getString(R.string.transcode_started_priority_count, it.size),
                                                icon = AppNoticeIcon.TRANSCODE,
                                            )
                                        },
                                        onFailure = {
                                            AppNoticeBus.error(
                                                context.localizedErrorMessage(it, R.string.transcode_service_start_failed)
                                            )
                                        },
                                    )
                                }
                            },
                            onCancelQueue = { id -> AudioTranscodeForegroundService.cancel(context, id) },
                            onRetryQueue = { id ->
                                if (ensureDirectOutputStorageAccess()) {
                                    AudioTranscodeQueue.retry(id)?.let { AudioTranscodeForegroundService.sync(context) }
                                }
                            },
                            onRemoveQueue = AudioTranscodeQueue::remove,
                            onMoveQueue = AudioTranscodeQueue::moveQueued,
                            onClearFinished = AudioTranscodeQueue::clearFinished,
                            onAuthorizeSourceDelete = onAuthorizeSourceDelete,
                            onAddLibrary = { showLibraryPicker = true },
                            onAddFiles = onPickExternalFiles,
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .zIndex(2f),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(settingsBackground),
                ) {
                    TranscodeNavigation(tab = tab, onTab = openTab)
                }
                TranscodeNavigationFeather(baseColor = settingsBackground)
            }
        }
    }

    if (showLibraryPicker) {
        LibraryPickerDialog(
            songs = librarySongs,
            existingPaths = drafts.mapTo(hashSetOf()) { it.sourcePath },
            onDismiss = { showLibraryPicker = false },
            onConfirm = { songs ->
                songs.forEach { song ->
                    if (drafts.none { it.sourcePath == song.path }) drafts += song.toDraftItem()
                }
                showLibraryPicker = false
            },
        )
    }

    if (editingItem != null) {
        val initial = editingItem.overrideOptions ?: globalOptions
        IndividualOptionsDialog(
            title = editingItem.title,
            initial = initial,
            capabilities = capabilities,
            isOverride = editingItem.overrideOptions != null,
            onDismiss = { editingPath = null },
            onUseBatch = {
                val index = drafts.indexOfFirst { it.sourcePath == editingItem.sourcePath }
                if (index >= 0) drafts[index] = drafts[index].copy(overrideOptions = null)
                editingPath = null
            },
            onSave = { options ->
                val index = drafts.indexOfFirst { it.sourcePath == editingItem.sourcePath }
                if (index >= 0) drafts[index] = drafts[index].copy(overrideOptions = options)
                editingPath = null
            },
        )
    }
}

@Composable
private fun TranscodeNavigation(tab: TranscodeTab, onTab: (TranscodeTab) -> Unit) {
    val colors = MiuixTheme.colorScheme
    Surface(
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
            .fillMaxWidth().shadow(8.dp, RoundedCornerShape(30.dp)),
        shape = RoundedCornerShape(30.dp),
        color = colors.surfaceContainerHigh.copy(alpha = 0.96f),
    ) {
        Row(Modifier.padding(5.dp).selectableGroup()) {
            TranscodeTab.entries.forEach { destination ->
                val selected = tab == destination
                val background by androidx.compose.animation.animateColorAsState(
                    if (selected) colors.surface else Color.Transparent,
                    label = "transcode_tab",
                )
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(25.dp))
                        .background(background)
                        .selectable(selected, role = Role.Tab, onClick = { onTab(destination) })
                        .padding(vertical = 15.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(destination.labelRes), fontSize = 14.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) colors.primary else colors.onSurfaceVariantSummary)
                }
            }
        }
    }
}

@Composable
private fun TranscodeNavigationFeather(baseColor: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .background(
                Brush.verticalGradient(
                    listOf(
                        baseColor.copy(alpha = 0.88f),
                        baseColor.copy(alpha = 0.52f),
                        Color.Transparent,
                    )
                )
            )
    )
}

@Composable
private fun SourceSummaryCard(
    drafts: List<TranscodeDraftItem>,
    selectedCount: Int,
    selectedBytes: Long,
    onOpenLibrary: () -> Unit,
    onPickFiles: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 12.dp)) {
        Text(
            if (drafts.isEmpty()) {
                stringResource(R.string.transcode_source_empty_title)
            } else {
                stringResource(R.string.transcode_source_selected_count, selectedCount)
            },
            fontSize = 25.sp, fontWeight = FontWeight.SemiBold,
            color = MiuixTheme.colorScheme.onSurface)
        Spacer(Modifier.height(8.dp))
        Text(if (drafts.isEmpty()) stringResource(R.string.transcode_source_empty_summary)
            else stringResource(R.string.transcode_source_selected_summary, formatBytes(selectedBytes)),
            fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onOpenLibrary, modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColorsPrimary()) {
                Text(stringResource(R.string.transcode_add_from_library), fontSize = 14.sp)
            }
            TextButton(
                text = stringResource(R.string.transcode_add_from_system),
                onClick = onPickFiles,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun OptionsPanel(
    title: String,
    options: TranscodeUiOptions,
    capabilities: List<AudioTranscodeCapability>,
    onOptions: (TranscodeUiOptions) -> Unit,
) {
    val capability = capabilityFor(options.format, capabilities)
    var advanced by remember { mutableStateOf(false) }
    val followSource = stringResource(R.string.transcode_follow_source_auto)
    val recommended = stringResource(R.string.transcode_recommended)
    val followBitDepth = stringResource(R.string.transcode_follow_source)
    val metadataStrictSummary = stringResource(R.string.transcode_metadata_strict_short)
    val metadataBestEffortSummary = stringResource(R.string.transcode_metadata_best_effort_short)
    val hardwareAccelerationSummary = stringResource(R.string.transcode_hardware_acceleration)
    val deleteSourceSummary = stringResource(R.string.transcode_delete_source_after)
    val autoScanSummary = stringResource(R.string.transcode_auto_scan)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, modifier = Modifier.padding(start = 6.dp), fontSize = 13.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        TranscodeCard {
            ConversionOption(stringResource(R.string.transcode_output_format), formatDisplayName(options.format),
                supportedFormats(capabilities).map { it to formatDisplayName(it) }, options.format) {
                onOptions(options.copy(format = it, sampleRateHz = null, bitDepth = null, bitRateKbps = null,
                    dsdRate = if (it == AudioTranscodeFormat.SACD_ISO) AudioTranscodeDsdRate.DSD64 else options.dsdRate,
                    strictMetadata = options.strictMetadata))
            }
            // Key the whole target-parameter branch by output format. A DSD selector must be
            // disposed immediately when the target changes to PCM/lossy instead of retaining any
            // expanded state from the previous format slot.
            key(options.format) {
                if (capability == null) {
                    Text(stringResource(R.string.transcode_loading_formats), fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                } else if (options.format.isDsd) {
                    ConversionOption(stringResource(R.string.transcode_dsd_rate), options.dsdRate.name,
                        capability.dsdRates.sortedBy { it.multiplier }.map { it to it.name }, options.dsdRate) {
                        onOptions(options.copy(dsdRate = it))
                    }
                } else {
                    ConversionOption<Int?>(stringResource(R.string.transcode_sample_rate),
                        options.sampleRateHz?.let(::formatSampleRate) ?: followSource,
                        listOf(null to followSource) + capability.sampleRatesHz.sorted().map { it to formatSampleRate(it) },
                        options.sampleRateHz) { onOptions(options.copy(sampleRateHz = it, bitRateKbps = null)) }
                    if (options.format.isLossy) {
                        val rates = options.sampleRateHz?.let { capability.bitRatesFor(it).ifEmpty { capability.bitRatesKbps } }
                            ?: capability.bitRatesKbps
                        ConversionOption<Int?>(stringResource(R.string.transcode_bitrate), options.bitRateKbps?.let { "$it kbps" } ?: recommended,
                            listOf(null to recommended) + rates.sorted().map { it to "$it kbps" }, options.bitRateKbps) {
                            onOptions(options.copy(bitRateKbps = it))
                        }
                    } else {
                        ConversionOption<Int?>(stringResource(R.string.transcode_bit_depth), options.bitDepth?.let { "$it bit" } ?: followBitDepth,
                            listOf(null to followBitDepth) + capability.bitDepths.sorted().map { it to "$it bit" },
                            options.bitDepth) { onOptions(options.copy(bitDepth = it)) }
                    }
                    if (options.format == AudioTranscodeFormat.FLAC) {
                        val faster = stringResource(R.string.transcode_flac_faster)
                        val balanced = stringResource(R.string.transcode_flac_balanced)
                        val smaller = stringResource(R.string.transcode_flac_smaller)
                        ConversionOption(stringResource(R.string.transcode_compression), when(options.flacCompressionLevel) { 5 -> faster; 12 -> smaller; else -> balanced },
                            listOf(
                                5 to stringResource(R.string.transcode_flac_level, faster, 5),
                                8 to stringResource(R.string.transcode_flac_level, balanced, 8),
                                12 to stringResource(R.string.transcode_flac_level, smaller, 12),
                            ),
                            options.flacCompressionLevel) { onOptions(options.copy(flacCompressionLevel = it)) }
                    }
                }
            }
        }
        Text(
            when {
                options.format == AudioTranscodeFormat.SACD_ISO -> stringResource(R.string.transcode_sacd_iso_note)
                options.format.isDsd -> stringResource(R.string.transcode_dsd_note)
                options.format.isLossy -> stringResource(R.string.transcode_lossy_note)
                else -> stringResource(R.string.transcode_lossless_note)
            }, modifier = Modifier.padding(horizontal = 6.dp), fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        TranscodeCard {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .clickable { advanced = !advanced }.padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.transcode_more_settings), fontSize = 15.sp)
                    Text(buildList {
                        add(if (options.strictMetadata) metadataStrictSummary else metadataBestEffortSummary)
                        if (options.hardwareAcceleration) add(hardwareAccelerationSummary)
                        if (options.deleteSourceOnSuccess) add(deleteSourceSummary)
                        if (options.autoScanOnSuccess) add(autoScanSummary)
                    }.joinToString(" · "), fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
                Text(
                    stringResource(if (advanced) R.string.transcode_collapse else R.string.transcode_expand),
                    color = MiuixTheme.colorScheme.primary,
                    fontSize = 13.sp,
                )
            }
            if (advanced) {
                SettingSwitchRow(stringResource(R.string.transcode_metadata_strict), stringResource(R.string.transcode_metadata_strict_summary), options.strictMetadata) {
                    onOptions(options.copy(strictMetadata = it))
                }
                SettingSwitchRow(stringResource(R.string.transcode_hardware_acceleration), stringResource(R.string.transcode_hardware_acceleration_summary), options.hardwareAcceleration) {
                    onOptions(options.copy(hardwareAcceleration = it))
                }
                SettingSwitchRow(stringResource(R.string.transcode_delete_source_after), stringResource(R.string.transcode_delete_source_after_summary), options.deleteSourceOnSuccess) {
                    onOptions(options.copy(deleteSourceOnSuccess = it))
                }
                SettingSwitchRow(
                    stringResource(R.string.transcode_add_to_library_after),
                    stringResource(
                        if (options.format.isDsd) R.string.transcode_add_to_library_dsd_summary
                        else R.string.transcode_add_to_library_summary
                    ),
                    options.autoScanOnSuccess,
                ) {
                    onOptions(options.copy(autoScanOnSuccess = it))
                }
            }
        }
    }
}

@Composable
private fun <T> ConversionOption(
    title: String, value: String, choices: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit,
) {
    val entry = DropdownEntry(
        items = choices.map { (key, label) ->
            DropdownItem(
                text = label,
                selected = key == selected,
                onClick = { onSelect(key) },
            )
        },
    )
    RawWindowDropdownPreference(
        entry = entry,
        title = title,
        enabled = choices.isNotEmpty(),
        showValue = true,
        valueOverride = value,
        maxHeight = 430.dp,
        collapseOnSelection = true,
    )
}

@Composable
private fun BatchPreviewCard(selected: List<TranscodeDraftItem>, options: TranscodeUiOptions, overriddenCount: Int) {
    if (selected.isEmpty()) return
    Column(Modifier.padding(horizontal = 6.dp, vertical = 8.dp)) {
        Text(stringResource(R.string.transcode_save_location), fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.transcode_save_location_summary),
            fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        if (overriddenCount > 0) Text(stringResource(R.string.transcode_override_count, overriddenCount), fontSize = 12.sp,
            color = MiuixTheme.colorScheme.primary)
        if (options.deleteSourceOnSuccess) Text(stringResource(R.string.transcode_source_will_be_deleted), fontSize = 12.sp,
            color = MiuixTheme.colorScheme.error)
    }
}

@Composable
private fun BottomBatchActions(
    modifier: Modifier = Modifier, selectedCount: Int, fixedAreaColor: Color, onConvert: () -> Unit,
) {
    Column(modifier.fillMaxWidth().background(
        Brush.verticalGradient(listOf(Color.Transparent, fixedAreaColor, fixedAreaColor)))
        .padding(horizontal = 24.dp, vertical = 16.dp)) {
        Button(onClick = onConvert, enabled = selectedCount > 0,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            colors = ButtonDefaults.buttonColorsPrimary()) {
            Text(
                if (selectedCount > 0) stringResource(R.string.transcode_start_count, selectedCount)
                else stringResource(R.string.transcode_add_audio_first),
                fontSize = 16.sp,
            )
        }
    }
}

@Composable
private fun QueueManagementPage(
    modifier: Modifier,
    drafts: List<TranscodeDraftItem>,
    queueEntries: List<AudioTranscodeQueueEntry>,
    resultsOnly: Boolean,
    globalOptions: TranscodeUiOptions,
    fixedAreaColor: Color,
    onToggleDraft: (String) -> Unit,
    onEditDraft: (String) -> Unit,
    onRemoveDraft: (String) -> Unit,
    onConvertDraft: (String) -> Unit,
    onQueueDraft: (String) -> Unit,
    onSelectAllDrafts: () -> Unit,
    onRemoveSelectedDrafts: () -> Unit,
    onQueueSelectedDrafts: () -> Unit,
    onConvertSelectedDrafts: () -> Unit,
    onCancelQueue: (String) -> Unit,
    onRetryQueue: (String) -> Unit,
    onRemoveQueue: (String) -> Boolean,
    onMoveQueue: (String, Int) -> Boolean,
    onClearFinished: () -> Unit,
    onAuthorizeSourceDelete: (taskId: String, sourcePath: String) -> Unit,
    onAddLibrary: () -> Unit,
    onAddFiles: () -> Unit,
) {
    val selectedCount = drafts.count(TranscodeDraftItem::selected)
    val hasFinished = queueEntries.any {
        it.state == AudioTranscodeQueueState.COMPLETED ||
            it.state == AudioTranscodeQueueState.FAILED ||
            it.state == AudioTranscodeQueueState.CANCELLED
    }
    Box(modifier = modifier.fillMaxWidth()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 14.dp,
                end = 14.dp,
                top = 106.dp,
                bottom = if (drafts.isNotEmpty()) 146.dp else 28.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (!resultsOnly) {
            item {
                QueueAddSourceRow(
                    onAddLibrary = onAddLibrary,
                    onAddFiles = onAddFiles,
                )
            }

            item {
                QueueSectionSummary(
                    title = stringResource(R.string.transcode_pending_title),
                    count = drafts.size,
                    summary = if (drafts.isEmpty()) {
                        stringResource(R.string.transcode_pending_empty)
                    } else {
                        stringResource(R.string.transcode_pending_selected_summary, selectedCount)
                    },
                    action = if (drafts.isNotEmpty()) {
                        stringResource(
                            if (drafts.all { it.selected }) R.string.transcode_deselect_all
                            else R.string.transcode_select_all
                        )
                    } else {
                        null
                    },
                    actionIconRes = R.drawable.ic_select_all_line,
                    onAction = onSelectAllDrafts,
                )
            }
            if (drafts.isEmpty()) {
                item { EmptyQueueCard(stringResource(R.string.transcode_pending_add_hint)) }
            } else {
                itemsIndexed(
                    items = drafts,
                    key = { _, item -> item.sourcePath },
                ) { index, item ->
                    DraftQueueCard(
                        position = index + 1,
                        item = item,
                        effectiveOptions = item.overrideOptions ?: globalOptions,
                        onToggle = { onToggleDraft(item.sourcePath) },
                        onEdit = { onEditDraft(item.sourcePath) },
                        onRemove = { onRemoveDraft(item.sourcePath) },
                        onConvert = { onConvertDraft(item.sourcePath) },
                        onQueue = { onQueueDraft(item.sourcePath) },
                    )
                }
            }

            }
            item {
                QueueSectionSummary(
                    title = stringResource(
                        if (resultsOnly) R.string.transcode_results_title else R.string.transcode_running_title
                    ),
                    count = queueEntries.size,
                    summary = when {
                        queueEntries.any { it.state == AudioTranscodeQueueState.RUNNING } -> stringResource(R.string.transcode_running_summary)
                        queueEntries.isNotEmpty() -> stringResource(
                            if (resultsOnly) R.string.transcode_results_summary else R.string.transcode_queue_summary
                        )
                        else -> stringResource(R.string.transcode_not_started)
                    },
                    action = if (hasFinished) stringResource(R.string.transcode_clear_finished) else null,
                    actionIconRes = R.drawable.ic_delete_bin_6_fill,
                    onAction = onClearFinished,
                )
            }
            if (queueEntries.isEmpty()) {
                item {
                    EmptyQueueCard(
                        stringResource(
                            if (resultsOnly) R.string.transcode_results_empty else R.string.transcode_running_empty
                        )
                    )
                }
            } else {
                itemsIndexed(
                    items = queueEntries,
                    key = { _, entry -> entry.id },
                ) { index, entry ->
                    ExecutionQueueCard(
                        position = index + 1,
                        entry = entry,
                        onCancel = { onCancelQueue(entry.id) },
                        onRetry = { onRetryQueue(entry.id) },
                        onRemove = { onRemoveQueue(entry.id) },
                        onMoveUp = { onMoveQueue(entry.id, -1) },
                        onMoveDown = { onMoveQueue(entry.id, 1) },
                        onAuthorizeSourceDelete = {
                            onAuthorizeSourceDelete(entry.id, entry.request.inputPath)
                        },
                    )
                }
            }
        }

        if (drafts.isNotEmpty()) {
            QueueFloatingActions(
                modifier = Modifier.align(Alignment.BottomCenter),
                selectedCount = selectedCount,
                totalCount = drafts.size,
                fixedAreaColor = fixedAreaColor,
                onRemoveSelected = onRemoveSelectedDrafts,
                onQueueSelected = onQueueSelectedDrafts,
                onConvertSelected = onConvertSelectedDrafts,
            )
        }
    }
}

@Composable
private fun QueueAddSourceRow(onAddLibrary: () -> Unit, onAddFiles: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        QueueTextAction(
            iconRes = R.drawable.ic_music_2_fill,
            text = stringResource(R.string.transcode_add_from_library),
            onClick = onAddLibrary,
        )
        QueueTextAction(
            iconRes = R.drawable.ic_folder_2_fill,
            text = stringResource(R.string.transcode_add_from_system),
            onClick = onAddFiles,
        )
    }
}

@Composable
private fun QueueSectionSummary(
    title: String,
    count: Int,
    summary: String,
    action: String?,
    actionIconRes: Int,
    onAction: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("$title · $count", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            if (count > 0) Text(summary, fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        if (action != null) {
            QueueTextAction(
                iconRes = actionIconRes,
                text = action,
                onClick = onAction,
            )
        }
    }
}

@Composable
private fun QueueFloatingActions(
    modifier: Modifier = Modifier,
    selectedCount: Int,
    totalCount: Int,
    fixedAreaColor: Color,
    onRemoveSelected: () -> Unit,
    onQueueSelected: () -> Unit,
    onConvertSelected: () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(136.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.00f to Color.Transparent,
                            0.20f to fixedAreaColor.copy(alpha = 0.12f),
                            0.43f to fixedAreaColor.copy(alpha = 0.54f),
                            0.66f to fixedAreaColor.copy(alpha = 0.91f),
                            1.00f to fixedAreaColor,
                        )
                    )
                )
        )

        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 14.dp, end = 14.dp, bottom = 12.dp)
                .fillMaxWidth()
                .shadow(
                    elevation = 16.dp,
                    shape = RoundedCornerShape(28.dp),
                    clip = false,
                ),
            shape = RoundedCornerShape(28.dp),
            color = MiuixTheme.colorScheme.surfaceContainerHigh,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, top = 10.dp, end = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (selectedCount > 0) {
                            stringResource(R.string.transcode_selected_of_total, selectedCount, totalCount)
                        } else {
                            stringResource(R.string.transcode_none_selected)
                        },
                        color = MiuixTheme.colorScheme.onSurface,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                    Text(
                        text = if (selectedCount > 0) {
                            stringResource(R.string.transcode_batch_affects_selected)
                        } else {
                            stringResource(R.string.transcode_select_for_batch)
                        },
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = 10.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                QueueTextAction(
                    iconRes = R.drawable.ic_delete_bin_6_fill,
                    text = stringResource(R.string.transcode_remove),
                    onClick = onRemoveSelected,
                    enabled = selectedCount > 0,
                )

                QueueTextAction(
                    iconRes = R.drawable.ic_play_list_add_fill,
                    text = stringResource(R.string.transcode_add_to_queue),
                    onClick = onQueueSelected,
                    enabled = selectedCount > 0,
                )

                Button(
                    onClick = onConvertSelected,
                    enabled = selectedCount > 0,
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Image(
                            painter = painterResource(R.drawable.ic_play),
                            contentDescription = null,
                            modifier = Modifier.size(17.dp),
                            colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onPrimary),
                        )
                        Text(
                            if (selectedCount > 0) stringResource(R.string.transcode_start_now_count, selectedCount)
                            else stringResource(R.string.transcode_start_now)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueOrdinalBadge(position: Int) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(MiuixTheme.colorScheme.surfaceVariant.copy(alpha = 0.78f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = position.toString(),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun TargetFormatBadge(
    text: String,
    emphasized: Boolean,
) {
    val tint = if (emphasized) {
        MiuixTheme.colorScheme.primary
    } else {
        MiuixTheme.colorScheme.onSurfaceVariantSummary
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Image(
            painter = painterResource(R.drawable.ic_music_note),
            contentDescription = null,
            modifier = Modifier.size(15.dp),
            colorFilter = ColorFilter.tint(tint),
        )
        Text(
            text = text,
            color = tint,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun QueueStateBadge(entry: AudioTranscodeQueueEntry) {
    val (text, accent, iconRes) = when (entry.state) {
        AudioTranscodeQueueState.QUEUED -> Triple(
            stringResource(R.string.transcode_state_waiting_short),
            MiuixTheme.colorScheme.onSurfaceVariantSummary,
            R.drawable.ic_play_list_fill,
        )
        AudioTranscodeQueueState.RUNNING -> Triple(
            stringResource(R.string.transcode_state_converting_short),
            MiuixTheme.colorScheme.primary,
            R.drawable.ic_play,
        )
        AudioTranscodeQueueState.COMPLETED -> Triple(
            stringResource(R.string.transcode_state_completed_short),
            Color(0xFF2AA66A),
            R.drawable.ic_check_line,
        )
        AudioTranscodeQueueState.FAILED -> Triple(
            stringResource(R.string.transcode_state_failed_short),
            MiuixTheme.colorScheme.error,
            R.drawable.ic_desktop_lyric_close,
        )
        AudioTranscodeQueueState.CANCELLED -> Triple(
            stringResource(R.string.transcode_state_cancelled_short),
            MiuixTheme.colorScheme.onSurfaceVariantSummary,
            R.drawable.ic_desktop_lyric_close,
        )
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.size(15.dp),
            colorFilter = ColorFilter.tint(accent),
        )
        Text(
            text = text,
            color = accent,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun DraftQueueCard(
    position: Int,
    item: TranscodeDraftItem,
    effectiveOptions: TranscodeUiOptions,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
    onConvert: () -> Unit,
    onQueue: () -> Unit,
) {
    TranscodeCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MiuixCheckBox(
                checked = item.selected,
                onClick = onToggle,
            )
            Spacer(Modifier.width(10.dp))
            QueueArtwork(
                key = item.artworkKey,
                title = item.title,
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    QueueOrdinalBadge(position)
                    Spacer(Modifier.width(7.dp))
                    TargetFormatBadge(
                        text = formatDisplayName(effectiveOptions.format),
                        emphasized = item.overrideOptions != null,
                    )
                }
                Spacer(Modifier.height(5.dp))
                Text(
                    item.title,
                    color = MiuixTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.artist.isNotBlank()) {
                    Text(
                        item.artist,
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                Text(
                    sourceSummary(item),
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            QueueActionIcon(
                contentDescription = stringResource(R.string.transcode_start_now),
                onClick = onConvert,
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_play),
                    contentDescription = null,
                    modifier = Modifier.size(21.dp),
                    colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onSurface),
                )
            }
            Spacer(Modifier.width(4.dp))
            QueueActionIcon(
                contentDescription = stringResource(R.string.transcode_add_to_queue),
                onClick = onQueue,
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_play_list_add_fill),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onSurface),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            if (item.overrideOptions != null) {
                stringResource(R.string.transcode_individual_parameters, optionsSummary(effectiveOptions))
            } else {
                stringResource(R.string.transcode_follow_batch_settings, optionsSummary(effectiveOptions))
            },
            fontSize = 12.sp,
            color = if (item.overrideOptions != null) {
                MiuixTheme.colorScheme.primary
            } else {
                MiuixTheme.colorScheme.onSurfaceVariantSummary
            },
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            QueueTextAction(
                iconRes = R.drawable.ic_settings,
                text = stringResource(R.string.transcode_individual_settings),
                onClick = onEdit,
            )
            Spacer(Modifier.weight(1f))
            QueueTextAction(
                iconRes = R.drawable.ic_delete_bin_6_fill,
                text = stringResource(R.string.transcode_remove),
                danger = true,
                onClick = onRemove,
            )
        }
    }
}

@Composable
private fun QueueArtwork(
    key: String,
    title: String,
) {
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MiuixTheme.colorScheme.surfaceVariant),
    ) {
        BitmapImage(
            key = key,
            contentDescription = title,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            targetWidth = 128,
            targetHeight = 128,
            surface = ArtworkSurface.List,
            fadeInMillis = 0,
            exactTarget = true,
        )
    }
}

@Composable
private fun QueueActionIcon(
    contentDescription: String,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier
            .size(42.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(
                role = Role.Button,
                onClickLabel = contentDescription,
                onClick = onClick,
            ),
        shape = RoundedCornerShape(14.dp),
        color = MiuixTheme.colorScheme.surfaceVariant.copy(alpha = 0.82f),
    ) {
        Box(contentAlignment = Alignment.Center) {
            content()
        }
    }
}

@Composable
private fun QueueTextAction(
    iconRes: Int,
    text: String,
    enabled: Boolean = true,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val activeColor = when {
        danger -> MiuixTheme.colorScheme.error
        else -> MiuixTheme.colorScheme.onSurface
    }
    val contentColor = if (enabled) activeColor else activeColor.copy(alpha = 0.34f)
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.size(17.dp),
            colorFilter = ColorFilter.tint(contentColor),
        )
        Text(
            text = text,
            color = contentColor,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

@Composable
private fun ExecutionQueueCard(
    position: Int,
    entry: AudioTranscodeQueueEntry,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onAuthorizeSourceDelete: () -> Unit,
) {
    val context = LocalContext.current
    val input = File(entry.request.inputPath)
    val artworkSource = entry.terminalSummary?.outputPath
        ?.takeIf { entry.state == AudioTranscodeQueueState.COMPLETED && File(it).isFile }
        ?: entry.request.inputPath
    TranscodeCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            QueueOrdinalBadge(position)
            Spacer(Modifier.width(8.dp))
            QueueArtwork(
                key = artworkKeyForPath(artworkSource),
                title = input.nameWithoutExtension.ifBlank { input.name },
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(input.nameWithoutExtension.ifBlank { input.name }, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${input.extension.uppercase(Locale.ROOT).ifBlank { "AUDIO" }} → ${formatDisplayName(entry.request.format)} · ${queueStateText(entry)}",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            QueueStateBadge(entry)
        }
        if (entry.state == AudioTranscodeQueueState.RUNNING) {
            Spacer(Modifier.height(9.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                MiuixLinearProgress(
                    progress = entry.progress.overallPermille.coerceIn(0, 1000) / 1000f,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "${entry.progress.overallPermille / 10}%",
                    color = MiuixTheme.colorScheme.primary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        val failure = entry.result as? AudioTranscodeResult.Failure
        val failedSummary = entry.terminalSummary
            ?.takeIf { entry.state == AudioTranscodeQueueState.FAILED }
        val failureDetail = localizedTranscodeFailureDetail(
            context = context,
            reason = failure?.reason ?: failedSummary?.failureReason,
            rawDetail = failure?.detail ?: failedSummary?.detail,
            nativeCode = failure?.nativeCode ?: failedSummary?.nativeCode,
        )
        if (!failureDetail.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                failureDetail,
                color = MiuixTheme.colorScheme.error,
                fontSize = 12.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val terminalSummary = entry.terminalSummary
        val postActionDetail = localizedTranscodePostActionDetail(
            context = context,
            entry = entry,
        )
        if (!postActionDetail.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                postActionDetail,
                color = if (entry.request.deleteSourceOnSuccess && terminalSummary?.sourceDeleted == false) {
                    MiuixTheme.colorScheme.error
                } else {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                },
                fontSize = 11.5.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        terminalSummary?.outputPath
            ?.takeIf { entry.state == AudioTranscodeQueueState.COMPLETED && it.isNotBlank() }
            ?.let { outputPath ->
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.transcode_output_path, outputPath),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 11.5.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (entry.state) {
                AudioTranscodeQueueState.QUEUED -> {
                    QueueTextAction(R.drawable.ic_arrow_up_line, stringResource(R.string.transcode_move_up), onClick = onMoveUp)
                    QueueTextAction(R.drawable.ic_arrow_down_line, stringResource(R.string.transcode_move_down), onClick = onMoveDown)
                    QueueTextAction(R.drawable.ic_desktop_lyric_close, stringResource(R.string.common_cancel), onClick = onCancel)
                    Spacer(Modifier.weight(1f))
                    QueueTextAction(
                        R.drawable.ic_delete_bin_6_fill,
                        stringResource(R.string.transcode_remove),
                        danger = true,
                        onClick = onRemove,
                    )
                }
                AudioTranscodeQueueState.RUNNING -> QueueTextAction(
                    R.drawable.ic_desktop_lyric_close,
                    stringResource(R.string.common_cancel),
                    onClick = onCancel,
                )
                AudioTranscodeQueueState.FAILED,
                AudioTranscodeQueueState.CANCELLED -> {
                    QueueTextAction(R.drawable.ic_repeat, stringResource(R.string.transcode_retry), onClick = onRetry)
                    Spacer(Modifier.weight(1f))
                    QueueTextAction(
                        R.drawable.ic_delete_bin_6_fill,
                        stringResource(R.string.transcode_remove),
                        danger = true,
                        onClick = onRemove,
                    )
                }
                AudioTranscodeQueueState.COMPLETED -> {
                    terminalSummary?.outputPath
                        ?.takeIf { it.isNotBlank() && File(it).isFile }
                        ?.let { outputPath ->
                            QueueTextAction(
                                R.drawable.ic_play,
                                stringResource(R.string.transcode_open),
                                onClick = { openTranscodeOutput(context, outputPath) },
                            )
                        }
                    if (terminalSummary?.sourceDeleteNeedsConsent == true) {
                        QueueTextAction(
                            R.drawable.ic_delete_bin_6_fill,
                            stringResource(R.string.transcode_authorize_delete),
                            onClick = onAuthorizeSourceDelete,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    QueueTextAction(
                        R.drawable.ic_delete_bin_6_fill,
                        stringResource(R.string.transcode_remove),
                        danger = true,
                        onClick = onRemove,
                    )
                }
            }
        }
    }
}

@Composable
private fun IndividualOptionsDialog(
    title: String,
    initial: TranscodeUiOptions,
    capabilities: List<AudioTranscodeCapability>,
    isOverride: Boolean,
    onDismiss: () -> Unit,
    onUseBatch: () -> Unit,
    onSave: (TranscodeUiOptions) -> Unit,
) {
    var local by remember(initial) { mutableStateOf(initial) }
    RawMiuixOverlayDialog(
        show = true,
        title = stringResource(R.string.transcode_individual_settings_title, title),
        summary = stringResource(R.string.transcode_individual_settings_summary),
        backgroundColor = MiuixTheme.colorScheme.surfaceContainerHigh,
        onDismissRequest = onDismiss,
        renderInRootScaffold = true,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 420.dp, max = 640.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                OptionsPanel(
                    title = stringResource(R.string.transcode_override_batch_settings),
                    options = local,
                    capabilities = capabilities,
                    onOptions = { local = it },
                )
            }

            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isOverride) {
                    TextButton(
                        text = stringResource(R.string.transcode_restore_batch_settings),
                        onClick = onUseBatch,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    TextButton(
                        text = stringResource(R.string.common_cancel),
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                    )
                }
                Button(
                    onClick = { onSave(local) },
                    colors = ButtonDefaults.buttonColorsPrimary(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.transcode_save))
                }
            }
            if (isOverride) {
                TextButton(
                    text = stringResource(R.string.common_cancel),
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun LibraryPickerDialog(
    songs: List<AudioFile>,
    existingPaths: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (List<AudioFile>) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var selectedPaths by remember { mutableStateOf<Set<String>>(emptySet()) }
    var sortMode by remember { mutableStateOf(LibraryPickerSort.LOSSLESS) }
    var descending by remember { mutableStateOf(false) }
    var showSortDialog by remember { mutableStateOf(false) }
    val hasDsd = remember(songs) { songs.any(::isDsdLibrarySong) }
    val filtered = remember(songs, query) {
        if (query.isBlank()) songs else songs.filter { song ->
            song.displayName.contains(query, true) ||
                song.artist.contains(query, true) ||
                song.album.contains(query, true) ||
                song.path.contains(query, true)
        }
    }
    val visible = remember(filtered, sortMode, descending) {
        sortLibraryPickerSongs(filtered, sortMode, descending)
    }
    val sortLabel = stringResource(sortMode.labelRes)
    RawMiuixOverlayDialog(
        show = true,
        title = stringResource(R.string.transcode_add_from_library),
        summary = stringResource(R.string.transcode_library_picker_summary),
        backgroundColor = MiuixTheme.colorScheme.surfaceContainerHigh,
        onDismissRequest = onDismiss,
        // AudioTranscodeActivity now installs its own MIUIX root Scaffold, so keep every
        // picker/dialog on the same overlay host. This restores pointer dispatch for the
        // in-app library picker and matches the rest of the MIUIX dialogs in this feature.
        renderInRootScaffold = true,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 420.dp, max = 650.dp),
        ) {
            TextField(
                value = query,
                onValueChange = { query = it },
                label = stringResource(R.string.transcode_library_search_hint),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(
                        if (descending) R.string.transcode_library_selected_sort_desc
                        else R.string.transcode_library_selected_sort,
                        selectedPaths.size,
                        sortLabel,
                    ),
                    modifier = Modifier.weight(1f),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = { showSortDialog = true }) {
                    Image(
                        painter = painterResource(R.drawable.ic_sort),
                        contentDescription = stringResource(R.string.transcode_sort),
                        modifier = Modifier.size(20.dp),
                        colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onSurface),
                    )
                }
                val selectableVisiblePaths = visible.asSequence()
                    .map(AudioFile::path)
                    .filterNot(existingPaths::contains)
                    .toSet()
                val allVisibleSelected = selectableVisiblePaths.isNotEmpty() &&
                    selectableVisiblePaths.all(selectedPaths::contains)
                IconButton(
                    onClick = {
                        selectedPaths = if (allVisibleSelected) {
                            selectedPaths - selectableVisiblePaths
                        } else {
                            selectedPaths + selectableVisiblePaths
                        }
                    },
                    enabled = selectableVisiblePaths.isNotEmpty(),
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_select_all_line),
                        contentDescription = stringResource(
                            if (allVisibleSelected) R.string.transcode_deselect_visible
                            else R.string.transcode_select_visible
                        ),
                        modifier = Modifier.size(21.dp),
                        colorFilter = ColorFilter.tint(
                            if (allVisibleSelected) {
                                MiuixTheme.colorScheme.primary
                            } else {
                                MiuixTheme.colorScheme.onSurface
                            }
                        ),
                    )
                }
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                shape = RoundedCornerShape(18.dp),
                color = MiuixTheme.colorScheme.surface.copy(alpha = 0.62f),
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 8.dp,
                        vertical = 6.dp,
                    ),
                ) {
                    if (visible.isEmpty()) {
                        item {
                            Text(
                                if (songs.isEmpty()) {
                                    stringResource(R.string.transcode_library_loading_or_empty)
                                } else {
                                    stringResource(R.string.transcode_library_no_match)
                                },
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 24.dp),
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                fontSize = 13.sp,
                            )
                        }
                    }
                    items(visible, key = { "${it.id}:${it.path}" }) { song ->
                        val alreadyAdded = song.path in existingPaths
                        val checked = alreadyAdded || song.path in selectedPaths
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .clickable(enabled = !alreadyAdded) {
                                    selectedPaths = if (song.path in selectedPaths) {
                                        selectedPaths - song.path
                                    } else {
                                        selectedPaths + song.path
                                    }
                                }
                                .padding(horizontal = 6.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            MiuixCheckBox(
                                checked = checked,
                                enabled = !alreadyAdded,
                                onClick = {
                                    if (!alreadyAdded) {
                                        selectedPaths = if (song.path in selectedPaths) {
                                            selectedPaths - song.path
                                        } else {
                                            selectedPaths + song.path
                                        }
                                    }
                                },
                            )
                            Spacer(Modifier.width(8.dp))
                            LibraryPickerArtwork(song)
                            Spacer(Modifier.width(9.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    song.displayName,
                                    color = MiuixTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    listOfNotNull(
                                        song.artist.takeIf(String::isNotBlank),
                                        song.format.takeIf(String::isNotBlank),
                                        song.sampleRate.takeIf { it > 0 }?.let(::formatSampleRate),
                                        song.bitsPerSample.takeIf { it > 0 }?.let { "$it bit" },
                                    ).joinToString(" · "),
                                    fontSize = 11.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (alreadyAdded) {
                                Text(
                                    stringResource(R.string.transcode_already_added),
                                    color = MiuixTheme.colorScheme.primary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    text = stringResource(R.string.common_cancel),
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = { onConfirm(songs.filter { it.path in selectedPaths }) },
                    enabled = selectedPaths.isNotEmpty(),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.transcode_add_count, selectedPaths.size))
                }
            }
        }
    }

    if (showSortDialog) {
        LibraryPickerSortDialog(
            current = sortMode,
            descending = descending,
            showDsd = hasDsd,
            onSelect = { selected -> sortMode = selected },
            onToggleDescending = { selected ->
                if (sortMode == selected) {
                    descending = !descending
                } else {
                    sortMode = selected
                    descending = true
                }
            },
            onDismiss = { showSortDialog = false },
        )
    }
}

@Composable
private fun LibraryPickerArtwork(song: AudioFile) {
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MiuixTheme.colorScheme.surfaceVariant),
    ) {
        BitmapImage(
            key = song.resolvePlaybackArtworkKey(null).orEmpty(),
            contentDescription = song.displayName,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            targetWidth = 104,
            targetHeight = 104,
            surface = ArtworkSurface.List,
            fadeInMillis = 0,
            exactTarget = true,
        )
    }
}

@Composable
private fun LibraryPickerSortDialog(
    current: LibraryPickerSort,
    descending: Boolean,
    showDsd: Boolean,
    onSelect: (LibraryPickerSort) -> Unit,
    onToggleDescending: (LibraryPickerSort) -> Unit,
    onDismiss: () -> Unit,
) {
    val options = LibraryPickerSort.entries.filter { it != LibraryPickerSort.DSD || showDsd }
    RawMiuixOverlayDialog(
        show = true,
        title = stringResource(R.string.transcode_sort),
        summary = stringResource(R.string.transcode_sort_summary),
        backgroundColor = MiuixTheme.colorScheme.surfaceContainerHigh,
        onDismissRequest = onDismiss,
        renderInRootScaffold = true,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 360.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            options.forEach { option ->
                val selected = current == option
                LibraryPickerSortRow(
                    title = stringResource(option.labelRes),
                    selected = selected,
                    reverseSelected = selected && descending,
                    onReverseClick = { onToggleDescending(option) },
                    onClick = { onSelect(option) },
                )
            }
        }
    }
}

@Composable
private fun LibraryPickerSortRow(
    title: String,
    selected: Boolean,
    reverseSelected: Boolean,
    onReverseClick: () -> Unit,
    onClick: () -> Unit,
) {
    val selectedColor = MiuixTheme.colorScheme.primary
    val textColor = if (selected) selectedColor else MiuixTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) selectedColor.copy(alpha = 0.10f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            color = textColor,
            fontSize = 16.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
        LibraryPickerSortDirectionToggle(
            checked = reverseSelected,
            onClick = onReverseClick,
        )
    }
}

@Composable
private fun LibraryPickerSortDirectionToggle(
    checked: Boolean,
    onClick: () -> Unit,
) {
    val selectedColor = MiuixTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(start = 10.dp, end = 2.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.transcode_descending),
            color = if (checked) selectedColor else MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = 13.sp,
            fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Normal,
        )
        Spacer(Modifier.size(7.dp))
        Box(
            modifier = Modifier
                .size(width = 30.dp, height = 18.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(
                    if (checked) selectedColor
                    else MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.20f)
                )
                .padding(2.dp),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(
                        if (checked) MiuixTheme.colorScheme.background
                        else MiuixTheme.colorScheme.onSurfaceVariantSummary
                    ),
            )
        }
    }
}

@Composable
private fun TranscodeCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MiuixTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 18.dp),
            content = content,
        )
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    summary: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    SwitchPreference(
        title = title,
        summary = summary,
        checked = checked,
        onCheckedChange = onChecked,
    )
}

@Composable
private fun PreviewRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            label,
            modifier = Modifier.width(76.dp),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = 12.sp,
        )
        Text(
            value,
            modifier = Modifier.weight(1f),
            color = MiuixTheme.colorScheme.onSurface,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun SectionHeader(title: String, action: String?, onAction: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        SmallTitle(
            text = title,
            modifier = Modifier.weight(1f),
        )
        if (action != null) {
            TextButton(
                text = action,
                onClick = onAction,
            )
        }
    }
}

@Composable
private fun EmptyQueueCard(text: String) {
    Text(text, modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 20.dp),
        fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
}

@Composable
private fun MiuixCheckBox(
    checked: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Checkbox(
        state = if (checked) ToggleableState.On else ToggleableState.Off,
        onClick = onClick,
        enabled = enabled,
    )
}

@Composable
private fun MiuixLinearProgress(
    progress: Float,
    modifier: Modifier = Modifier,
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        label = "transcode_progress",
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(5.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(MiuixTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(animatedProgress)
                .height(5.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(MiuixTheme.colorScheme.primary),
        )
    }
}

private fun sortLibraryPickerSongs(
    songs: List<AudioFile>,
    sort: LibraryPickerSort,
    descending: Boolean,
): List<AudioFile> {
    if (songs.size < 2) return songs
    val titleComparator = Comparator<AudioFile> { left, right ->
        CjkSortUtils.compare(left.displayName, right.displayName)
    }
    val baseComparator = when (sort) {
        LibraryPickerSort.LOSSLESS -> compareBy<AudioFile> { if (isLosslessLibrarySong(it)) 0 else 1 }
            .then(titleComparator)
        LibraryPickerSort.LOSSY -> compareBy<AudioFile> { if (isLosslessLibrarySong(it)) 1 else 0 }
            .then(titleComparator)
        LibraryPickerSort.DSD -> compareBy<AudioFile> { if (isDsdLibrarySong(it)) 0 else 1 }
            .then(titleComparator)
        LibraryPickerSort.BIT_DEPTH -> compareBy<AudioFile> { it.bitsPerSample.coerceAtLeast(0) }
            .then(titleComparator)
        LibraryPickerSort.SAMPLE_RATE -> compareBy<AudioFile> { it.sampleRate.coerceAtLeast(0) }
            .then(titleComparator)
    }
    return if (descending) songs.sortedWith(baseComparator.reversed()) else songs.sortedWith(baseComparator)
}

private fun isDsdLibrarySong(song: AudioFile): Boolean {
    if (song.isDsdFormat) return true
    return audioFormatTokens(song).any { token ->
        token == "DSF" || token == "DFF" || token == "DSD" || token.startsWith("DSD")
    }
}

private fun isLosslessLibrarySong(song: AudioFile): Boolean {
    if (isDsdLibrarySong(song)) return true
    val tokens = audioFormatTokens(song)
    if (tokens.any { it in LOSSLESS_LIBRARY_FORMATS }) return true
    if (tokens.any { it in LOSSY_LIBRARY_FORMATS }) return false
    // Scanner keeps meaningful PCM precision for lossless/PCM-like sources and stores 0 for
    // lossy codecs, so this is the safest fallback for an uncommon or container-only label.
    return song.bitsPerSample > 0
}

private fun audioFormatTokens(song: AudioFile): Set<String> = buildSet {
    song.format.trim().takeIf(String::isNotBlank)?.let { add(it.uppercase(Locale.ROOT)) }
    song.encodingFormat.trim().takeIf(String::isNotBlank)?.let { add(it.uppercase(Locale.ROOT)) }
    song.extension.trim().takeIf(String::isNotBlank)?.let { add(it.uppercase(Locale.ROOT)) }
}

private val LOSSLESS_LIBRARY_FORMATS = setOf(
    "FLAC", "OGGFLAC", "OGA", "WAV", "WAVE", "AIFF", "AIF", "ALAC", "APE", "WAVPACK", "WV", "TTA",
)

private val LOSSY_LIBRARY_FORMATS = setOf(
    "MP3", "MP2", "MPA", "M2A", "AAC", "M4A-AAC", "OPUS", "VORBIS", "OGG", "WMA", "AMR", "MPC", "MUSEPACK",
)

private fun supportedFormats(capabilities: List<AudioTranscodeCapability>): List<AudioTranscodeFormat> =
    AudioTranscodeFormat.entries.filter { format -> capabilities.any { it.id == format.capabilityId } }

private fun capabilityFor(
    format: AudioTranscodeFormat,
    capabilities: List<AudioTranscodeCapability>,
): AudioTranscodeCapability? = capabilities.firstOrNull { it.id == format.capabilityId }

private fun AudioFile.toDraftItem(): TranscodeDraftItem = TranscodeDraftItem(
    sourcePath = path,
    title = displayName,
    artist = artist,
    sourceFormat = format.ifBlank { extension },
    sampleRateHz = sampleRate,
    bitDepth = bitsPerSample,
    channels = channelCount,
    fileSize = fileSize.takeIf { it > 0L } ?: File(path).length(),
    artworkKey = resolvePlaybackArtworkKey(null).orEmpty(),
)

private fun artworkKeyForPath(path: String): String {
    val file = File(path)
    return "audio://${file.absolutePath}|${file.length()}|${file.lastModified()}"
}

private fun openTranscodeOutput(context: Context, outputPath: String) {
    val file = File(outputPath)
    if (!file.isFile) {
        AppNoticeBus.error(context.getString(R.string.transcode_output_missing))
        return
    }
    runCatching {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val openIntent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "audio/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(
            Intent.createChooser(openIntent, context.getString(R.string.transcode_open_result))
        )
    }.onFailure {
        AppNoticeBus.error(context.getString(R.string.transcode_no_app_to_open))
    }
}

private fun probeDraft(path: String): TranscodeDraftItem? {
    val file = File(path)
    if (!file.isFile || file.length() <= 0L) return null
    val probe = AudioTranscodeManager.probe(path) ?: return null
    return TranscodeDraftItem(
        sourcePath = file.absolutePath,
        title = file.nameWithoutExtension.ifBlank { file.name },
        artist = "",
        sourceFormat = file.extension.uppercase(Locale.ROOT).ifBlank { if (probe.isDsdSource) "DSD" else "Audio" },
        sampleRateHz = if (probe.isDsdSource) probe.dsdSampleRateHz else probe.sampleRateHz,
        bitDepth = probe.bitDepth,
        channels = probe.channels,
        fileSize = file.length(),
        artworkKey = "audio://${file.absolutePath}|${file.length()}|${file.lastModified()}",
    )
}

private fun buildRequests(
    items: List<TranscodeDraftItem>,
    globalOptions: TranscodeUiOptions,
    importedSourceRoot: String,
    importedOutputDir: File,
): List<AudioTranscodeRequest> {
    if (items.isEmpty()) return emptyList()
    val reserved = hashSetOf<String>()
    return items.map { item ->
        val options = item.overrideOptions ?: globalOptions
        AudioTranscodeRequest(
            inputPath = item.sourcePath,
            outputPath = uniqueOutputPath(
                inputPath = item.sourcePath,
                format = options.format,
                reserved = reserved,
                importedSourceRoot = importedSourceRoot,
                importedOutputDir = importedOutputDir,
            ),
            format = options.format,
            targetSampleRateHz = if (options.format.isDsd) null else options.sampleRateHz,
            targetBitDepth = if (options.format.isDsd || options.format.isLossy) null else options.bitDepth,
            dsdRate = options.dsdRate,
            flacCompressionLevel = options.flacCompressionLevel,
            targetBitRateKbps = if (options.format.isLossy) options.bitRateKbps else null,
            metadataPolicy = if (options.strictMetadata) {
                AudioTranscodeMetadataPolicy.STRICT
            } else {
                AudioTranscodeMetadataPolicy.BEST_EFFORT
            },
            verificationMode = AudioTranscodeVerificationMode.AUTO,
            hardwareAccelerationEnabled = options.hardwareAcceleration,
            deleteSourceOnSuccess = options.deleteSourceOnSuccess,
            autoScanOnSuccess = options.autoScanOnSuccess,
            overwrite = false,
        )
    }
}

private fun uniqueOutputPath(
    inputPath: String,
    format: AudioTranscodeFormat,
    reserved: MutableSet<String>,
    @Suppress("UNUSED_PARAMETER") importedSourceRoot: String,
    importedOutputDir: File,
): String {
    val input = File(inputPath)
    // Publish all conversions into one predictable public directory. Source-local folders are
    // easy to miss when a library item lives several levels deep under Downloads/cloud folders.
    val outputDir = importedOutputDir
    val stem = input.nameWithoutExtension.ifBlank { "audio" }
    var index = 0
    while (true) {
        val suffix = if (index == 0) "" else " ($index)"
        val candidate = File(outputDir, "$stem$suffix.${format.extension}")
        val key = candidate.absolutePath.lowercase(Locale.ROOT)
        if (!candidate.exists() && key !in reserved) {
            reserved += key
            return candidate.absolutePath
        }
        index++
    }
}

private fun formatDisplayName(format: AudioTranscodeFormat): String = when (format) {
    AudioTranscodeFormat.SACD_ISO -> "SACD ISO"
    AudioTranscodeFormat.OGG_FLAC -> "Ogg FLAC"
    AudioTranscodeFormat.ALAC -> "ALAC"
    AudioTranscodeFormat.WAVPACK -> "WavPack"
    AudioTranscodeFormat.TTA -> "TTA"
    AudioTranscodeFormat.MP2 -> "MP2"
    AudioTranscodeFormat.VORBIS -> "Vorbis"
    AudioTranscodeFormat.WMA -> "WMA"
    else -> format.name
}

@Composable
private fun optionsSummary(options: TranscodeUiOptions): String {
    val compatibleSource = stringResource(R.string.transcode_follow_compatible_source)
    val recommendedBitrate = stringResource(R.string.transcode_recommended_bitrate)
    val keepSampleRate = stringResource(R.string.transcode_keep_sample_rate)
    val keepBitDepth = stringResource(R.string.transcode_keep_bit_depth)
    return buildString {
    append(formatDisplayName(options.format))
    when {
        options.format.isDsd -> append(" · ${options.dsdRate.name}")
        options.format.isLossy -> {
            append(" · ${options.sampleRateHz?.let(::formatSampleRate) ?: compatibleSource}")
            append(" · ${options.bitRateKbps?.let { "$it kbps" } ?: recommendedBitrate}")
        }
        else -> {
            append(" · ${options.sampleRateHz?.let(::formatSampleRate) ?: keepSampleRate}")
            append(" · ${options.bitDepth?.let { "$it bit" } ?: keepBitDepth}")
        }
    }
    }
}

private fun sourceSummary(item: TranscodeDraftItem): String = listOfNotNull(
    item.sourceFormat.takeIf(String::isNotBlank),
    item.sampleRateHz.takeIf { it > 0 }?.let(::formatSampleRate),
    item.bitDepth.takeIf { it > 0 }?.let { "$it bit" },
    item.channels.takeIf { it > 0 }?.let { "${it}ch" },
    item.fileSize.takeIf { it > 0 }?.let(::formatBytes),
).joinToString(" · ")

private fun formatSampleRate(rate: Int): String = when {
    rate % 1000 == 0 -> "${rate / 1000} kHz"
    else -> String.format(Locale.US, "%.1f kHz", rate / 1000.0)
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit++
    }
    return if (unit == 0) "$bytes B" else String.format(Locale.US, "%.1f %s", value, units[unit])
}

@Composable
private fun queueStateText(entry: AudioTranscodeQueueEntry): String = when (entry.state) {
    AudioTranscodeQueueState.QUEUED -> stringResource(R.string.transcode_stage_queued)
    AudioTranscodeQueueState.RUNNING -> when (entry.progress.stage) {
        AudioTranscodeStage.PROBING -> stringResource(R.string.transcode_stage_probing)
        AudioTranscodeStage.ENCODING -> stringResource(R.string.transcode_stage_encoding)
        AudioTranscodeStage.MIGRATING_METADATA -> stringResource(R.string.transcode_stage_metadata)
        AudioTranscodeStage.VERIFYING -> stringResource(R.string.transcode_stage_verifying)
        AudioTranscodeStage.COMMITTING -> stringResource(R.string.transcode_stage_committing)
        else -> stringResource(R.string.transcode_processing)
    }
    AudioTranscodeQueueState.COMPLETED -> stringResource(R.string.transcode_stage_completed)
    AudioTranscodeQueueState.FAILED -> stringResource(R.string.transcode_stage_failed)
    AudioTranscodeQueueState.CANCELLED -> stringResource(R.string.transcode_stage_cancelled)
}

private fun localizedTranscodeFailureDetail(
    context: Context,
    reason: AudioTranscodeFailureReason?,
    rawDetail: String?,
    nativeCode: Int?,
): String? {
    return com.rawsmusic.transcode.formatTranscodeFailureDetail(
        language = context.resources.configuration.locales[0]?.language.orEmpty(),
        reason = reason,
        rawDetail = rawDetail,
        nativeCode = nativeCode,
        localizedReason = { failureReason -> when (failureReason) {
        AudioTranscodeFailureReason.INVALID_REQUEST ->
            context.getString(R.string.transcode_failure_invalid_request)
        AudioTranscodeFailureReason.UNSUPPORTED_SOURCE ->
            context.getString(R.string.transcode_failure_unsupported_source)
        AudioTranscodeFailureReason.UNSUPPORTED_TARGET ->
            context.getString(R.string.transcode_failure_unsupported_target)
        AudioTranscodeFailureReason.OUTPUT_EXISTS ->
            context.getString(R.string.transcode_failure_output_exists)
        AudioTranscodeFailureReason.INSUFFICIENT_SPACE ->
            context.getString(R.string.transcode_failure_insufficient_space)
        AudioTranscodeFailureReason.ENCODE_FAILED ->
            context.getString(R.string.transcode_failure_encode_failed)
        AudioTranscodeFailureReason.METADATA_MIGRATION_FAILED ->
            context.getString(R.string.transcode_failure_metadata_migration)
        AudioTranscodeFailureReason.VERIFICATION_FAILED ->
            context.getString(R.string.transcode_failure_verification)
        AudioTranscodeFailureReason.COMMIT_FAILED ->
            context.getString(R.string.transcode_failure_commit)
        AudioTranscodeFailureReason.PROCESS_INTERRUPTED ->
            context.getString(R.string.transcode_failure_process_interrupted)
        AudioTranscodeFailureReason.INTERNAL_ERROR ->
            context.getString(R.string.transcode_failure_internal)
        } },
        withNativeCode = { base, code ->
            context.getString(R.string.transcode_failure_with_native_code, base, code)
        },
    )
}

private fun localizedTranscodePostActionDetail(
    context: Context,
    entry: AudioTranscodeQueueEntry,
): String? {
    val summary = entry.terminalSummary ?: return null
    val raw = summary.postActionDetail?.trim().orEmpty()
    if (raw.isBlank()) return null

    val currentLanguage = context.resources.configuration.locales[0]?.language.orEmpty()
    val rawContainsHan = raw.any { ch ->
        Character.UnicodeScript.of(ch.code) == Character.UnicodeScript.HAN
    }
    if (currentLanguage == "zh" || !rawContainsHan) return raw

    return when {
        entry.request.deleteSourceOnSuccess && summary.sourceDeleteNeedsConsent ->
            context.getString(R.string.transcode_post_source_delete_needs_consent)
        entry.request.deleteSourceOnSuccess && summary.sourceDeleted == true ->
            context.getString(R.string.transcode_post_source_deleted)
        entry.request.deleteSourceOnSuccess && summary.sourceDeleted == false ->
            context.getString(R.string.transcode_post_source_delete_failed)
        summary.autoScanRequested ->
            context.getString(R.string.transcode_post_library_refresh_requested)
        else -> context.getString(R.string.transcode_post_action_notice)
    }
}
