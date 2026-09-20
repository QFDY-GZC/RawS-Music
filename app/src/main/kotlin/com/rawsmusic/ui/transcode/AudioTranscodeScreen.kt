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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.content.FileProvider
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
import com.rawsmusic.transcode.AudioTranscodeCapability
import com.rawsmusic.transcode.AudioTranscodeDsdRate
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

private enum class TranscodeTab(val label: String) { SETTINGS("格式转换"), QUEUE("队列管理"), RESULTS("转换结果") }

private enum class LibraryPickerSort(val label: String) {
    LOSSLESS("无损"),
    LOSSY("有损"),
    DSD("DSD 优先"),
    BIT_DEPTH("位深"),
    SAMPLE_RATE("采样率"),
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
            AppNoticeBus.error("转换结果需要写入音乐目录，请授权文件访问后重试")
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
            AppNoticeBus.error("所选文件没有可用的音频流")
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
            title = "音频转换",
            color = pageBackground,
            titleColor = MiuixTheme.colorScheme.onBackground,
            navigationIcon = {
                IconButton(onClick = {
                    onBack()
                }) {
                    Icon(
                        imageVector = MiuixIcons.Regular.Back,
                        contentDescription = "返回",
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
                                        title = if (selectedDrafts.size > 1) "批量转换设置" else "转换设置",
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
                                            AppNoticeBus.error("请先选择要转换的音频")
                                        } else {
                                            AudioTranscodeForegroundService.enqueueAllNow(context, requests).fold(
                                                onSuccess = {
                                                    drafts.removeAll { it.sourcePath in selectedPaths }
                                                    AppNoticeBus.post(
                                                        message = "已开始 ${it.size} 个转换任务",
                                                        icon = AppNoticeIcon.TRANSCODE,
                                                    )
                                                    openTab(TranscodeTab.QUEUE)
                                                },
                                                onFailure = {
                                                    AppNoticeBus.error(it.message ?: "无法启动转换服务")
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
                                            message = "已优先开始转换",
                                            icon = AppNoticeIcon.TRANSCODE,
                                        )
                                    },
                                    onFailure = {
                                        AppNoticeBus.error(it.message ?: "无法启动转换服务")
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
                                            message = "已加入真实队列",
                                            icon = AppNoticeIcon.QUEUE,
                                        )
                                    },
                                    onFailure = {
                                        AppNoticeBus.error(it.message ?: "无法加入转换队列")
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
                                                message = "已加入 ${it.size} 个真实队列任务",
                                                icon = AppNoticeIcon.QUEUE,
                                            )
                                        },
                                        onFailure = {
                                            AppNoticeBus.error(it.message ?: "无法加入转换队列")
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
                                                message = "已优先开始 ${it.size} 个转换任务",
                                                icon = AppNoticeIcon.TRANSCODE,
                                            )
                                        },
                                        onFailure = {
                                            AppNoticeBus.error(it.message ?: "无法启动转换服务")
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
                    Text(destination.label, fontSize = 14.sp,
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
        Text(if (drafts.isEmpty()) "让音乐，换一种格式" else "已选择 $selectedCount 首音频",
            fontSize = 25.sp, fontWeight = FontWeight.SemiBold,
            color = MiuixTheme.colorScheme.onSurface)
        Spacer(Modifier.height(8.dp))
        Text(if (drafts.isEmpty()) "从软件内或系统文件添加音频，轻松批量转换。"
            else "${formatBytes(selectedBytes)} · 在队列管理中选择歌曲或单独调整",
            fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onOpenLibrary, modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColorsPrimary()) { Text("软件内添加", fontSize = 14.sp) }
            TextButton(text = "系统文件选择", onClick = onPickFiles, modifier = Modifier.weight(1f))
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
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, modifier = Modifier.padding(start = 6.dp), fontSize = 13.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        TranscodeCard {
            ConversionOption("输出格式", formatDisplayName(options.format),
                supportedFormats(capabilities).map { it to formatDisplayName(it) }, options.format) {
                onOptions(options.copy(format = it, sampleRateHz = null, bitDepth = null, bitRateKbps = null))
            }
            // Key the whole target-parameter branch by output format. A DSD selector must be
            // disposed immediately when the target changes to PCM/lossy instead of retaining any
            // expanded state from the previous format slot.
            key(options.format) {
                if (capability == null) {
                    Text("正在加载可用格式…", fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                } else if (options.format.isDsd) {
                    ConversionOption("DSD 规格", options.dsdRate.name,
                        capability.dsdRates.sortedBy { it.multiplier }.map { it to it.name }, options.dsdRate) {
                        onOptions(options.copy(dsdRate = it))
                    }
                } else {
                    ConversionOption<Int?>("采样率",
                        options.sampleRateHz?.let(::formatSampleRate) ?: "自动 · 跟随源文件",
                        listOf(null to "自动 · 跟随源文件") + capability.sampleRatesHz.sorted().map { it to formatSampleRate(it) },
                        options.sampleRateHz) { onOptions(options.copy(sampleRateHz = it, bitRateKbps = null)) }
                    if (options.format.isLossy) {
                        val rates = options.sampleRateHz?.let { capability.bitRatesFor(it).ifEmpty { capability.bitRatesKbps } }
                            ?: capability.bitRatesKbps
                        ConversionOption<Int?>("码率", options.bitRateKbps?.let { "$it kbps" } ?: "推荐",
                            listOf(null to "推荐") + rates.sorted().map { it to "$it kbps" }, options.bitRateKbps) {
                            onOptions(options.copy(bitRateKbps = it))
                        }
                    } else {
                        ConversionOption<Int?>("位深", options.bitDepth?.let { "$it bit" } ?: "跟随源文件",
                            listOf(null to "跟随源文件") + capability.bitDepths.sorted().map { it to "$it bit" },
                            options.bitDepth) { onOptions(options.copy(bitDepth = it)) }
                    }
                    if (options.format == AudioTranscodeFormat.FLAC) {
                        ConversionOption("压缩程度", when(options.flacCompressionLevel) { 5 -> "更快"; 12 -> "更小"; else -> "均衡" },
                            listOf(5 to "更快 · 级别 5", 8 to "均衡 · 级别 8", 12 to "更小 · 级别 12"),
                            options.flacCompressionLevel) { onOptions(options.copy(flacCompressionLevel = it)) }
                    }
                }
            }
        }
        Text(
            when {
                options.format.isDsd -> "适合支持 DSD 的播放设备；PCM→DSD 是调制转换，不会增加源文件的信息量，输出体积通常会明显增大。"
                options.format.isLossy -> "体积更小，适合日常聆听；有损转换无法恢复原始音质。"
                else -> "无损存储，适合收藏。转换不会提升源文件的音质。"
            }, modifier = Modifier.padding(horizontal = 6.dp), fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        TranscodeCard {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .clickable { advanced = !advanced }.padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("更多设置", fontSize = 15.sp)
                    Text(buildList {
                        add(if (options.strictMetadata) "完整保留标签" else "尽量保留标签")
                        if (options.hardwareAcceleration) add("硬件加速")
                        if (options.deleteSourceOnSuccess) add("完成后删除源文件")
                        if (options.autoScanOnSuccess) add("自动扫描")
                    }.joinToString(" · "), fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
                Text(if (advanced) "收起" else "展开", color = MiuixTheme.colorScheme.primary, fontSize = 13.sp)
            }
            if (advanced) {
                SettingSwitchRow("完整保留标签与封面", "无法完整保留时停止转换", options.strictMetadata) {
                    onOptions(options.copy(strictMetadata = it))
                }
                SettingSwitchRow("硬件加速", "设备不支持时自动使用软件编码", options.hardwareAcceleration) {
                    onOptions(options.copy(hardwareAcceleration = it))
                }
                SettingSwitchRow("完成后删除源文件", "仅在转换和验证成功后删除", options.deleteSourceOnSuccess) {
                    onOptions(options.copy(deleteSourceOnSuccess = it))
                }
                SettingSwitchRow(
                    "完成后加入音乐库",
                    if (options.format.isDsd) "DSF 会自动扫描；开启后其他格式也在队列完成后扫描" else "所有任务完成后自动扫描",
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
        Text("保存位置", fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        Text("转换结果统一保存到 Music / RawSMusic / Converted。",
            fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        if (overriddenCount > 0) Text("$overriddenCount 首使用单独设置", fontSize = 12.sp,
            color = MiuixTheme.colorScheme.primary)
        if (options.deleteSourceOnSuccess) Text("转换成功后将删除源文件", fontSize = 12.sp,
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
            Text(if (selectedCount > 0) "开始转换 · $selectedCount 首" else "先添加音频", fontSize = 16.sp)
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
                    title = "待转换",
                    count = drafts.size,
                    summary = if (drafts.isEmpty()) {
                        "还没有待处理项目"
                    } else {
                        "已选 $selectedCount 首 · 可单独覆盖参数或批量转换"
                    },
                    action = if (drafts.isNotEmpty()) {
                        if (drafts.all { it.selected }) "取消全选" else "全选"
                    } else {
                        null
                    },
                    actionIconRes = R.drawable.ic_select_all_line,
                    onAction = onSelectAllDrafts,
                )
            }
            if (drafts.isEmpty()) {
                item { EmptyQueueCard("可以从软件内多选音频，或通过系统文件选择一次加入多个文件。") }
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
                    title = if (resultsOnly) "转换结果" else "执行中",
                    count = queueEntries.size,
                    summary = when {
                        queueEntries.any { it.state == AudioTranscodeQueueState.RUNNING } -> "正在执行任务，完成后自动继续下一项"
                        queueEntries.isNotEmpty() -> if (resultsOnly) "完成的文件与处理记录" else "按顺序自动转换"
                        else -> "尚未开始转换"
                    },
                    action = if (hasFinished) "清理已完成" else null,
                    actionIconRes = R.drawable.ic_delete_bin_6_fill,
                    onAction = onClearFinished,
                )
            }
            if (queueEntries.isEmpty()) {
                item { EmptyQueueCard(if (resultsOnly) "还没有转换结果\n任务完成后，会在这里保留结果和失败原因。" else "暂无执行中的任务") }
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
            text = "软件内添加",
            onClick = onAddLibrary,
        )
        QueueTextAction(
            iconRes = R.drawable.ic_folder_2_fill,
            text = "系统文件选择",
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
                            "已选 $selectedCount / $totalCount 首"
                        } else {
                            "未选择待转换项目"
                        },
                        color = MiuixTheme.colorScheme.onSurface,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                    Text(
                        text = if (selectedCount > 0) {
                            "批量操作只影响当前勾选的项目"
                        } else {
                            "勾选项目后可批量移除或开始转换"
                        },
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = 10.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                QueueTextAction(
                    iconRes = R.drawable.ic_delete_bin_6_fill,
                    text = "移除",
                    onClick = onRemoveSelected,
                    enabled = selectedCount > 0,
                )

                QueueTextAction(
                    iconRes = R.drawable.ic_play_list_add_fill,
                    text = "加入队列",
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
                        Text(if (selectedCount > 0) "立即开始 ($selectedCount)" else "立即开始")
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
            "等待",
            MiuixTheme.colorScheme.onSurfaceVariantSummary,
            R.drawable.ic_play_list_fill,
        )
        AudioTranscodeQueueState.RUNNING -> Triple(
            "转换中",
            MiuixTheme.colorScheme.primary,
            R.drawable.ic_play,
        )
        AudioTranscodeQueueState.COMPLETED -> Triple(
            "完成",
            Color(0xFF2AA66A),
            R.drawable.ic_check_line,
        )
        AudioTranscodeQueueState.FAILED -> Triple(
            "失败",
            MiuixTheme.colorScheme.error,
            R.drawable.ic_desktop_lyric_close,
        )
        AudioTranscodeQueueState.CANCELLED -> Triple(
            "已取消",
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
                contentDescription = "立即开始转换",
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
                contentDescription = "加入真实队列",
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
                "单独参数 · ${optionsSummary(effectiveOptions)}"
            } else {
                "跟随批量设置 · ${optionsSummary(effectiveOptions)}"
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
                text = "单独设置",
                onClick = onEdit,
            )
            Spacer(Modifier.weight(1f))
            QueueTextAction(
                iconRes = R.drawable.ic_delete_bin_6_fill,
                text = "移除",
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
        val failureDetail = (entry.result as? AudioTranscodeResult.Failure)?.detail
            ?: entry.terminalSummary
                ?.takeIf { entry.state == AudioTranscodeQueueState.FAILED }
                ?.detail
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
        val postActionDetail = terminalSummary?.postActionDetail
        if (!postActionDetail.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                postActionDetail,
                color = if (entry.request.deleteSourceOnSuccess && terminalSummary.sourceDeleted == false) {
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
                    text = "输出：$outputPath",
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
                    QueueTextAction(R.drawable.ic_arrow_up_line, "上移", onClick = onMoveUp)
                    QueueTextAction(R.drawable.ic_arrow_down_line, "下移", onClick = onMoveDown)
                    QueueTextAction(R.drawable.ic_desktop_lyric_close, "取消", onClick = onCancel)
                    Spacer(Modifier.weight(1f))
                    QueueTextAction(
                        R.drawable.ic_delete_bin_6_fill,
                        "移除",
                        danger = true,
                        onClick = onRemove,
                    )
                }
                AudioTranscodeQueueState.RUNNING -> QueueTextAction(
                    R.drawable.ic_desktop_lyric_close,
                    "取消",
                    onClick = onCancel,
                )
                AudioTranscodeQueueState.FAILED,
                AudioTranscodeQueueState.CANCELLED -> {
                    QueueTextAction(R.drawable.ic_repeat, "重试", onClick = onRetry)
                    Spacer(Modifier.weight(1f))
                    QueueTextAction(
                        R.drawable.ic_delete_bin_6_fill,
                        "移除",
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
                                "打开",
                                onClick = { openTranscodeOutput(context, outputPath) },
                            )
                        }
                    if (terminalSummary?.sourceDeleteNeedsConsent == true) {
                        QueueTextAction(
                            R.drawable.ic_delete_bin_6_fill,
                            "授权删除",
                            onClick = onAuthorizeSourceDelete,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    QueueTextAction(
                        R.drawable.ic_delete_bin_6_fill,
                        "移除",
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
        title = "单独设置 · $title",
        summary = "仅覆盖这一首音频；其他项目继续跟随批量设置",
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
                    title = "覆盖批量设置",
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
                        text = "恢复批量设置",
                        onClick = onUseBatch,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    TextButton(
                        text = "取消",
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                    )
                }
                Button(
                    onClick = { onSave(local) },
                    colors = ButtonDefaults.buttonColorsPrimary(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("保存")
                }
            }
            if (isOverride) {
                TextButton(
                    text = "取消",
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
    RawMiuixOverlayDialog(
        show = true,
        title = "软件内添加",
        summary = "从 RawSMusic 曲库中选择音频；已在草稿队列中的歌曲会保持选中并锁定",
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
                label = "搜索歌曲 / 艺术家 / 专辑",
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "已选 ${selectedPaths.size} · ${sortMode.label}${if (descending) " · 倒序" else ""}",
                    modifier = Modifier.weight(1f),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = { showSortDialog = true }) {
                    Image(
                        painter = painterResource(R.drawable.ic_sort),
                        contentDescription = "排序",
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
                        contentDescription = if (allVisibleSelected) "取消全选当前结果" else "全选当前结果",
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
                                    "软件内曲库正在加载，或当前曲库为空。"
                                } else {
                                    "没有匹配的歌曲。"
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
                                    "已添加",
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
                    text = "取消",
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = { onConfirm(songs.filter { it.path in selectedPaths }) },
                    enabled = selectedPaths.isNotEmpty(),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("添加 (${selectedPaths.size})")
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
        title = "排序",
        summary = "排序方式与曲库列表一致；右侧可单独切换倒序",
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
                    title = option.label,
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
            text = "倒序",
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
        AppNoticeBus.error("输出文件已不存在")
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
        context.startActivity(Intent.createChooser(openIntent, "打开转换结果"))
    }.onFailure {
        AppNoticeBus.error("没有可用的应用打开此音频")
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
    AudioTranscodeFormat.OGG_FLAC -> "Ogg FLAC"
    AudioTranscodeFormat.ALAC -> "ALAC"
    AudioTranscodeFormat.WAVPACK -> "WavPack"
    AudioTranscodeFormat.TTA -> "TTA"
    AudioTranscodeFormat.MP2 -> "MP2"
    AudioTranscodeFormat.VORBIS -> "Vorbis"
    AudioTranscodeFormat.WMA -> "WMA"
    else -> format.name
}

private fun optionsSummary(options: TranscodeUiOptions): String = buildString {
    append(formatDisplayName(options.format))
    when {
        options.format.isDsd -> append(" · ${options.dsdRate.name}")
        options.format.isLossy -> {
            append(" · ${options.sampleRateHz?.let(::formatSampleRate) ?: "跟随/兼容源"}")
            append(" · ${options.bitRateKbps?.let { "$it kbps" } ?: "推荐码率"}")
        }
        else -> {
            append(" · ${options.sampleRateHz?.let(::formatSampleRate) ?: "保持采样率"}")
            append(" · ${options.bitDepth?.let { "$it bit" } ?: "保持位深"}")
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

private fun queueStateText(entry: AudioTranscodeQueueEntry): String = when (entry.state) {
    AudioTranscodeQueueState.QUEUED -> "等待中"
    AudioTranscodeQueueState.RUNNING -> when (entry.progress.stage) {
        AudioTranscodeStage.PROBING -> "检查源文件"
        AudioTranscodeStage.ENCODING -> "正在编码"
        AudioTranscodeStage.MIGRATING_METADATA -> "迁移元数据"
        AudioTranscodeStage.VERIFYING -> "验证输出"
        AudioTranscodeStage.COMMITTING -> "提交文件"
        else -> "处理中"
    }
    AudioTranscodeQueueState.COMPLETED -> "已完成"
    AudioTranscodeQueueState.FAILED -> "失败"
    AudioTranscodeQueueState.CANCELLED -> "已取消"
}
