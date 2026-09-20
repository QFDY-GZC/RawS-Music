package com.rawsmusic.ai.variant

import android.content.Context
import android.net.Uri
import android.util.Log
import com.rawsmusic.ai.instrument.AiInstalledInstrumentPack
import com.rawsmusic.ai.instrument.AiInstrumentPackStore
import com.rawsmusic.ai.melody.AiInstalledMelodyModel
import com.rawsmusic.ai.melody.AiMelodyExtractor
import com.rawsmusic.ai.melody.AiMelodyModelStore
import com.rawsmusic.ai.melody.AiPerformanceMidiStore
import com.rawsmusic.ai.melody.AiPerformanceTrackStore
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.ui.widget.player.PlayerAiInstrumentOption
import com.rawsmusic.core.ui.widget.player.PlayerAiPerformanceMode
import com.rawsmusic.core.ui.widget.player.PlayerAiPerformanceUiState
import com.rawsmusic.module.player.PlayerController
import com.rawsmusic.separation.AiSeparationJobPhase
import com.rawsmusic.separation.AiSeparationJobProgressBus
import com.rawsmusic.separation.AiSeparationJobService
import com.rawsmusic.separation.AiSeparationLiveStreamBus
import com.rawsmusic.separation.AiSeparationLiveStreamState
import com.rawsmusic.separation.AiStemDependency
import com.rawsmusic.separation.AiStemDependencyResolver
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal object AiPerformanceFeaturePolicy {
    fun missingPrerequisite(modelReady: Boolean, packReady: Boolean): String = when {
        !modelReady && !packReady -> "需要旋律模型和乐器"
        !modelReady -> "需要旋律模型"
        !packReady -> "需要乐器"
        else -> ""
    }

    fun modeFromSourceTag(sourceTag: String?): PlayerAiPerformanceMode = when {
        sourceTag?.startsWith("ai_variant:instrument_performance:") == true ->
            PlayerAiPerformanceMode.INSTRUMENT_PERFORMANCE
        sourceTag?.startsWith("ai_variant:vocal_ensemble:") == true ->
            PlayerAiPerformanceMode.VOCAL_ENSEMBLE
        else -> PlayerAiPerformanceMode.ORIGINAL
    }

    fun separationProgress(fraction: Float): Float =
        if (fraction > 0f) 0.03f + fraction.coerceIn(0f, 1f) * 0.41f else 0.03f
}

