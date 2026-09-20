package com.rawsmusic.transcode

import android.content.Context
import com.rawsmusic.module.scanner.LibraryIncrementalPathPublisher
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

enum class AudioTranscodeQueueState {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
}

data class AudioTranscodeQueueEntry(
    val id: String,
    val request: AudioTranscodeRequest,
    val state: AudioTranscodeQueueState,
    val progress: AudioTranscodeProgress = AudioTranscodeProgress(
        stage = AudioTranscodeStage.QUEUED,
        stagePermille = 0,
        overallPermille = 0,
    ),
    val result: AudioTranscodeResult? = null,
    val terminalSummary: AudioTranscodeQueueTerminalSummary? = null,
    val enqueuedAtMs: Long = System.currentTimeMillis(),
    val startedAtMs: Long? = null,
    val finishedAtMs: Long? = null,
)

data class AudioTranscodeQueueTerminalSummary(
    val outputPath: String? = null,
    val failureReason: AudioTranscodeFailureReason? = null,
    val detail: String? = null,
    val nativeCode: Int? = null,
    val backend: AudioTranscodeBackendInfo? = null,
    val postActionDetail: String? = null,
    val sourceDeleted: Boolean? = null,
    val sourceDeleteNeedsConsent: Boolean = false,
    val autoScanRequested: Boolean = false,
)

/**
 * Serialized queue for offline conversion jobs.
 *
 * The queue deliberately owns ordering/cancellation separately from [AudioTranscodeManager]. A
 * Once [initialize] receives an application context, durable queue transitions are persisted with
 * AtomicFile. QUEUED work may be restored; a persisted RUNNING item is converted to an explicit
 * PROCESS_INTERRUPTED failure instead of guessing how to continue a hidden partial file.
 */
object AudioTranscodeQueue {
    private data class JobHolder(
        val request: AudioTranscodeRequest,
        @Volatile var task: AudioTranscodeTask? = null,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /**
     * Wake-up signal rather than an id queue. The worker resolves the first QUEUED entry from
     * [_entries] every time, so UI reorder operations change the real execution order too.
     */
    private val scheduleSignals = Channel<Unit>(Channel.CONFLATED)
    private val holders = ConcurrentHashMap<String, JobHolder>()
    private val stateLock = Any()
    private val _entries = MutableStateFlow<List<AudioTranscodeQueueEntry>>(emptyList())
    @Volatile private var initialized = false
    @Volatile private var executionEnabled = false
    @Volatile private var persistence: AudioTranscodeQueueStore? = null
    @Volatile private var applicationContext: Context? = null
    @Volatile private var pendingLibraryScan = false
    private val pendingIncrementalLibraryPaths = linkedSetOf<String>()

    val entries: StateFlow<List<AudioTranscodeQueueEntry>> = _entries.asStateFlow()

    init {
        scope.launch {
            for (ignored in scheduleSignals) {
                while (true) {
                    if (!executionEnabled) break
                    val nextId = synchronized(stateLock) {
                        _entries.value.firstOrNull { it.state == AudioTranscodeQueueState.QUEUED }?.id
                    } ?: break
                    runQueued(nextId)
                }
            }
        }
    }

    fun initialize(context: Context) {
        var sanitizedSnapshot: List<AudioTranscodeQueueEntry>? = null
        val interruptedRequests = mutableListOf<AudioTranscodeRequest>()
        synchronized(stateLock) {
            if (initialized) return
            val appContext = context.applicationContext
            applicationContext = appContext
            val store = AudioTranscodeQueueStore(appContext)
            persistence = store
            holders.clear()
            val now = System.currentTimeMillis()
            val sanitized = store.load().map { entry ->
                when (entry.state) {
                    AudioTranscodeQueueState.QUEUED -> {
                        holders[entry.id] = JobHolder(entry.request)
                        entry.copy(
                            progress = AudioTranscodeProgress(
                                stage = AudioTranscodeStage.QUEUED,
                                stagePermille = 0,
                                overallPermille = 0,
                            ),
                            result = null,
                            terminalSummary = null,
                            startedAtMs = null,
                            finishedAtMs = null,
                        )
                    }
                    AudioTranscodeQueueState.RUNNING -> {
                        interruptedRequests += entry.request
                        val detail = "应用进程在转换执行期间结束；为避免猜测续写隐藏 partial，任务未自动恢复，请手动重试"
                        entry.copy(
                            state = AudioTranscodeQueueState.FAILED,
                            progress = AudioTranscodeProgress(
                                stage = AudioTranscodeStage.FAILED,
                                stagePermille = 1000,
                                overallPermille = entry.progress.overallPermille.coerceIn(0, 999),
                            ),
                            result = AudioTranscodeResult.Failure(
                                reason = AudioTranscodeFailureReason.PROCESS_INTERRUPTED,
                                detail = detail,
                            ),
                            terminalSummary = AudioTranscodeQueueTerminalSummary(
                                failureReason = AudioTranscodeFailureReason.PROCESS_INTERRUPTED,
                                detail = detail,
                            ),
                            finishedAtMs = now,
                        )
                    }
                    AudioTranscodeQueueState.COMPLETED -> entry.copy(result = null)
                    AudioTranscodeQueueState.FAILED -> {
                        val summary = entry.terminalSummary
                        entry.copy(
                            result = summary?.failureReason?.let { reason ->
                                AudioTranscodeResult.Failure(
                                    reason = reason,
                                    detail = summary.detail.orEmpty(),
                                    nativeCode = summary.nativeCode,
                                )
                            },
                        )
                    }
                    AudioTranscodeQueueState.CANCELLED -> entry.copy(
                        result = AudioTranscodeResult.Cancelled,
                    )
                }
            }
            _entries.value = sanitized
            initialized = true
            sanitizedSnapshot = sanitized
        }
        sanitizedSnapshot?.let(::persistSnapshot)
        if (interruptedRequests.isNotEmpty()) {
            scope.launch(Dispatchers.IO) {
                interruptedRequests.forEach(::cleanupInterruptedPartials)
            }
        }
    }

    /** Called only after a foreground owner has taken responsibility for restored pending work. */
    fun resumePending() {
        executionEnabled = true
        if (_entries.value.any { it.state == AudioTranscodeQueueState.QUEUED }) {
            scheduleSignals.trySend(Unit)
        }
    }

    fun suspendPendingExecution() {
        executionEnabled = false
    }

    fun enqueue(request: AudioTranscodeRequest): String = enqueueInternal(request, priority = false)

    /** Insert before every ordinary QUEUED item. A currently RUNNING item is never displaced. */
    fun enqueuePriority(request: AudioTranscodeRequest): String = enqueueInternal(request, priority = true)

    private fun enqueueInternal(request: AudioTranscodeRequest, priority: Boolean): String {
        val id = UUID.randomUUID().toString()
        holders[id] = JobHolder(request)
        mutateEntries { current ->
            val entry = AudioTranscodeQueueEntry(
                id = id,
                request = request,
                state = AudioTranscodeQueueState.QUEUED,
            )
            insertQueuedEntries(current, listOf(entry), priority)
        }
        if (executionEnabled && scheduleSignals.trySend(Unit).isFailure) {
            holders.remove(id)
            mutateEntry(id) { entry ->
                val detail = "转换队列已关闭"
                entry.copy(
                    state = AudioTranscodeQueueState.FAILED,
                    result = AudioTranscodeResult.Failure(
                        AudioTranscodeFailureReason.INTERNAL_ERROR,
                        detail,
                    ),
                    terminalSummary = AudioTranscodeQueueTerminalSummary(
                        failureReason = AudioTranscodeFailureReason.INTERNAL_ERROR,
                        detail = detail,
                    ),
                    finishedAtMs = System.currentTimeMillis(),
                )
            }
        }
        return id
    }

    fun enqueueAll(requests: List<AudioTranscodeRequest>): List<String> =
        enqueueAllInternal(requests, priority = false)

    /** Batch equivalent of [enqueuePriority]; source order is preserved inside the priority group. */
    fun enqueueAllPriority(requests: List<AudioTranscodeRequest>): List<String> =
        enqueueAllInternal(requests, priority = true)

    private fun enqueueAllInternal(
        requests: List<AudioTranscodeRequest>,
        priority: Boolean,
    ): List<String> {
        if (requests.isEmpty()) return emptyList()
        val staged = requests.map { request ->
            UUID.randomUUID().toString() to request
        }
        staged.forEach { (id, request) -> holders[id] = JobHolder(request) }
        mutateEntries { current ->
            val entries = staged.map { (id, request) ->
                AudioTranscodeQueueEntry(
                    id = id,
                    request = request,
                    state = AudioTranscodeQueueState.QUEUED,
                )
            }
            insertQueuedEntries(current, entries, priority)
        }
        if (executionEnabled && scheduleSignals.trySend(Unit).isFailure) {
            val failedIds = staged.mapTo(hashSetOf()) { it.first }
            failedIds.forEach(holders::remove)
            mutateEntries { current ->
                current.map { entry ->
                    if (entry.id !in failedIds) entry else {
                        val detail = "转换队列已关闭"
                        entry.copy(
                            state = AudioTranscodeQueueState.FAILED,
                            result = AudioTranscodeResult.Failure(
                                AudioTranscodeFailureReason.INTERNAL_ERROR,
                                detail,
                            ),
                            terminalSummary = AudioTranscodeQueueTerminalSummary(
                                failureReason = AudioTranscodeFailureReason.INTERNAL_ERROR,
                                detail = detail,
                            ),
                            finishedAtMs = System.currentTimeMillis(),
                        )
                    }
                }
            }
        }
        return staged.map { it.first }
    }

    fun cancel(id: String): Boolean {
        val holder = holders[id] ?: return false
        holder.task?.cancel()
        mutateEntry(id) { entry ->
            if (entry.state == AudioTranscodeQueueState.QUEUED) {
                entry.copy(
                    state = AudioTranscodeQueueState.CANCELLED,
                    result = AudioTranscodeResult.Cancelled,
                    terminalSummary = AudioTranscodeQueueTerminalSummary(detail = "任务已取消"),
                    progress = entry.progress.copy(
                        stage = AudioTranscodeStage.CANCELLED,
                        stagePermille = 1000,
                        overallPermille = entry.progress.overallPermille,
                    ),
                    finishedAtMs = System.currentTimeMillis(),
                )
            } else {
                entry
            }
        }
        return true
    }

    fun retry(id: String): String? {
        val entry = entries.value.firstOrNull { it.id == id } ?: return null
        if (entry.state != AudioTranscodeQueueState.FAILED &&
            entry.state != AudioTranscodeQueueState.CANCELLED
        ) return null
        val retryId = enqueue(entry.request)
        // A retry represents the same logical conversion attempt. Once the replacement is safely
        // queued, remove the stale terminal card so repeated retries do not accumulate a history of
        // identical failures in both Queue Management and Conversion Results.
        remove(id)
        return retryId
    }

    /** Remove a queued or terminal entry. A running task must be cancelled first. */
    fun remove(id: String): Boolean {
        val current = entry(id) ?: return false
        if (current.state == AudioTranscodeQueueState.RUNNING) return false
        holders.remove(id)?.task?.cancel()
        mutateEntries { entries -> entries.filterNot { it.id == id } }
        return true
    }

    /** Move an item inside the pending subset. Running/terminal entries keep their own slots. */
    fun moveQueued(id: String, delta: Int): Boolean {
        if (delta == 0) return false
        var changed = false
        mutateEntries { current ->
            val queuedPositions = current.indices.filter { current[it].state == AudioTranscodeQueueState.QUEUED }
            val fromQueuedIndex = queuedPositions.indexOfFirst { position -> current[position].id == id }
            if (fromQueuedIndex < 0) return@mutateEntries current
            val toQueuedIndex = (fromQueuedIndex + delta).coerceIn(0, queuedPositions.lastIndex)
            if (toQueuedIndex == fromQueuedIndex) return@mutateEntries current
            val mutable = current.toMutableList()
            val from = queuedPositions[fromQueuedIndex]
            val to = queuedPositions[toQueuedIndex]
            val swap = mutable[from]
            mutable[from] = mutable[to]
            mutable[to] = swap
            changed = true
            mutable
        }
        if (changed && executionEnabled) scheduleSignals.trySend(Unit)
        return changed
    }

    fun clearFinished() {
        val finishedIds = entries.value
            .asSequence()
            .filter { it.state in terminalStates }
            .mapTo(mutableSetOf()) { it.id }
        if (finishedIds.isEmpty()) return
        finishedIds.forEach(holders::remove)
        mutateEntries { current -> current.filterNot { it.id in finishedIds } }
    }

    fun entry(id: String): AudioTranscodeQueueEntry? = entries.value.firstOrNull { it.id == id }

    fun markAuthorizedSourceDeleteResult(
        id: String,
        deleted: Boolean,
        detail: String,
    ): Boolean {
        var changed = false
        mutateEntry(id) { entry ->
            if (entry.state != AudioTranscodeQueueState.COMPLETED || !entry.request.deleteSourceOnSuccess) {
                entry
            } else {
                val summary = entry.terminalSummary ?: AudioTranscodeQueueTerminalSummary()
                changed = true
                entry.copy(
                    terminalSummary = summary.copy(
                        postActionDetail = appendPostActionDetail(summary.postActionDetail, detail),
                        sourceDeleted = deleted,
                        sourceDeleteNeedsConsent = !deleted,
                    ),
                )
            }
        }
        return changed
    }

    private suspend fun runQueued(id: String) {
        val holder = holders[id] ?: return
        val queued = entry(id) ?: return
        if (queued.state != AudioTranscodeQueueState.QUEUED) return

        val task = AudioTranscodeManager.start(holder.request)
        holder.task = task
        mutateEntry(id) { entry ->
            entry.copy(
                state = AudioTranscodeQueueState.RUNNING,
                result = null,
                terminalSummary = null,
                startedAtMs = System.currentTimeMillis(),
                finishedAtMs = null,
            )
        }

        val result = coroutineScope {
            val progressJob = launch {
                task.progress.collect { progress ->
                    mutateEntry(id, persist = false) { entry -> entry.copy(progress = progress) }
                }
            }
            try {
                task.await()
            } finally {
                progressJob.cancel()
            }
        }
        holder.task = null
        val postActions = if (result is AudioTranscodeResult.Success) {
            val context = applicationContext
            if (context != null) {
                AudioTranscodePostProcessor.afterCommittedSuccess(
                    context = context,
                    request = holder.request,
                    output = result.output,
                )
            } else {
                AudioTranscodePostActionReport(
                    detail = "转换已完成，但系统媒体库刷新未执行：队列缺少 application context",
                    sourceDeleted = holder.request.deleteSourceOnSuccess.takeIf { it }?.let { false },
                    libraryScanRequested = false,
                )
            }
        } else {
            null
        }
        if (postActions?.libraryScanRequested == true) pendingLibraryScan = true
        if (result is AudioTranscodeResult.Success && holder.request.format.isDsd) {
            result.output.parentFile?.absolutePath?.let { outputDirectory ->
                synchronized(stateLock) {
                    pendingIncrementalLibraryPaths += outputDirectory
                }
            }
        }
        val finalState = when (result) {
            is AudioTranscodeResult.Success -> AudioTranscodeQueueState.COMPLETED
            is AudioTranscodeResult.Failure -> AudioTranscodeQueueState.FAILED
            AudioTranscodeResult.Cancelled -> AudioTranscodeQueueState.CANCELLED
        }
        mutateEntry(id) { entry ->
            entry.copy(
                state = finalState,
                progress = entry.progress.copy(
                    stage = when (finalState) {
                        AudioTranscodeQueueState.COMPLETED -> AudioTranscodeStage.COMPLETED
                        AudioTranscodeQueueState.FAILED -> AudioTranscodeStage.FAILED
                        AudioTranscodeQueueState.CANCELLED -> AudioTranscodeStage.CANCELLED
                        AudioTranscodeQueueState.QUEUED -> AudioTranscodeStage.QUEUED
                        AudioTranscodeQueueState.RUNNING -> entry.progress.stage
                    },
                    stagePermille = if (finalState in terminalStates) 1000 else entry.progress.stagePermille,
                    overallPermille = if (finalState == AudioTranscodeQueueState.COMPLETED) {
                        1000
                    } else {
                        entry.progress.overallPermille
                    },
                ),
                result = result,
                terminalSummary = terminalSummary(result, postActions),
                finishedAtMs = System.currentTimeMillis(),
            )
        }
        requestDeferredLibraryScanIfIdle()
    }

    private fun mutateEntry(
        id: String,
        persist: Boolean = true,
        transform: (AudioTranscodeQueueEntry) -> AudioTranscodeQueueEntry,
    ) {
        mutateEntries(persist = persist) { current ->
            current.map { entry -> if (entry.id == id) transform(entry) else entry }
        }
    }

    private fun mutateEntries(
        persist: Boolean = true,
        transform: (List<AudioTranscodeQueueEntry>) -> List<AudioTranscodeQueueEntry>,
    ) {
        val updated: List<AudioTranscodeQueueEntry>
        synchronized(stateLock) {
            updated = transform(_entries.value)
            _entries.value = updated
        }
        if (persist) persistSnapshot(updated)
    }

    private fun persistSnapshot(entries: List<AudioTranscodeQueueEntry>) {
        persistence?.scheduleWrite(entries)
    }

    private fun cleanupInterruptedPartials(request: AudioTranscodeRequest) {
        val output = File(request.outputPath)
        val parent = output.parentFile?.takeIf(File::isDirectory) ?: return
        val prefix = ".${output.nameWithoutExtension}.raws-partial-"
        val suffix = ".${request.format.extension}"
        parent.listFiles().orEmpty()
            .asSequence()
            .filter(File::isFile)
            .filter { candidate ->
                candidate.name.startsWith(prefix) && candidate.name.endsWith(suffix)
            }
            .forEach { candidate -> runCatching { candidate.delete() } }
    }

    private suspend fun requestDeferredLibraryScanIfIdle() {
        val idle = synchronized(stateLock) {
            _entries.value.none { entry ->
                entry.state == AudioTranscodeQueueState.QUEUED ||
                    entry.state == AudioTranscodeQueueState.RUNNING
            }
        }
        if (!idle) return
        val context = applicationContext ?: return

        val incrementalPaths = synchronized(stateLock) {
            pendingIncrementalLibraryPaths.toList().also { pendingIncrementalLibraryPaths.clear() }
        }
        if (incrementalPaths.isNotEmpty()) {
            val publishResult = LibraryIncrementalPathPublisher.scanAndUpsert(
                context = context,
                directories = incrementalPaths,
            )
            val suffix = publishResult.fold(
                onSuccess = { report ->
                    if (report.failedDirectories == 0) {
                        "DSD 输出已加入 RawSMusic 音乐库（${report.upserted} 首）"
                    } else {
                        "DSD 输出已增量扫描 ${report.upserted} 首；${report.failedDirectories} 个目录读取失败"
                    }
                },
                onFailure = { error ->
                    "DSD 输出已保存，但加入 RawSMusic 音乐库失败：${error.message ?: error::class.java.simpleName}"
                },
            )
            val normalizedPaths = incrementalPaths.mapTo(hashSetOf()) { File(it).absolutePath }
            mutateEntries { current ->
                current.map { entry ->
                    val outputPath = entry.terminalSummary?.outputPath
                    val outputDirectory = outputPath?.let { File(it).parentFile?.absolutePath }
                    if (entry.state != AudioTranscodeQueueState.COMPLETED ||
                        !entry.request.format.isDsd ||
                        outputDirectory == null || outputDirectory !in normalizedPaths
                    ) {
                        entry
                    } else {
                        val summary = entry.terminalSummary ?: AudioTranscodeQueueTerminalSummary()
                        entry.copy(
                            terminalSummary = summary.copy(
                                postActionDetail = appendPostActionDetail(summary.postActionDetail, suffix),
                            ),
                        )
                    }
                }
            }
        }

        if (!pendingLibraryScan) return
        val started = AudioTranscodePostProcessor.requestRawSMusicLibraryScan(context)
        if (started) pendingLibraryScan = false
        val suffix = if (started) {
            "RawSMusic 音乐库扫描已启动"
        } else {
            "RawSMusic 音乐库扫描启动失败"
        }
        mutateEntries { current ->
            current.map { entry ->
                if (entry.state != AudioTranscodeQueueState.COMPLETED ||
                    !entry.request.autoScanOnSuccess
                ) {
                    entry
                } else {
                    val summary = entry.terminalSummary ?: AudioTranscodeQueueTerminalSummary()
                    entry.copy(
                        terminalSummary = summary.copy(
                            postActionDetail = appendPostActionDetail(summary.postActionDetail, suffix),
                            autoScanRequested = true,
                        ),
                    )
                }
            }
        }
    }

    private fun appendPostActionDetail(existing: String?, next: String): String =
        if (existing.isNullOrBlank()) next else "$existing · $next"

    private fun terminalSummary(
        result: AudioTranscodeResult,
        postActions: AudioTranscodePostActionReport? = null,
    ): AudioTranscodeQueueTerminalSummary =
        when (result) {
            is AudioTranscodeResult.Success -> AudioTranscodeQueueTerminalSummary(
                outputPath = result.output.absolutePath,
                backend = result.backend,
                postActionDetail = postActions?.detail,
                sourceDeleted = postActions?.sourceDeleted,
                sourceDeleteNeedsConsent = postActions?.sourceDeleteNeedsConsent == true,
                autoScanRequested = postActions?.libraryScanRequested == true,
            )
            is AudioTranscodeResult.Failure -> AudioTranscodeQueueTerminalSummary(
                failureReason = result.reason,
                detail = result.detail,
                nativeCode = result.nativeCode,
            )
            AudioTranscodeResult.Cancelled -> AudioTranscodeQueueTerminalSummary(
                detail = "任务已取消",
            )
        }

    private val terminalStates = setOf(
        AudioTranscodeQueueState.COMPLETED,
        AudioTranscodeQueueState.FAILED,
        AudioTranscodeQueueState.CANCELLED,
    )
}

internal fun insertQueuedEntries(
    current: List<AudioTranscodeQueueEntry>,
    newEntries: List<AudioTranscodeQueueEntry>,
    priority: Boolean,
): List<AudioTranscodeQueueEntry> {
    if (newEntries.isEmpty()) return current
    if (!priority) return current + newEntries
    val insertion = current.indexOfFirst { it.state == AudioTranscodeQueueState.QUEUED }
        .takeIf { it >= 0 }
        ?: current.size
    return current.toMutableList().apply { addAll(insertion, newEntries) }
}