/** Coordinates the player-facing AI performance modes and the shared stem pipeline. */
class AiPerformanceFeatureController private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val stemResolver = AiStemDependencyResolver.get(appContext)
    private val melodyModelStore = AiMelodyModelStore.get(appContext)
    private val melodyExtractor = AiMelodyExtractor.get(appContext)
    private val performanceTrackStore = AiPerformanceTrackStore.get(appContext)
    private val midiStore = AiPerformanceMidiStore.get(appContext)
    private val instrumentPackStore = AiInstrumentPackStore.get(appContext)
    private val variantResolver = AiMediaVariantResolver.get(appContext)
    private val variantCoordinator = AiPerformanceVariantCoordinator.get(appContext)
    private val playbackCoordinator = AiMediaVariantPlaybackCoordinator.get(appContext)
    private val realtimePerformance = AiRealtimePerformanceController.get(appContext)
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val generationMutex = Mutex()
    private val cancelRequested = AtomicBoolean(false)

    @Volatile private var activeSeparationTaskId: String = ""
    @Volatile private var activeSeparationOwnedByAi: Boolean = false

    private val mutableState = MutableStateFlow(PlayerAiPerformanceUiState())
    val state: StateFlow<PlayerAiPerformanceUiState> = mutableState.asStateFlow()

    init {
        controllerScope.launch {
            combine(melodyModelStore.revision, instrumentPackStore.revision) { _, _ -> Unit }
                .collect {
                    PlayerController.getInstanceOrNull()?.currentSong?.value?.let { bindSong(it) }
                }
        }
    }

    suspend fun bindSong(song: AudioFile?) {
        if (song == null) {
            logI("bind_song song=null")
            mutableState.value = PlayerAiPerformanceUiState()
            return
        }
        val key = songKey(song)
        logI("bind_song title=${logTitle(song)} key=${logKey(song)}")
        if (realtimePerformance.modeFor(song) == null && mutableState.value.livePlayback) {
            realtimePerformance.deactivate()
        }
        val existing = mutableState.value
        val busyForSong = existing.busy && existing.songKey == key
        mutableState.value = inspect(
            song,
            busy = busyForSong,
            progress = if (busyForSong) existing.progress else 0f,
        )
    }

    suspend fun selectMode(song: AudioFile, mode: PlayerAiPerformanceMode) {
        logI(
            "select_mode title=${logTitle(song)} mode=$mode " +
                "state=${mutableState.value.mode} busy=${mutableState.value.busy} " +
                "live=${mutableState.value.livePlayback}",
        )
        when (mode) {
            PlayerAiPerformanceMode.ORIGINAL -> {
                if (mutableState.value.busy && mutableState.value.songKey == songKey(song)) cancel(song)
                realtimePerformance.deactivate()
                val result = playbackCoordinator.switchToOriginal(song)
                logI("restore_original result=$result title=${logTitle(song)}")
                publishInspected(song) { base ->
                    base.copy(
                        mode = if (result is AiMediaVariantPlaybackResult.OriginalActive) {
                            PlayerAiPerformanceMode.ORIGINAL
                        } else {
                            base.mode
                        },
                        busy = false,
                        livePlayback = false,
                        status = playbackMessage(result),
                    )
                }
            }
            PlayerAiPerformanceMode.INSTRUMENT_PERFORMANCE,
            PlayerAiPerformanceMode.VOCAL_ENSEMBLE -> selectGeneratedMode(song, mode)
        }
    }

    suspend fun selectInstrument(song: AudioFile, instrumentKey: String) {
        val pack = installedInstrumentPacks().firstOrNull { keyFor(it) == instrumentKey } ?: return
        preferences.edit().putString(PREF_INSTRUMENT_KEY, instrumentKey).apply()
        publishInspected(song) { it.copy(status = pack.manifest.displayName) }

        val liveMode = realtimePerformance.modeFor(song) ?: return
        val model = melodyModelStore.resolveRecommended() ?: return
        val dependency = stemResolver.resolve(song)
        val performance = dependency?.let { performanceTrackStore.load(it, model.descriptor) }
        if (performance != null) {
            realtimePerformance.activatePreparedMidi(song, liveMode, performance, pack)
            publishInspected(song) { it.copy(livePlayback = true, status = "已切换乐器") }
            return
        }
        currentSharedStream(song)?.let { stream ->
            realtimePerformance.activateSharedSeparation(song, liveMode, stream, pack)
            publishInspected(song) { it.copy(livePlayback = true, status = "已切换乐器") }
            return
        }
        realtimePerformance.activateLiveSeparation(song, liveMode, pack)
        publishInspected(song) { it.copy(livePlayback = true, status = "已切换乐器") }
    }

    suspend fun installRecommendedMelodyModel(sourceUri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val target = copyImportToCache(sourceUri, "rmvpe_q8.onnx.import", MAX_MELODY_MODEL_IMPORT_BYTES)
            try {
                melodyModelStore.installRecommendedFrom(target).getOrThrow()
                Unit
            } finally {
                target.delete()
            }
        }
    }

    suspend fun installInstrumentPack(sourceUri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val target = copyImportToCache(sourceUri, "instrument_pack.zip.import", MAX_INSTRUMENT_PACK_IMPORT_BYTES)
            try {
                instrumentPackStore.installFromZip(target).getOrThrow()
                Unit
            } finally {
                target.delete()
            }
        }
    }

    fun cancel(song: AudioFile) {
        logI(
            "cancel_requested title=${logTitle(song)} task=${activeSeparationTaskId.ifBlank { "none" }} " +
                "owned=${activeSeparationOwnedByAi} state=${mutableState.value.mode} " +
                "busy=${mutableState.value.busy} live=${mutableState.value.livePlayback}",
        )
        cancelRequested.set(true)
        if (activeSeparationOwnedByAi) {
            activeSeparationTaskId.takeIf(String::isNotBlank)?.let { taskId ->
                AiSeparationJobService.cancel(appContext, taskId)
            }
        }
        realtimePerformance.deactivate()
        publishFor(song) { it.copy(busy = false, livePlayback = false, status = "已关闭") }
        logI("cancel_applied title=${logTitle(song)}")
    }

    private suspend fun selectGeneratedMode(song: AudioFile, mode: PlayerAiPerformanceMode) {
        // Reset cancellation before this request can suspend. Keeping this in
        // generateAndActivate allowed a fast OFF action to be overwritten later.
        cancelRequested.set(false)
        if (!AiMediaVariantPlaybackPolicy.supportsSong(song)) {
            logW("select_generated unsupported title=${logTitle(song)} mode=$mode")
            publishInspected(song) { it.copy(status = "CUE 子轨暂不支持") }
            return
        }
        val player = PlayerController.getInstanceOrNull()
        val usbExclusive = player?.usbExclusiveActive?.value == true
        logI("select_generated title=${logTitle(song)} mode=$mode usbExclusive=$usbExclusive")
        val selection = selectionFor(mode)

        // Rendered media remains the USB route. Normal PCM always prefers shared stems + realtime synthesis.
        if (usbExclusive) {
            variantResolver.resolve(song, selection)?.let {
                realtimePerformance.deactivate()
                val result = playbackCoordinator.switchToVariant(song, selection)
                logI("switch_variant_cached title=${logTitle(song)} mode=$mode result=$result")
                publishInspected(song) { base ->
                    base.copy(
                        mode = if (result is AiMediaVariantPlaybackResult.VariantActive) mode else base.mode,
                        status = playbackMessage(result),
                    )
                }
                return
            }
        } else {
            if (realtimePerformance.modeFor(song) != null) {
                val pack = selectedInstrumentPack()
                if (pack != null) {
                    val shared = currentSharedStream(song)
                    if (shared != null) {
                        realtimePerformance.activateSharedSeparation(song, selection.mode, shared, pack)
                    } else {
                        realtimePerformance.activateLiveSeparation(song, selection.mode, pack)
                    }
                    publishInspected(song) {
                        it.copy(mode = mode, livePlayback = true, busy = false, status = "实时演奏")
                    }
                    return
                }
            }
        }

        generationMutex.withLock {
            generateAndActivate(song, mode, usbExclusive)
        }
    }

    private suspend fun generateAndActivate(
        song: AudioFile,
        requestedMode: PlayerAiPerformanceMode,
        usbExclusive: Boolean,
    ) {
        logI(
            "generation_start title=${logTitle(song)} mode=$requestedMode " +
                "usbExclusive=$usbExclusive",
        )
        val model = melodyModelStore.resolveRecommended()
        val pack = selectedInstrumentPack()
        val missing = AiPerformanceFeaturePolicy.missingPrerequisite(model != null, pack != null)
        if (missing.isNotBlank()) {
            logW("generation_prerequisite_missing title=${logTitle(song)} reason=$missing")
            publishInspected(song) { it.copy(prerequisite = missing, status = missing) }
            return
        }
        require(model != null && pack != null)
        publishInspected(song) { it.copy(busy = true, progress = 0f, status = "准备中") }

        var backgroundOwnsTask = false
        try {
            val cachedDependency = stemResolver.resolve(song)
            if (cachedDependency != null) {
                logI("generation_dependency_cache_hit title=${logTitle(song)}")
                activateFromDependency(song, requestedMode, cachedDependency, model, pack, usbExclusive)
                return
            }

            if (!usbExclusive) {
                val shared = waitForExistingSharedStem(song)
                check(!cancelRequested.get()) { CANCELLED_MESSAGE }
                if (shared != null && shared.active && shared.ready) {
                    logI("generation_shared_stem_ready title=${logTitle(song)} task=${shared.taskId}")
                    realtimePerformance.activateSharedSeparation(
                        song = song,
                        mode = selectionFor(requestedMode).mode,
                        stream = shared,
                        pack = pack,
                    ).getOrThrow()
                    publishInspected(song) { base ->
                        base.copy(
                            mode = requestedMode,
                            busy = false,
                            livePlayback = true,
                            progress = maxOf(base.progress, 0.20f),
                            status = "实时演奏",
                        )
                    }
                    val taskId = shared.taskId
                    backgroundOwnsTask = true
                    controllerScope.launch(Dispatchers.IO) {
                        finishSharedAnalysis(song, requestedMode, taskId, model)
                    }
                    return
                }
                stemResolver.resolve(song)?.let { completedWhileWaiting ->
                    activateFromDependency(
                        song,
                        requestedMode,
                        completedWhileWaiting,
                        model,
                        pack,
                        usbExclusive = false,
                    )
                    return
                }

                // No normal separation job is running for this song. Reuse the existing
                // process-local realtime separator instead of starting a second/offline job.
                publishProgress(song, 0.08f, "准备实时演奏")
                realtimePerformance.activateLiveSeparation(
                    song = song,
                    mode = selectionFor(requestedMode).mode,
                    pack = pack,
                ).getOrThrow()
                logI("generation_realtime_active title=${logTitle(song)} mode=$requestedMode")
                publishInspected(song) { base ->
                    base.copy(
                        mode = requestedMode,
                        busy = false,
                        livePlayback = true,
                        progress = maxOf(base.progress, 0.12f),
                        status = "实时演奏",
                    )
                }
                return
            }

            val dependency = ensureStemDependencyBlocking(song)
            logI("generation_dependency_ready title=${logTitle(song)}")
            activateFromDependency(song, requestedMode, dependency, model, pack, usbExclusive)
        } catch (error: Throwable) {
            val cancelled = cancelRequested.get() || error.message == CANCELLED_MESSAGE
            logE(
                "generation_end title=${logTitle(song)} cancelled=$cancelled " +
                    "message=${error.message.orEmpty()}",
                error,
            )
            val failedProgress = mutableState.value.progress
            publishInspected(song) { base ->
                base.copy(
                    busy = false,
                    progress = if (cancelled) 0f else failedProgress,
                    status = if (cancelled) "已取消" else (error.message ?: "AI 演奏失败"),
                )
            }
        } finally {
            if (!backgroundOwnsTask) clearActiveSeparation()
            cancelRequested.set(false)
            logI("generation_finally title=${logTitle(song)} backgroundOwnsTask=$backgroundOwnsTask")
        }
    }

    private suspend fun activateFromDependency(
        song: AudioFile,
        requestedMode: PlayerAiPerformanceMode,
        dependency: AiStemDependency,
        model: AiInstalledMelodyModel,
        pack: AiInstalledInstrumentPack,
        usbExclusive: Boolean,
    ) {
        check(!cancelRequested.get()) { CANCELLED_MESSAGE }
        publishProgress(song, 0.48f, "分析旋律")
        val performance = melodyExtractor.extract(
            dependency = dependency,
            model = model,
            onProgress = { fraction -> publishProgress(song, 0.48f + fraction * 0.22f, "分析旋律") },
            isCancelled = cancelRequested::get,
        ).getOrThrow()
        check(!cancelRequested.get()) { CANCELLED_MESSAGE }
        prewarmInstalledMidis(performance)

        if (!usbExclusive) {
            publishProgress(song, 0.74f, "准备演奏")
            realtimePerformance.activatePreparedMidi(
                song = song,
                mode = selectionFor(requestedMode).mode,
                performance = performance,
                pack = pack,
            ).getOrThrow()
            publishInspected(song) { base ->
                base.copy(
                    mode = requestedMode,
                    busy = false,
                    livePlayback = true,
                    progress = 1f,
                    status = "实时演奏",
                )
            }
            return
        }

        publishProgress(song, 0.74f, "生成离线版本")
        variantCoordinator.prepareInstrumentVariants(
            dependency = dependency,
            performance = performance,
            pack = pack,
            onProgress = { fraction -> publishProgress(song, 0.74f + fraction * 0.24f, "生成离线版本") },
            isCancelled = cancelRequested::get,
        )
        check(!cancelRequested.get()) { CANCELLED_MESSAGE }
        realtimePerformance.deactivate()
        val result = playbackCoordinator.switchToVariant(song, selectionFor(requestedMode))
        publishInspected(song) { base ->
            base.copy(
                mode = if (result is AiMediaVariantPlaybackResult.VariantActive) requestedMode else base.mode,
                busy = false,
                progress = 1f,
                status = playbackMessage(result),
            )
        }
    }

    /** Attach only when the normal separation feature is already processing this exact song. */
    private suspend fun waitForExistingSharedStem(song: AudioFile): AiSeparationLiveStreamState? {
        val source = sourceUri(song).toString()
        val running = AiSeparationJobProgressBus.state.value
        if (!running.active || running.sourceIdentity != source) return null
        val taskId = running.taskId
        activeSeparationTaskId = taskId
        activeSeparationOwnedByAi = false
        publishProgress(song, 0.03f, "复用分离")

        val pair = combine(
            AiSeparationJobProgressBus.state,
            AiSeparationLiveStreamBus.state,
        ) { progress, live -> progress to live }
            .first { (progress, live) ->
                if (progress.taskId == taskId) {
                    publishProgress(
                        song,
                        AiPerformanceFeaturePolicy.separationProgress(progress.fraction),
                        if (progress.phase == AiSeparationJobPhase.SEPARATING) "分离中" else progressShortLabel(progress.phase),
                    )
                }
                val currentPosition = PlayerController.getInstanceOrNull()?.position?.value?.coerceAtLeast(0L) ?: 0L
                val requiredFrame = ((currentPosition + SHARED_LEAD_MS) * live.sampleRate / 1000L)
                val readyForPlayback = live.taskId == taskId && live.active && live.ready &&
                    live.availableFrames >= requiredFrame
                val terminal = progress.taskId == taskId && progress.phase in TERMINAL_SEPARATION_PHASES
                readyForPlayback || terminal || cancelRequested.get()
            }
        if (cancelRequested.get()) error(CANCELLED_MESSAGE)
        val (progress, live) = pair
        if (live.taskId == taskId && live.active && live.ready) return live
        when (progress.phase) {
            AiSeparationJobPhase.COMPLETED -> return null
            AiSeparationJobPhase.CANCELLED -> error(CANCELLED_MESSAGE)
            AiSeparationJobPhase.FAILED -> error(progress.message.ifBlank { "人声分离失败" })
            else -> return null
        }
    }

    private suspend fun finishSharedAnalysis(
        song: AudioFile,
        requestedMode: PlayerAiPerformanceMode,
        taskId: String,
        model: AiInstalledMelodyModel,
    ) {
        try {
            val terminal = AiSeparationJobProgressBus.state.first { progress ->
                progress.taskId == taskId && progress.phase in TERMINAL_SEPARATION_PHASES
            }
            if (terminal.phase != AiSeparationJobPhase.COMPLETED) return
            val dependency = stemResolver.resolve(song) ?: return
            val performance = melodyExtractor.extract(
                dependency = dependency,
                model = model,
                onProgress = {},
                isCancelled = { false },
            ).getOrNull() ?: return
            prewarmInstalledMidis(performance)
            val current = PlayerController.getInstanceOrNull()?.currentSong?.value ?: return
            if (songKey(current) != songKey(song)) return
            if (realtimePerformance.modeFor(song) != selectionFor(requestedMode).mode) return
            val pack = selectedInstrumentPack() ?: return
            realtimePerformance.activatePreparedMidi(
                song = song,
                mode = selectionFor(requestedMode).mode,
                performance = performance,
                pack = pack,
            )
            publishInspected(song) { it.copy(livePlayback = true, busy = false, progress = 1f, status = "实时演奏") }
        } finally {
            if (activeSeparationTaskId == taskId) clearActiveSeparation()
        }
    }

    private suspend fun ensureStemDependencyBlocking(song: AudioFile): AiStemDependency {
        stemResolver.resolve(song)?.let {
            publishProgress(song, 0.45f, "复用分轨")
            return it
        }
        val source = sourceUri(song)
        val handle = AiSeparationJobService.startOrAttachShared(appContext, source, song.displayName).getOrThrow()
        activeSeparationTaskId = handle.taskId
        activeSeparationOwnedByAi = handle.ownedByCaller
        val terminal = AiSeparationJobProgressBus.state.first { progress ->
            if (progress.taskId == handle.taskId) {
                publishProgress(
                    song,
                    AiPerformanceFeaturePolicy.separationProgress(progress.fraction),
                    progressShortLabel(progress.phase),
                )
            }
            progress.taskId == handle.taskId && progress.phase in TERMINAL_SEPARATION_PHASES
        }
        when (terminal.phase) {
            AiSeparationJobPhase.COMPLETED -> Unit
            AiSeparationJobPhase.CANCELLED -> error(CANCELLED_MESSAGE)
            AiSeparationJobPhase.FAILED -> error(terminal.message.ifBlank { "人声分离失败" })
            else -> error("人声分离未完成")
        }
        return stemResolver.resolve(song) ?: error("分轨结果不可用")
    }

    private suspend fun inspect(
        song: AudioFile,
        busy: Boolean = false,
        progress: Float = 0f,
    ): PlayerAiPerformanceUiState = withContext(Dispatchers.IO) {
        val supported = AiMediaVariantPlaybackPolicy.supportsSong(song)
        val installedModel = melodyModelStore.resolveRecommended()
        val modelReady = installedModel != null
        val packs = installedInstrumentPacks()
        val selectedPack = selectedInstrumentPack(packs)
        val packReady = selectedPack != null
        val dependency = if (supported) stemResolver.resolve(song) else null
        val midiAnalysisReady = if (dependency != null && installedModel != null && packReady) {
            performanceTrackStore.load(dependency, installedModel.descriptor) != null
        } else false
        val instrumentReady = if (supported) {
            midiAnalysisReady ||
                variantResolver.resolve(song, AiMediaVariantSelection(AiMediaVariantMode.INSTRUMENT_PERFORMANCE)) != null
        } else false
        val ensembleReady = if (supported) {
            midiAnalysisReady ||
                variantResolver.resolve(song, AiMediaVariantSelection(AiMediaVariantMode.VOCAL_ENSEMBLE)) != null
        } else false
        val liveMode = realtimePerformance.modeFor(song)
        val activeMode = liveMode?.let(::modeFromVariant) ?: currentMode(song)
        val prerequisite = when {
            !supported -> "CUE 子轨暂不支持"
            instrumentReady || ensembleReady -> ""
            else -> AiPerformanceFeaturePolicy.missingPrerequisite(modelReady, packReady)
        }
        val selectedKey = selectedPack?.let(::keyFor).orEmpty()
        PlayerAiPerformanceUiState(
            songKey = songKey(song),
            supported = supported,
            mode = activeMode,
            busy = busy,
            livePlayback = liveMode != null,
            progress = progress.coerceIn(0f, 1f),
            status = when {
                busy -> "准备中"
                liveMode != null -> "实时演奏"
                activeMode == PlayerAiPerformanceMode.INSTRUMENT_PERFORMANCE -> "乐器演奏"
                activeMode == PlayerAiPerformanceMode.VOCAL_ENSEMBLE -> "人声合奏"
                else -> "关闭"
            },
            instrumentPerformanceReady = instrumentReady,
            vocalEnsembleReady = ensembleReady,
            generationAvailable = supported && modelReady && packReady,
            melodyModelReady = modelReady,
            instrumentPackReady = packReady,
            prerequisite = prerequisite,
            instruments = packs.map { pack ->
                PlayerAiInstrumentOption(
                    key = keyFor(pack),
                    label = pack.manifest.displayName,
                    iconKey = iconKeyFor(pack.manifest.instrument),
                    selected = keyFor(pack) == selectedKey,
                )
            },
            selectedInstrumentKey = selectedKey,
        )
    }

    private fun installedInstrumentPacks(): List<AiInstalledInstrumentPack> = instrumentPackStore.installedPacks()
        .sortedWith(
            compareBy<AiInstalledInstrumentPack> { instrumentSortOrder(it.manifest.instrument) }
                .thenBy { it.manifest.displayName.lowercase() }
                .thenByDescending { it.manifest.version },
        )

    private fun selectedInstrumentPack(
        packs: List<AiInstalledInstrumentPack> = installedInstrumentPacks(),
    ): AiInstalledInstrumentPack? {
        if (packs.isEmpty()) return null
        val saved = preferences.getString(PREF_INSTRUMENT_KEY, null)
        val selected = packs.firstOrNull { keyFor(it) == saved }
            ?: packs.firstOrNull { it.manifest.instrument.equals("piano", ignoreCase = true) }
            ?: packs.first()
        val key = keyFor(selected)
        if (saved != key) preferences.edit().putString(PREF_INSTRUMENT_KEY, key).apply()
        return selected
    }

    private fun keyFor(pack: AiInstalledInstrumentPack): String =
        "${pack.manifest.id}@${pack.manifest.version}"

    private fun iconKeyFor(instrument: String): String {
        val key = instrument.trim().lowercase().replace('-', '_').replace(' ', '_')
        return when {
            key.contains("electric_guitar") || key.contains("guitar") -> "guitar"
            key.contains("cello") || key.contains("violin") || key.contains("string") || key.contains("koto") || key.contains("guzheng") -> "cello"
            key.contains("drum") || key.contains("handpan") || key.contains("triangle") || key.contains("tambourine") -> "drum"
            key.contains("sax") -> "saxophone"
            key.contains("trumpet") || key.contains("brass") || key.contains("suona") -> "trumpet"
            key.contains("flute") || key.contains("hulusi") || key.contains("dizi") || key.contains("kazoo") -> "saxophone"
            key.contains("piano") || key.contains("keyboard") || key.contains("music_box") || key.contains("serinette") -> "piano"
            else -> "midi"
        }
    }

    private fun instrumentSortOrder(instrument: String): Int = when (iconKeyFor(instrument)) {
        "piano" -> 0
        "guitar" -> 1
        "cello" -> 2
        "saxophone" -> 3
        "trumpet" -> 4
        "drum" -> 5
        else -> 6
    }

    /** MIDI is cheap once melody analysis exists, so cache one SMF identity per installed timbre. */
    private fun prewarmInstalledMidis(performance: com.rawsmusic.ai.melody.AiPerformanceTrack) {
        val instruments = installedInstrumentPacks()
            .map { it.manifest.instrument }
            .distinctBy { it.trim().lowercase() }
        controllerScope.launch(Dispatchers.IO) {
            instruments.forEach { instrument ->
                runCatching { midiStore.prepare(performance, instrument) }
            }
        }
    }

    private fun currentSharedStream(song: AudioFile): AiSeparationLiveStreamState? {
        val source = runCatching { sourceUri(song).toString() }.getOrNull() ?: return null
        return AiSeparationLiveStreamBus.state.value.takeIf { it.active && it.sourceIdentity == source && it.ready }
    }

    private fun progressShortLabel(phase: AiSeparationJobPhase): String = when (phase) {
        AiSeparationJobPhase.PREPARING, AiSeparationJobPhase.DECODING, AiSeparationJobPhase.LOADING_MODEL -> "准备分离"
        AiSeparationJobPhase.SEPARATING -> "分离中"
        AiSeparationJobPhase.COMMITTING -> "保存分轨"
        AiSeparationJobPhase.COMPLETED -> "分离完成"
        AiSeparationJobPhase.CANCELLED -> "已取消"
        AiSeparationJobPhase.FAILED -> "分离失败"
        AiSeparationJobPhase.IDLE -> "等待"
    }

    private fun clearActiveSeparation() {
        activeSeparationTaskId = ""
        activeSeparationOwnedByAi = false
    }

    private fun modeFromVariant(mode: AiMediaVariantMode): PlayerAiPerformanceMode = when (mode) {
        AiMediaVariantMode.INSTRUMENT_PERFORMANCE -> PlayerAiPerformanceMode.INSTRUMENT_PERFORMANCE
        AiMediaVariantMode.VOCAL_ENSEMBLE -> PlayerAiPerformanceMode.VOCAL_ENSEMBLE
    }

    private fun currentMode(song: AudioFile): PlayerAiPerformanceMode {
        val override = PlayerController.getInstanceOrNull()?.playbackSourceOverride?.value
        if (override == null || !override.matches(song)) return PlayerAiPerformanceMode.ORIGINAL
        return AiPerformanceFeaturePolicy.modeFromSourceTag(override.sourceTag)
    }

    private fun selectionFor(mode: PlayerAiPerformanceMode): AiMediaVariantSelection = when (mode) {
        PlayerAiPerformanceMode.INSTRUMENT_PERFORMANCE ->
            AiMediaVariantSelection(AiMediaVariantMode.INSTRUMENT_PERFORMANCE)
        PlayerAiPerformanceMode.VOCAL_ENSEMBLE ->
            AiMediaVariantSelection(AiMediaVariantMode.VOCAL_ENSEMBLE)
        PlayerAiPerformanceMode.ORIGINAL -> error("Original source is not an AI media variant")
    }

    private fun sourceUri(song: AudioFile): Uri {
        val path = song.path.trim()
        require(path.isNotBlank()) { "歌曲路径为空" }
        return when {
            path.startsWith("content://", ignoreCase = true) || path.startsWith("file://", ignoreCase = true) -> Uri.parse(path)
            path.startsWith("http://", ignoreCase = true) || path.startsWith("https://", ignoreCase = true) ->
                error("AI 演奏仅支持本地歌曲")
            else -> {
                val file = File(path)
                require(file.isFile) { "找不到歌曲文件" }
                Uri.fromFile(file)
            }
        }
    }

    private fun copyImportToCache(sourceUri: Uri, name: String, maxBytes: Long): File {
        val importDir = File(appContext.cacheDir, "ai_performance/import").apply { mkdirs() }
        val target = File(importDir, "${System.nanoTime()}_$name")
        var written = 0L
        try {
            appContext.contentResolver.openInputStream(sourceUri)?.use { raw ->
                BufferedInputStream(raw, IMPORT_BUFFER_BYTES).use { input ->
                    BufferedOutputStream(FileOutputStream(target), IMPORT_BUFFER_BYTES).use { output ->
                        val buffer = ByteArray(IMPORT_BUFFER_BYTES)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            written += read
                            require(written <= maxBytes) { "导入文件过大" }
                            output.write(buffer, 0, read)
                        }
                    }
                }
            } ?: error("无法读取文件")
            require(written > 0L) { "文件为空" }
            return target
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    private fun publishProgress(song: AudioFile, progress: Float, message: String) {
        val previousStatus = mutableState.value.status
        publishFor(song) { current ->
            current.copy(
                busy = true,
                progress = maxOf(current.progress, progress.coerceIn(0f, 1f)),
                status = message,
            )
        }
        if (previousStatus != message) {
            logI("progress title=${logTitle(song)} status=$message progress=${progress.coerceIn(0f, 1f)}")
        }
    }

    private suspend fun publishInspected(
        song: AudioFile,
        transform: (PlayerAiPerformanceUiState) -> PlayerAiPerformanceUiState,
    ) {
        val base = inspect(song)
        publishFor(song) { transform(base) }
    }

    private fun publishFor(
        song: AudioFile,
        transform: (PlayerAiPerformanceUiState) -> PlayerAiPerformanceUiState,
    ) {
        val key = songKey(song)
        val current = mutableState.value
        if (current.songKey.isNotBlank() && current.songKey != key) return
        mutableState.value = transform(current).copy(songKey = key)
    }

    private fun playbackMessage(result: AiMediaVariantPlaybackResult): String = when (result) {
        is AiMediaVariantPlaybackResult.VariantActive -> if (result.changed) "已切换" else "正在播放"
        AiMediaVariantPlaybackResult.OriginalActive -> "已关闭"
        AiMediaVariantPlaybackResult.VariantNotReady -> "尚未准备"
        AiMediaVariantPlaybackResult.UnsupportedCue -> "CUE 子轨暂不支持"
        AiMediaVariantPlaybackResult.NoCurrentSong -> "没有正在播放的歌曲"
        AiMediaVariantPlaybackResult.StaleSong -> "歌曲已切换"
        AiMediaVariantPlaybackResult.SourceUnavailable -> "文件不可用"
        AiMediaVariantPlaybackResult.PlayerUnavailable -> "播放器不可用"
        AiMediaVariantPlaybackResult.Superseded -> "请求已更新"
    }

    private fun songKey(song: AudioFile): String =
        "${song.path}|${song.cueOffsetMs}|${song.cueTrackIndex}"

    private fun logTitle(song: AudioFile): String =
        song.displayName.replace(Regex("\\s+"), " ").trim().take(80)

    private fun logKey(song: AudioFile): String =
        Integer.toHexString(songKey(song).hashCode())

    private fun logI(message: String) {
        Log.i(LOG_TAG, message)
    }

    private fun logW(message: String) {
        Log.w(LOG_TAG, message)
    }

    private fun logE(message: String, error: Throwable) {
        Log.e(LOG_TAG, message, error)
    }

    companion object {
        private const val LOG_TAG = "AiPerformance"
        private const val PREFERENCES_NAME = "ai_performance_ui"
        private const val PREF_INSTRUMENT_KEY = "instrument_key"
        private const val SHARED_LEAD_MS = 1_000L
        private const val CANCELLED_MESSAGE = "AI performance generation cancelled"
        private const val IMPORT_BUFFER_BYTES = 256 * 1024
        private const val MAX_MELODY_MODEL_IMPORT_BYTES = 128L * 1024L * 1024L
        private const val MAX_INSTRUMENT_PACK_IMPORT_BYTES = 2L * 1024L * 1024L * 1024L
        private val TERMINAL_SEPARATION_PHASES = setOf(
            AiSeparationJobPhase.COMPLETED,
            AiSeparationJobPhase.FAILED,
            AiSeparationJobPhase.CANCELLED,
        )
        @Volatile private var instance: AiPerformanceFeatureController? = null

        fun get(context: Context): AiPerformanceFeatureController = instance ?: synchronized(this) {
            instance ?: AiPerformanceFeatureController(context.applicationContext).also { instance = it }
        }
    }
}
