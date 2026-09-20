package com.rawsmusic.separation

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.rawsmusic.core.common.ffmpeg.FFmpegBridge
import com.rawsmusic.module.player.RealtimePlaybackPcmProcessor
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.concurrent.Executors
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Model-backed, process-local playback separator.
 *
 * Two MDX chunks are queued before normal separated playback starts. The second queued chunk is
 * the realtime safety margin: while chunk N is being heard, the AI worker can finish chunk N+1
 * without ever blocking the AudioTrack write path. AI-performance mode is different because it
 * deliberately passes the dry PCM through during transformer warm-up, so that path still uses a
 * single initial chunk below.
 */
object AiRealtimeOnnxPcmProcessor : RealtimePlaybackPcmProcessor, Closeable {
    private const val TAG = "AiRealtimeOnnx"
    private const val CHANNELS = 2
    private const val INITIAL_SEGMENTS = 2
    private const val MAX_WAIT_MS = 15_000L
    private const val SHARED_TRANSFORM_BLOCK_MS = 750
    private const val SHARED_TRANSFORM_MIN_FRAMES = 12_288

    private val lock = Object()
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "RawS-AI-Realtime-MDX").apply { isDaemon = true }
    }
    private val transformExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "RawS-AI-Realtime-MID").apply { isDaemon = true }
    }
    private val tasks = ArrayDeque<SegmentTask>()
    private val transformTasks = ArrayDeque<TransformTask>()
    private val outputs = ArrayDeque<OutputBlock>()
    private val modelSamples = FloatQueue()
    private val sharedMixtureSamples = FloatQueue()

    @Volatile private var initialized = false
    @Volatile private var desiredEnabled = false
    @Volatile private var enabled = false
    @Volatile private var ready = false
    @Volatile private var stem = AiSeparationStem.VOCALS
    @Volatile private var strength = 1f
    @Volatile private var generation = 0L
    @Volatile private var modelOpenGeneration = 0L
    @Volatile private var modelOpenInFlight = false
    @Volatile private var onPreparingChanged: (Boolean) -> Unit = {}
    @Volatile private var onPhaseChanged: (AiRealtimeSeparationPhase) -> Unit = {}
    @Volatile private var onFailure: (String) -> Unit = {}
    @Volatile private var publishedPhase = AiRealtimeSeparationPhase.IDLE
    @Volatile private var playbackPositionProvider: () -> Long = { 0L }
    @Volatile private var songIdentityProvider: () -> String = { "" }
    @Volatile private var separatedBlockTransformer: AiRealtimeSeparatedBlockTransformer? = null

    private lateinit var appContext: Context
    private var runtimeSession: AiOnnxRuntimeSession? = null
    private var installedModel: AiSeparationInstalledModel? = null
    private var contract: AiSeparationModelContract? = null
    private var inputResampler: StreamingStereoResampler? = null
    private var sourceFormat: PcmFormat? = null
    private var previousContext = FloatArray(0)
    private var submittedSegments = 0
    private var playbackStarted = false
    private var workerScheduled = false
    private var transformWorkerScheduled = false
    private var outputOffsetFrames = 0
    private var modelTimelineStartMs = 0L
    private var submittedTimelineFrames = 0L
    private var submittedSourceFrames = 0L
    private var performanceTimelineFrames = 0L
    private var inputEnded = false
    private var cachedDependency: AiStemDependency? = null
    private var cachedSongIdentity = ""
    private var cachedDecoder = 0L
    private var cachedDecoderPath = ""
    private var cachedDecodeBuffer = ByteArray(0)
    private var sharedStreamTaskId = ""
    private var sharedPlaybackSongIdentity = ""
    private var sharedTimelineStartMs = 0L
    private var sharedSubmittedFrames = 0L
    private var firstInputLogged = false
    private var performanceWarmupLogged = false
    // Single model worker owns these buffers. Reuse them across chunks so a 5.9s MDX segment does
    // not allocate two multi-megabyte direct buffers on every inference boundary.
    private var mixtureInferenceBuffer: ByteBuffer? = null
    private var vocalInferenceBuffer: ByteBuffer? = null

    override val active: Boolean
        get() = enabled && (
            ready ||
                sharedStreamTaskId.isNotBlank() ||
                (cachedDependency != null &&
                    (separatedBlockTransformer == null || separatedBlockTransformer?.realtimePlaybackSafe == true))
            )

    fun initialize(
        context: Context,
        onPreparing: (Boolean) -> Unit,
        onPhase: (AiRealtimeSeparationPhase) -> Unit,
        onError: (String) -> Unit,
        positionProvider: () -> Long,
        currentSongIdentity: () -> String,
    ) {
        appContext = context.applicationContext
        onPreparingChanged = onPreparing
        onPhaseChanged = onPhase
        onFailure = onError
        playbackPositionProvider = positionProvider
        songIdentityProvider = currentSongIdentity
        initialized = true
    }

    fun setEnabled(value: Boolean) {
        check(initialized) { "Realtime ONNX processor is not initialized" }
        desiredEnabled = value
        if (!value) {
            enabled = false
            ready = false
            val disableModelGeneration = synchronized(lock) {
                modelOpenInFlight = false
                ++modelOpenGeneration
            }
            onPreparingChanged(false)
            publishPhase(AiRealtimeSeparationPhase.IDLE)
            reset("disabled")
            executor.execute {
                synchronized(lock) {
                    if (!desiredEnabled && disableModelGeneration == modelOpenGeneration) {
                        runtimeSession?.close()
                        runtimeSession = null
                        installedModel = null
                        contract = null
                    }
                }
            }
            Log.i(TAG, "AI_REALTIME_MODEL disabled storage=memory")
            return
        }
        armEnabled("set_enabled")
    }

    private fun armEnabled(reason: String) {
        if (!desiredEnabled) return
        val cachedDirect = synchronized(lock) { canUseCachedDirectLocked() }
        val sharedDirect = synchronized(lock) { canUseSharedDirectLocked() }
        if (enabled && (ready || cachedDirect || sharedDirect || modelOpenInFlight)) return
        enabled = true
        Log.i(
            TAG,
            "AI_REALTIME_MODEL enable reason=$reason ready=$ready opening=$modelOpenInFlight " +
                "cachedDirect=$cachedDirect sharedDirect=$sharedDirect",
        )
        if (cachedDirect) {
            ready = false
            onPreparingChanged(false)
            publishPhase(AiRealtimeSeparationPhase.ACTIVE)
            Log.i(TAG, "AI_REALTIME_MODEL cached-direct enabled; model inference not required")
            return
        }
        if (sharedDirect) {
            ready = false
            onPreparingChanged(true)
            publishPhase(AiRealtimeSeparationPhase.BUFFERING_AUDIO)
            Log.i(TAG, "AI_REALTIME_MODEL shared-stem enabled; second separation model not required")
            return
        }
        ready = false
        onPreparingChanged(true)
        publishPhase(AiRealtimeSeparationPhase.LOADING_MODEL)
        val openGeneration = synchronized(lock) {
            modelOpenInFlight = true
            ++modelOpenGeneration
        }
        executor.execute {
            runCatching {
                val store = AiSeparationPluginStore.get(appContext)
                val selected = requireNotNull(store.preferredRealtimeInstalledModel()) {
                    "请先下载并选择可实时运行的 MDX 人声分离模型"
                }
                val modelContract = requireNotNull(selected.catalog.contract) {
                    "当前模型不包含可执行参数"
                }
                require(selected.executable) { "当前模型不可执行" }
                require(modelContract.tensorLayout == "bcft_complex_channels") {
                    "当前高质量波形模型仅支持离线分离；实时播放请选择 MDX 模型"
                }
                val modelFile = File(selected.directory, selected.catalog.modelFile).takeIf(File::isFile)
                    ?: error("当前实时模型文件不存在")
                val session = AiOnnxRuntimeSession.open(appContext, modelFile, modelContract)
                val accepted = synchronized(lock) {
                    val directNow = canUseCachedDirectLocked() || canUseSharedDirectLocked()
                    if (!desiredEnabled || !enabled || openGeneration != modelOpenGeneration || directNow) {
                        session.close()
                        if (openGeneration == modelOpenGeneration) modelOpenInFlight = false
                        false
                    } else {
                        runtimeSession?.close()
                        runtimeSession = session
                        installedModel = selected
                        contract = modelContract
                        clearPipelineLocked("model_ready")
                        ready = true
                        modelOpenInFlight = false
                        true
                    }
                }
                if (!accepted) return@runCatching
                publishPhase(AiRealtimeSeparationPhase.BUFFERING_AUDIO)
                Log.i(
                    TAG,
                    "AI_REALTIME_MODEL ready model=${selected.catalog.id} " +
                        "segment=${selected.catalog.segmentSamples} sr=${selected.catalog.sampleRate}",
                )
            }.onFailure { error ->
                if (openGeneration == modelOpenGeneration) {
                    modelOpenInFlight = false
                    desiredEnabled = false
                    enabled = false
                    ready = false
                    onPreparingChanged(false)
                    onFailure(error.message ?: error.javaClass.simpleName)
                    Log.e(TAG, "AI_REALTIME_MODEL open failed", error)
                }
            }
        }
    }

    fun setStem(value: AiSeparationStem) {
        if (stem != value) {
            synchronized(lock) {
                closeCachedDecoderLocked()
            }
        }
        stem = value
    }

    fun setStrength(value: Float) {
        strength = value.coerceIn(0f, 1f)
    }

    fun setSeparatedBlockTransformer(value: AiRealtimeSeparatedBlockTransformer?) {
        val previous = separatedBlockTransformer
        if (previous === value) return
        separatedBlockTransformer = value
        reset("separated_transform_changed")
        if (previous != null) {
            // Serialize close after an in-flight transform has returned. reset() clears queued stale work.
            transformExecutor.execute { previous.runCatching { close() } }
        }
    }

    fun setCachedDependency(dependency: AiStemDependency?, songIdentity: String) {
        synchronized(lock) {
            if (cachedDependency?.dependencyFingerprint == dependency?.dependencyFingerprint &&
                cachedSongIdentity == songIdentity
            ) return
            closeCachedDecoderLocked()
            cachedDependency = dependency
            cachedSongIdentity = songIdentity
            clearPipelineLocked("cached_dependency_changed")
        }
        val cachedDirect = synchronized(lock) { canUseCachedDirectLocked() }
        if (enabled && cachedDirect && dependency != null) {
            publishPhase(AiRealtimeSeparationPhase.ACTIVE)
            onPreparingChanged(false)
            Log.i(
                TAG,
                "AI_REALTIME_CACHE hit dependency=${dependency.dependencyFingerprint.take(12)} " +
                    "result=${dependency.separationResultId} format=${dependency.outputFormat}",
            )
        } else if (enabled && !ready && sharedStreamTaskId.isBlank()) {
            // A cached result disappeared and no shared separation owns the stem stream.
            setEnabled(true)
        }
    }

    fun setSharedLiveStream(stream: AiSeparationLiveStreamState?, playbackSongIdentity: String) {
        synchronized(lock) {
            val nextTaskId = stream?.takeIf { it.active && it.sourceIdentity.isNotBlank() }?.taskId.orEmpty()
            if (sharedStreamTaskId == nextTaskId && sharedPlaybackSongIdentity == playbackSongIdentity) return
            sharedStreamTaskId = nextTaskId
            sharedPlaybackSongIdentity = if (nextTaskId.isBlank()) "" else playbackSongIdentity
            clearPipelineLocked("shared_live_stream_changed")
        }
        if (enabled && stream != null && stream.active && stream.ready) {
            ready = false
            onPreparingChanged(true)
            publishPhase(AiRealtimeSeparationPhase.BUFFERING_AUDIO)
        }
    }

    override fun process(
        buffer: ByteArray,
        byteCount: Int,
        channels: Int,
        sampleRate: Int,
        bitsPerSample: Int,
        floatEncoding: Boolean,
    ): Int {
        if (!active || byteCount <= 0) return byteCount
        if (channels !in 1..2 || sampleRate <= 0 || bitsPerSample <= 1) {
            fail("实时人声分离当前仅支持单声道或双声道 PCM")
            return byteCount
        }
        val bytesPerSample = if (bitsPerSample <= 16) 2 else 4
        val frameSize = channels * bytesPerSample
        val frames = byteCount / frameSize
        if (frames <= 0) return 0
        val format = PcmFormat(
            channels = channels,
            sampleRate = sampleRate,
            bitsPerSample = bitsPerSample,
            floatEncoding = floatEncoding,
        )
        val dry = decodeStereo(buffer, frames, format)

        synchronized(lock) {
            if (!active) return byteCount
            if (!firstInputLogged) {
                firstInputLogged = true
                Log.i(
                    TAG,
                    "AI_REALTIME_MODEL first_input position_ms=${playbackPositionProvider().coerceAtLeast(0L)} " +
                        "channels=$channels sr=$sampleRate bits=$bitsPerSample " +
                        "cachedDirect=${canUseCachedDirectLocked()} sharedDirect=${canUseSharedDirectLocked()}",
                )
            }
            val directCached = canUseCachedDirectLocked()
            if (directCached) {
                if (cachedSongIdentity != songIdentityProvider()) {
                    closeCachedDecoderLocked()
                    cachedDependency = null
                    return byteCount
                }
                return processCachedLocked(buffer, byteCount, frames, format, dry)
            }
            if (canUseSharedDirectLocked()) {
                if (sharedPlaybackSongIdentity != songIdentityProvider()) {
                    sharedStreamTaskId = ""
                    sharedPlaybackSongIdentity = ""
                    clearPipelineLocked("shared_song_changed")
                    return byteCount
                }
                return processSharedLiveLocked(buffer, frames, format, dry)
            }
            if (inputEnded) return 0
            if (sourceFormat != format) {
                clearPipelineLocked("format_changed")
                sourceFormat = format
                modelTimelineStartMs = playbackPositionProvider().coerceAtLeast(0L)
                submittedTimelineFrames = 0L
                val modelRate = installedModel?.catalog?.sampleRate ?: return byteCount
                inputResampler = StreamingStereoResampler(sampleRate, modelRate)
            }
            val converted = requireNotNull(inputResampler).appendAndDrain(dry)
            modelSamples.append(converted)
            enqueueAvailableSegmentsLocked(format)

            // AI performance adds a second, heavier transform after separation. Do not turn its
            // warm-up into audible silence: emit the original PCM until a processed block covers
            // the exact timeline position, then consume that block in place.
            if (separatedBlockTransformer != null) {
                if (tryWritePerformanceOutputLocked(buffer, frames, format)) {
                    return frames * format.channels * format.bytesPerSample
                }
                markPerformancePassthroughLocked(frames)
                return byteCount
            }

            if (!playbackStarted) {
                publishPhase(AiRealtimeSeparationPhase.BUFFERING_AUDIO)
                val initialSegments = if (separatedBlockTransformer != null) 1 else INITIAL_SEGMENTS
                if (submittedSegments < initialSegments && outputs.isEmpty()) {
                    return 0
                }
                waitForOutputLocked()
                if (outputs.isNotEmpty()) playbackStarted = true
            } else if (outputs.isEmpty()) {
                waitForOutputLocked()
            }
            if (outputs.isEmpty()) return 0
            return writeOutputLocked(buffer, frames, format)
        }
    }

    override fun drain(buffer: ByteArray, maxByteCount: Int): Int {
        synchronized(lock) {
            if (!active) return -1
            if (cachedDependency != null) return -1
            if (separatedBlockTransformer != null) {
                // Performance output is an in-place replacement for PCM that may already have
                // been emitted during warm-up. Never drain it again at EOF, or the tail is heard
                // twice. Invalidate workers that are still finishing the already-played timeline.
                generation++
                inputEnded = true
                transformTasks.clear()
                outputs.clear()
                outputOffsetFrames = 0
                lock.notifyAll()
                return -1
            }
            val format = sourceFormat ?: return -1
            if (sharedStreamTaskId.isNotBlank()) {
                if (!inputEnded) {
                    inputEnded = true
                    enqueueSharedAvailableLocked(format, allowPartial = true)
                }
                if (outputs.isEmpty() && hasPendingWorkLocked()) waitForOutputLocked()
                if (outputs.isEmpty()) return if (hasPendingWorkLocked()) 0 else -1
                val frameSize = format.channels * format.bytesPerSample
                val requestedFrames = (minOf(maxByteCount, buffer.size) / frameSize).coerceAtLeast(1)
                return writeOutputLocked(buffer, requestedFrames, format)
            }
            if (!inputEnded) {
                inputEnded = true
                enqueueTailSegmentLocked(format)
            }
            if (outputs.isEmpty() && hasPendingWorkLocked()) {
                waitForOutputLocked()
            }
            if (outputs.isEmpty()) {
                return if (!hasPendingWorkLocked()) -1 else 0
            }
            val frameSize = format.channels * format.bytesPerSample
            val requestedFrames = (minOf(maxByteCount, buffer.size) / frameSize).coerceAtLeast(1)
            return writeOutputLocked(buffer, requestedFrames, format)
        }
    }

    override fun reset(reason: String) {
        val rearm: Boolean
        synchronized(lock) {
            generation++
            clearPipelineLocked(reason)
            rearm = AiRealtimeResetPolicy.shouldRearm(
                desiredEnabled = desiredEnabled,
                ready = ready,
                cachedDirect = canUseCachedDirectLocked(),
                sharedDirect = canUseSharedDirectLocked(),
                modelOpenInFlight = modelOpenInFlight,
            )
        }
        separatedBlockTransformer?.runCatching { reset(reason) }
        if (reason == "new_play_request" && desiredEnabled && separatedBlockTransformer != null) {
            Log.i(
                TAG,
                "AI_PERF_REARM_AFTER_PLAY_REQUEST rearm=$rearm ready=$ready opening=$modelOpenInFlight " +
                    "song=${Integer.toHexString(songIdentityProvider().hashCode())}",
            )
        }
        if (rearm) {
            Log.i(TAG, "AI_REALTIME_MODEL rearm reason=$reason desired=true")
            armEnabled("reset:$reason")
        }
    }

    override fun close() {
        desiredEnabled = false
        enabled = false
        ready = false
        synchronized(lock) {
            modelOpenGeneration++
            modelOpenInFlight = false
        }
        reset("close")
        val transformer = separatedBlockTransformer
        separatedBlockTransformer = null
        transformer?.runCatching { close() }
        executor.execute {
            synchronized(lock) {
                runtimeSession?.close()
                runtimeSession = null
            }
        }
    }

    private fun processSharedLiveLocked(
        destination: ByteArray,
        requestedFrames: Int,
        format: PcmFormat,
        dry: FloatArray,
    ): Int {
        if (inputEnded) return 0
        if (sourceFormat != format) {
            clearPipelineLocked("shared_format_changed")
            sourceFormat = format
            sharedTimelineStartMs = playbackPositionProvider().coerceAtLeast(0L)
            sharedSubmittedFrames = 0L
        }
        sharedMixtureSamples.append(dry)
        enqueueSharedAvailableLocked(format, allowPartial = false)
        if (separatedBlockTransformer != null) {
            if (tryWritePerformanceOutputLocked(destination, requestedFrames, format)) {
                return requestedFrames * format.channels * format.bytesPerSample
            }
            markPerformancePassthroughLocked(requestedFrames)
            return requestedFrames * format.channels * format.bytesPerSample
        }
        if (!playbackStarted) {
            publishPhase(AiRealtimeSeparationPhase.BUFFERING_AUDIO)
            if (outputs.isEmpty() && !hasPendingWorkLocked()) return 0
            waitForOutputLocked()
            if (outputs.isNotEmpty()) playbackStarted = true
        } else if (outputs.isEmpty() && hasPendingWorkLocked()) {
            waitForOutputLocked()
        }
        if (outputs.isEmpty()) return 0
        return writeOutputLocked(destination, requestedFrames, format)
    }

    private fun enqueueSharedAvailableLocked(format: PcmFormat, allowPartial: Boolean) {
        val transformer = separatedBlockTransformer ?: return
        val latest = currentSharedStreamLocked() ?: return
        val targetFrames = maxOf(SHARED_TRANSFORM_MIN_FRAMES, format.sampleRate * SHARED_TRANSFORM_BLOCK_MS / 1000)
        while (sharedMixtureSamples.frameCount >= if (allowPartial) 1 else targetFrames) {
            val frames = if (sharedMixtureSamples.frameCount >= targetFrames) {
                targetFrames
            } else {
                sharedMixtureSamples.frameCount
            }
            val blockStartMs = sharedTimelineStartMs +
                sharedSubmittedFrames * 1000L / format.sampleRate.coerceAtLeast(1)
            val vocal = AiSharedStemWavReader.readStereoAt(
                stream = latest,
                playbackPositionMs = blockStartMs,
                outputFrames = frames,
                outputSampleRate = format.sampleRate,
            ) ?: break
            val mixture = FloatArray(frames * CHANNELS)
            sharedMixtureSamples.copyFramesTo(mixture, 0, 0, frames)
            sharedMixtureSamples.discardFrames(frames)
            transformTasks.addLast(
                TransformTask(
                    generation = generation,
                    transformer = transformer,
                    mixture = mixture,
                    vocal = vocal,
                    modelSampleRate = format.sampleRate,
                    sourceSampleRate = format.sampleRate,
                    playbackPositionMs = blockStartMs,
                    timelineStartFrame = sharedSubmittedFrames,
                    separationMs = 0L,
                )
            )
            sharedSubmittedFrames += frames.toLong()
            submittedSegments++
            scheduleTransformWorkerLocked()
            if (allowPartial) break
        }
    }

    private fun currentSharedStreamLocked(): AiSeparationLiveStreamState? {
        val taskId = sharedStreamTaskId
        if (taskId.isBlank()) return null
        return AiSeparationLiveStreamBus.state.value.takeIf { state ->
            state.taskId == taskId && state.active && state.ready
        }
    }

    private fun canUseSharedDirectLocked(): Boolean =
        separatedBlockTransformer != null &&
            sharedPlaybackSongIdentity.isNotBlank() &&
            sharedPlaybackSongIdentity == songIdentityProvider() &&
            currentSharedStreamLocked() != null

    private fun enqueueAvailableSegmentsLocked(format: PcmFormat) {
        val selected = installedModel ?: return
        val modelContract = contract ?: return
        val segmentFrames = selected.catalog.segmentSamples.toInt()
        val trim = modelContract.edgeTrimSamples.coerceAtLeast(0)
        val usefulFrames = if (modelContract.chunkMode == "uvr_mdx_center_trim") {
            segmentFrames - 2 * trim
        } else {
            segmentFrames
        }
        if (usefulFrames <= 0) return
        val requiredFutureFrames = usefulFrames + trim
        while (modelSamples.frameCount >= requiredFutureFrames) {
            val sourceStartFrame = submittedSourceFrames
            val segment = FloatArray(segmentFrames * CHANNELS)
            if (trim > 0 && previousContext.isNotEmpty()) {
                previousContext.copyInto(segment, 0)
            }
            modelSamples.copyFramesTo(
                destination = segment,
                destinationFrameOffset = trim,
                sourceFrameOffset = 0,
                frames = requiredFutureFrames,
            )
            val useful = FloatArray(usefulFrames * CHANNELS)
            modelSamples.copyFramesTo(useful, 0, 0, usefulFrames)
            previousContext = if (trim > 0) {
                useful.copyOfRange((usefulFrames - trim) * CHANNELS, useful.size)
            } else {
                FloatArray(0)
            }
            modelSamples.discardFrames(usefulFrames)
            tasks.addLast(
                SegmentTask(
                    generation = generation,
                    segment = segment,
                    usefulMixture = useful,
                    sourceSampleRate = format.sampleRate,
                    trimFrames = trim,
                    playbackPositionMs = modelTimelineStartMs +
                        submittedTimelineFrames * 1000L / selected.catalog.sampleRate.coerceAtLeast(1),
                    timelineStartFrame = sourceStartFrame,
                )
            )
            submittedSegments++
            submittedTimelineFrames += usefulFrames.toLong()
            submittedSourceFrames += usefulFrames.toLong() * format.sampleRate / selected.catalog.sampleRate
        }
        scheduleWorkerLocked()
    }

    private fun enqueueTailSegmentLocked(format: PcmFormat) {
        val selected = installedModel ?: return
        val modelContract = contract ?: return
        val remainingFrames = modelSamples.frameCount
        if (remainingFrames <= 0) {
            scheduleWorkerLocked()
            return
        }
        val segmentFrames = selected.catalog.segmentSamples.toInt()
        val trim = modelContract.edgeTrimSamples.coerceAtLeast(0)
        val segment = FloatArray(segmentFrames * CHANNELS)
        if (trim > 0 && previousContext.isNotEmpty()) {
            previousContext.copyInto(segment, 0, endIndex = minOf(previousContext.size, segment.size))
        }
        val writableFrames = minOf(remainingFrames, (segmentFrames - trim).coerceAtLeast(0))
        if (writableFrames <= 0) return
        val sourceStartFrame = submittedSourceFrames
        modelSamples.copyFramesTo(
            destination = segment,
            destinationFrameOffset = trim,
            sourceFrameOffset = 0,
            frames = writableFrames,
        )
        val useful = FloatArray(writableFrames * CHANNELS)
        modelSamples.copyFramesTo(useful, 0, 0, writableFrames)
        modelSamples.discardFrames(writableFrames)
        tasks.addLast(
            SegmentTask(
                generation = generation,
                segment = segment,
                usefulMixture = useful,
                sourceSampleRate = format.sampleRate,
                trimFrames = trim,
                playbackPositionMs = modelTimelineStartMs +
                    submittedTimelineFrames * 1000L / selected.catalog.sampleRate.coerceAtLeast(1),
                timelineStartFrame = sourceStartFrame,
            )
        )
        submittedSegments++
        submittedTimelineFrames += writableFrames.toLong()
        submittedSourceFrames += writableFrames.toLong() * format.sampleRate / selected.catalog.sampleRate
        scheduleWorkerLocked()
        Log.i(TAG, "AI_REALTIME_MODEL eof_tail frames=$writableFrames")
    }

    private fun scheduleWorkerLocked() {
        if (workerScheduled || tasks.isEmpty()) return
        workerScheduled = true
        executor.execute {
            while (true) {
                val task = synchronized(lock) {
                    if (!enabled) {
                        workerScheduled = false
                        return@execute
                    }
                    tasks.pollFirst().also {
                        if (it == null) workerScheduled = false
                    }
                } ?: return@execute
                processTask(task)
            }
        }
    }

    private fun reusableInferenceBuffer(current: ByteBuffer?, byteCount: Int): ByteBuffer {
        val buffer = if (current == null || current.capacity() < byteCount) {
            ByteBuffer.allocateDirect(byteCount).order(ByteOrder.nativeOrder())
        } else {
            current
        }
        buffer.clear()
        buffer.limit(byteCount)
        return buffer
    }

    private fun processTask(task: SegmentTask) {
        val session: AiOnnxRuntimeSession
        val selected: AiSeparationInstalledModel
        val modelContract: AiSeparationModelContract
        synchronized(lock) {
            session = runtimeSession ?: return
            selected = installedModel ?: return
            modelContract = contract ?: return
        }
        val segmentFrames = selected.catalog.segmentSamples.toInt()
        val byteCount = task.segment.size * Float.SIZE_BYTES
        val mixtureBytes = reusableInferenceBuffer(mixtureInferenceBuffer, byteCount).also {
            mixtureInferenceBuffer = it
        }
        val vocalBytes = reusableInferenceBuffer(vocalInferenceBuffer, byteCount).also {
            vocalInferenceBuffer = it
        }
        mixtureBytes.asFloatBuffer().put(task.segment)
        if (!playbackStarted) {
            publishPhase(AiRealtimeSeparationPhase.RUNNING_MODEL)
        }
        val started = SystemClock.elapsedRealtime()
        val result = AiSeparationRuntimeBridge.separateSegment(
            mixtureBuffer = mixtureBytes,
            vocalBuffer = vocalBytes,
            sampleRate = selected.catalog.sampleRate,
            segmentSamples = segmentFrames,
            contract = modelContract,
            runtimeSession = session,
        )
        if (result.isFailure) {
            fail(result.exceptionOrNull()?.message ?: "实时模型推理失败")
            return
        }
        vocalBytes.rewind()
        val usefulFrames = task.usefulMixture.size / CHANNELS
        val vocalStart = task.trimFrames * CHANNELS
        val usefulVocal = FloatArray(usefulFrames * CHANNELS)
        vocalBytes.asFloatBuffer().apply {
            position(vocalStart)
            get(usefulVocal, 0, usefulVocal.size)
        }
        val inferMs = SystemClock.elapsedRealtime() - started
        val transformer = separatedBlockTransformer
        if (transformer != null) {
            synchronized(lock) {
                if (enabled && task.generation == generation) {
                    transformTasks.addLast(
                        TransformTask(
                            generation = task.generation,
                            transformer = transformer,
                            mixture = task.usefulMixture,
                            vocal = usefulVocal,
                            modelSampleRate = selected.catalog.sampleRate,
                            sourceSampleRate = task.sourceSampleRate,
                            playbackPositionMs = task.playbackPositionMs,
                            timelineStartFrame = task.timelineStartFrame,
                            separationMs = inferMs,
                        )
                    )
                    scheduleTransformWorkerLocked()
                    lock.notifyAll()
                }
            }
            Log.i(TAG, "AI_REALTIME_MODEL separated infer_ms=$inferMs queued_transform=true")
            return
        }

        val preparedAtModelRate = FloatArray(usefulFrames * 4)
        for (frame in 0 until usefulFrames) {
            val source = frame * CHANNELS
            val target = frame * 4
            preparedAtModelRate[target] = task.usefulMixture[source]
            preparedAtModelRate[target + 1] = task.usefulMixture[source + 1]
            preparedAtModelRate[target + 2] = usefulVocal[source]
            preparedAtModelRate[target + 3] = usefulVocal[source + 1]
        }
        val output = resampleBlock(
            input = preparedAtModelRate,
            channels = 4,
            inputRate = selected.catalog.sampleRate,
            outputRate = task.sourceSampleRate,
        )
        publishOutput(task.generation, output, 4)
        Log.i(
            TAG,
            "AI_REALTIME_MODEL segment infer_ms=$inferMs out_frames=${output.size / 4}",
        )
    }

    private fun canUseCachedDirectLocked(): Boolean = cachedDependency != null &&
        (separatedBlockTransformer == null || separatedBlockTransformer?.realtimePlaybackSafe == true)

    private fun waitForOutputLocked() {
        val deadline = SystemClock.elapsedRealtime() + MAX_WAIT_MS
        while (enabled && outputs.isEmpty() && hasPendingWorkLocked()) {
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0L) break
            lock.wait(remaining.coerceAtMost(250L))
        }
    }

    private fun hasPendingWorkLocked(): Boolean =
        tasks.isNotEmpty() || workerScheduled || transformTasks.isNotEmpty() || transformWorkerScheduled

    private fun scheduleTransformWorkerLocked() {
        if (transformWorkerScheduled || transformTasks.isEmpty()) return
        transformWorkerScheduled = true
        transformExecutor.execute {
            while (true) {
                val task = synchronized(lock) {
                    transformTasks.pollFirst().also {
                        if (it == null) transformWorkerScheduled = false
                    }
                } ?: return@execute
                processTransformTask(task)
            }
        }
    }

    private fun processTransformTask(task: TransformTask) {
        val started = SystemClock.elapsedRealtime()
        val transformed = try {
            task.transformer.transformAt(
                mixtureStereo = task.mixture,
                vocalStereo = task.vocal,
                sampleRate = task.modelSampleRate,
                playbackPositionMs = task.playbackPositionMs,
            ).also { output ->
                require(output.size == task.mixture.size) {
                    "实时 AI transform 必须保持原始 frame count"
                }
            }
        } catch (error: Throwable) {
            fail(error.message ?: "实时 AI transform 失败")
            return
        }
        val output = resampleBlock(
            input = transformed,
            channels = CHANNELS,
            inputRate = task.modelSampleRate,
            outputRate = task.sourceSampleRate,
        )
        publishOutput(task.generation, output, CHANNELS, task.timelineStartFrame)
        Log.i(
            TAG,
            "AI_REALTIME_MODEL transform separation_ms=${task.separationMs} " +
                "transform_ms=${SystemClock.elapsedRealtime() - started} out_frames=${output.size / CHANNELS}",
        )
    }

    private fun publishOutput(
        taskGeneration: Long,
        output: FloatArray,
        outputChannels: Int,
        timelineStartFrame: Long = Long.MIN_VALUE,
    ) {
        synchronized(lock) {
            if (enabled && taskGeneration == generation) {
                outputs.addLast(OutputBlock(output, outputChannels, timelineStartFrame))
                if (!playbackStarted) {
                    publishPhase(AiRealtimeSeparationPhase.ACTIVE)
                    onPreparingChanged(false)
                }
                lock.notifyAll()
            }
        }
    }

    private fun markPerformancePassthroughLocked(frames: Int) {
        if (!playbackStarted) {
            playbackStarted = true
            publishPhase(AiRealtimeSeparationPhase.ACTIVE)
            onPreparingChanged(false)
        }
        performanceTimelineFrames += frames.toLong()
        if (!performanceWarmupLogged) {
            performanceWarmupLogged = true
            Log.i(
                TAG,
                "AI_PERF realtime_passthrough frames=$frames " +
                    "timeline=$performanceTimelineFrames queued=${outputs.size}",
            )
        }
    }

    private fun tryWritePerformanceOutputLocked(
        destination: ByteArray,
        requestedFrames: Int,
        format: PcmFormat,
    ): Boolean {
        if (requestedFrames <= 0) return false
        discardStalePerformanceOutputLocked()
        if (!hasPerformanceFramesLocked(requestedFrames)) return false
        val written = writeOutputLocked(destination, requestedFrames, format)
        if (written != requestedFrames * format.channels * format.bytesPerSample) {
            return false
        }
        if (!playbackStarted) {
            playbackStarted = true
            publishPhase(AiRealtimeSeparationPhase.ACTIVE)
            onPreparingChanged(false)
        }
        performanceTimelineFrames += requestedFrames.toLong()
        return true
    }

    private fun discardStalePerformanceOutputLocked() {
        while (outputs.isNotEmpty()) {
            val block = outputs.first()
            if (block.timelineStartFrame == Long.MIN_VALUE) return
            val blockFrames = block.samples.size / block.channels
            val blockPosition = block.timelineStartFrame + outputOffsetFrames
            val staleFrames = performanceTimelineFrames - blockPosition
            if (staleFrames <= 0L) return
            outputOffsetFrames += staleFrames.coerceAtMost((blockFrames - outputOffsetFrames).toLong()).toInt()
            if (outputOffsetFrames >= blockFrames) {
                outputs.removeFirst()
                outputOffsetFrames = 0
            }
        }
    }

    private fun hasPerformanceFramesLocked(requestedFrames: Int): Boolean {
        var cursor = performanceTimelineFrames
        var remaining = requestedFrames
        var first = true
        for (block in outputs) {
            if (block.timelineStartFrame == Long.MIN_VALUE) return false
            val offset = if (first) outputOffsetFrames else 0
            first = false
            val blockFrames = block.samples.size / block.channels
            val blockPosition = block.timelineStartFrame + offset
            if (blockPosition > cursor) return false
            val skip = (cursor - blockPosition).coerceAtMost((blockFrames - offset).toLong()).toInt()
            val available = blockFrames - offset - skip
            if (available <= 0) continue
            val take = minOf(remaining, available)
            cursor += take.toLong()
            remaining -= take
            if (remaining == 0) return true
        }
        return false
    }

    private fun writeOutputLocked(
        destination: ByteArray,
        requestedFrames: Int,
        format: PcmFormat,
    ): Int {
        var writtenFrames = 0
        while (writtenFrames < requestedFrames && outputs.isNotEmpty()) {
            val block = outputs.first()
            val blockFrames = block.samples.size / block.channels
            val available = blockFrames - outputOffsetFrames
            val take = minOf(requestedFrames - writtenFrames, available)
            if (block.channels == CHANNELS) {
                encodeStereo(
                    source = block.samples,
                    sourceFrameOffset = outputOffsetFrames,
                    destination = destination,
                    destinationFrameOffset = writtenFrames,
                    frames = take,
                    format = format,
                )
            } else {
                encodeMixed(
                    source = block.samples,
                    sourceFrameOffset = outputOffsetFrames,
                    destination = destination,
                    destinationFrameOffset = writtenFrames,
                    frames = take,
                    format = format,
                    selectedStem = stem,
                    selectedStrength = strength,
                )
            }
            writtenFrames += take
            outputOffsetFrames += take
            if (outputOffsetFrames >= blockFrames) {
                outputs.removeFirst()
                outputOffsetFrames = 0
            }
        }
        return writtenFrames * format.channels * format.bytesPerSample
    }

    private fun processCachedLocked(
        destination: ByteArray,
        byteCount: Int,
        requestedFrames: Int,
        format: PcmFormat,
        dry: FloatArray,
    ): Int {
        val dependency = cachedDependency ?: return byteCount
        val transformer = separatedBlockTransformer?.takeIf { it.realtimePlaybackSafe }
        val selectedFile = if (transformer != null) dependency.vocalsFile else dependency.fileFor(stem)
        if (!selectedFile.isFile) {
            cachedDependency = null
            closeCachedDecoderLocked()
            return byteCount
        }
        if (cachedDecoder == 0L || cachedDecoderPath != selectedFile.absolutePath) {
            closeCachedDecoderLocked()
            cachedDecoder = FFmpegBridge.openDecoder(
                selectedFile.absolutePath,
                format.sampleRate,
                format.bitsPerSample,
                format.channels,
            )
            if (cachedDecoder == 0L) {
                cachedDependency = null
                return byteCount
            }
            cachedDecoderPath = selectedFile.absolutePath
            FFmpegBridge.seekDecoder(cachedDecoder, playbackPositionProvider().coerceAtLeast(0L))
        }
        if (cachedDecodeBuffer.size < byteCount) cachedDecodeBuffer = ByteArray(byteCount)
        val decodedBytes = FFmpegBridge.decodeChunk(
            cachedDecoder,
            cachedDecodeBuffer,
            0,
            byteCount,
        )
        if (decodedBytes <= 0) return 0
        val cachedFormat = format.copy(floatEncoding = false)
        val cachedFrames = decodedBytes / (cachedFormat.channels * cachedFormat.bytesPerSample)
        val stemSamples = decodeStereo(cachedDecodeBuffer, cachedFrames, cachedFormat)
        val outputFrames = minOf(requestedFrames, cachedFrames)
        if (transformer != null) {
            val dryExact = if (outputFrames * CHANNELS == dry.size) dry else dry.copyOf(outputFrames * CHANNELS)
            val vocalExact = if (outputFrames * CHANNELS == stemSamples.size) stemSamples else
                stemSamples.copyOf(outputFrames * CHANNELS)
            val transformed = try {
                transformer.transformAt(
                    mixtureStereo = dryExact,
                    vocalStereo = vocalExact,
                    sampleRate = format.sampleRate,
                    playbackPositionMs = playbackPositionProvider().coerceAtLeast(0L),
                )
            } catch (error: Throwable) {
                fail(error.message ?: "MID 实时演奏失败")
                return 0
            }
            require(transformed.size == outputFrames * CHANNELS) { "MID transform frame count mismatch" }
            encodeStereo(
                source = transformed,
                sourceFrameOffset = 0,
                destination = destination,
                destinationFrameOffset = 0,
                frames = outputFrames,
                format = format,
            )
            if (!playbackStarted) {
                playbackStarted = true
                publishPhase(AiRealtimeSeparationPhase.ACTIVE)
                onPreparingChanged(false)
            }
            return outputFrames * format.channels * format.bytesPerSample
        }
        val mix = strength
        for (frame in 0 until outputFrames) {
            val sample = frame * CHANNELS
            val destinationBase = frame * format.channels * format.bytesPerSample
            val left = dry[sample] + (stemSamples[sample] - dry[sample]) * mix
            val right = dry[sample + 1] + (stemSamples[sample + 1] - dry[sample + 1]) * mix
            if (format.channels == 1) {
                writeSample(destination, destinationBase, (left + right) * 0.5f, format)
            } else {
                writeSample(destination, destinationBase, left, format)
                writeSample(
                    destination,
                    destinationBase + format.bytesPerSample,
                    right,
                    format,
                )
            }
        }
        return outputFrames * format.channels * format.bytesPerSample
    }

    private fun clearPipelineLocked(reason: String) {
        closeCachedDecoderLocked()
        tasks.clear()
        transformTasks.clear()
        outputs.clear()
        modelSamples.clear()
        sharedMixtureSamples.clear()
        inputResampler = null
        sourceFormat = null
        previousContext = FloatArray(0)
        submittedSegments = 0
        playbackStarted = false
        outputOffsetFrames = 0
        inputEnded = false
        modelTimelineStartMs = 0L
        submittedTimelineFrames = 0L
        sharedTimelineStartMs = 0L
        sharedSubmittedFrames = 0L
        submittedSourceFrames = 0L
        performanceTimelineFrames = 0L
        firstInputLogged = false
        performanceWarmupLogged = false
        lock.notifyAll()
        Log.i(
            TAG,
            "AI_REALTIME_MODEL reset reason=$reason desired=$desiredEnabled enabled=$enabled " +
                "ready=$ready opening=$modelOpenInFlight",
        )
    }

    private fun closeCachedDecoderLocked() {
        if (cachedDecoder != 0L) {
            FFmpegBridge.closeDecoder(cachedDecoder)
            cachedDecoder = 0L
        }
        cachedDecoderPath = ""
    }

    private fun fail(message: String) {
        desiredEnabled = false
        enabled = false
        ready = false
        synchronized(lock) {
            clearPipelineLocked("failure")
        }
        onPreparingChanged(false)
        publishPhase(AiRealtimeSeparationPhase.IDLE)
        onFailure(message)
        Log.e(TAG, "AI_REALTIME_MODEL failed: $message")
    }

    private fun decodeStereo(source: ByteArray, frames: Int, format: PcmFormat): FloatArray {
        val result = FloatArray(frames * CHANNELS)
        val frameSize = format.channels * format.bytesPerSample
        for (frame in 0 until frames) {
            val frameOffset = frame * frameSize
            val left = readSample(source, frameOffset, format)
            val right = if (format.channels > 1) {
                readSample(source, frameOffset + format.bytesPerSample, format)
            } else {
                left
            }
            result[frame * 2] = left
            result[frame * 2 + 1] = right
        }
        return result
    }

    private fun readSample(source: ByteArray, offset: Int, format: PcmFormat): Float {
        if (format.bytesPerSample == 2) {
            val value = (source[offset].toInt() and 0xff) or
                (source[offset + 1].toInt() shl 8)
            return value.toShort().toFloat() / 32768f
        }
        val bits = (source[offset].toInt() and 0xff) or
            ((source[offset + 1].toInt() and 0xff) shl 8) or
            ((source[offset + 2].toInt() and 0xff) shl 16) or
            (source[offset + 3].toInt() shl 24)
        return if (format.floatEncoding) {
            Float.fromBits(bits).takeIf { it.isFinite() }?.coerceIn(-1f, 1f) ?: 0f
        } else {
            bits.toFloat() / 2147483648f
        }
    }

    private fun encodeStereo(
        source: FloatArray,
        sourceFrameOffset: Int,
        destination: ByteArray,
        destinationFrameOffset: Int,
        frames: Int,
        format: PcmFormat,
    ) {
        val frameSize = format.channels * format.bytesPerSample
        for (index in 0 until frames) {
            val sourceBase = (sourceFrameOffset + index) * CHANNELS
            val left = source[sourceBase]
            val right = source[sourceBase + 1]
            val destinationBase = (destinationFrameOffset + index) * frameSize
            if (format.channels == 1) {
                writeSample(destination, destinationBase, (left + right) * 0.5f, format)
            } else {
                writeSample(destination, destinationBase, left, format)
                writeSample(destination, destinationBase + format.bytesPerSample, right, format)
            }
        }
    }

    private fun encodeMixed(
        source: FloatArray,
        sourceFrameOffset: Int,
        destination: ByteArray,
        destinationFrameOffset: Int,
        frames: Int,
        format: PcmFormat,
        selectedStem: AiSeparationStem,
        selectedStrength: Float,
    ) {
        val frameSize = format.channels * format.bytesPerSample
        for (index in 0 until frames) {
            val sourceBase = (sourceFrameOffset + index) * 4
            val mixLeft = source[sourceBase]
            val mixRight = source[sourceBase + 1]
            val vocalLeft = source[sourceBase + 2]
            val vocalRight = source[sourceBase + 3]
            val selectedLeft = if (selectedStem == AiSeparationStem.VOCALS) {
                vocalLeft
            } else {
                mixLeft - vocalLeft
            }
            val selectedRight = if (selectedStem == AiSeparationStem.VOCALS) {
                vocalRight
            } else {
                mixRight - vocalRight
            }
            val left = mixLeft + (selectedLeft - mixLeft) * selectedStrength
            val right = mixRight + (selectedRight - mixRight) * selectedStrength
            val destinationBase = (destinationFrameOffset + index) * frameSize
            if (format.channels == 1) {
                writeSample(destination, destinationBase, (left + right) * 0.5f, format)
            } else {
                writeSample(destination, destinationBase, left, format)
                writeSample(destination, destinationBase + format.bytesPerSample, right, format)
            }
        }
    }

    private fun writeSample(
        destination: ByteArray,
        offset: Int,
        sampleValue: Float,
        format: PcmFormat,
    ) {
        val sample = sampleValue.coerceIn(-1f, 1f)
        if (format.bytesPerSample == 2) {
            val value = if (sample < 0f) {
                (sample * 32768f).roundToInt()
            } else {
                (sample * 32767f).roundToInt()
            }.coerceIn(-32768, 32767)
            destination[offset] = value.toByte()
            destination[offset + 1] = (value ushr 8).toByte()
            return
        }
        val value = if (format.floatEncoding) {
            sample.toBits()
        } else {
            (sample * 2147483647f).toLong()
                .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
                .toInt()
        }
        destination[offset] = value.toByte()
        destination[offset + 1] = (value ushr 8).toByte()
        destination[offset + 2] = (value ushr 16).toByte()
        destination[offset + 3] = (value ushr 24).toByte()
    }

    private fun resampleBlock(
        input: FloatArray,
        channels: Int,
        inputRate: Int,
        outputRate: Int,
    ): FloatArray {
        if (inputRate == outputRate) return input
        val inputFrames = input.size / channels
        if (inputFrames <= 1) return input
        val outputFrames = (inputFrames.toDouble() * outputRate / inputRate)
            .roundToInt()
            .coerceAtLeast(1)
        val result = FloatArray(outputFrames * channels)
        val scale = inputRate.toDouble() / outputRate
        for (frame in 0 until outputFrames) {
            val position = (frame * scale).coerceAtMost((inputFrames - 1).toDouble())
            val lower = floor(position).toInt()
            val upper = minOf(lower + 1, inputFrames - 1)
            val fraction = (position - lower).toFloat()
            for (channel in 0 until channels) {
                val a = input[lower * channels + channel]
                val b = input[upper * channels + channel]
                result[frame * channels + channel] = a + (b - a) * fraction
            }
        }
        return result
    }

    private fun publishPhase(phase: AiRealtimeSeparationPhase) {
        if (publishedPhase == phase) return
        publishedPhase = phase
        onPhaseChanged(phase)
    }

    private data class SegmentTask(
        val generation: Long,
        val segment: FloatArray,
        val usefulMixture: FloatArray,
        val sourceSampleRate: Int,
        val trimFrames: Int,
        val playbackPositionMs: Long,
        val timelineStartFrame: Long,
    )

    private data class TransformTask(
        val generation: Long,
        val transformer: AiRealtimeSeparatedBlockTransformer,
        val mixture: FloatArray,
        val vocal: FloatArray,
        val modelSampleRate: Int,
        val sourceSampleRate: Int,
        val playbackPositionMs: Long,
        val timelineStartFrame: Long,
        val separationMs: Long,
    )

    private data class OutputBlock(
        val samples: FloatArray,
        val channels: Int,
        val timelineStartFrame: Long = Long.MIN_VALUE,
    )

    private data class PcmFormat(
        val channels: Int,
        val sampleRate: Int,
        val bitsPerSample: Int,
        val floatEncoding: Boolean,
    ) {
        val bytesPerSample: Int get() = if (bitsPerSample <= 16) 2 else 4
    }

    private class FloatQueue {
        private var values = FloatArray(16_384)
        private var start = 0
        private var end = 0
        val frameCount: Int get() = (end - start) / CHANNELS

        fun append(input: FloatArray) {
            if (input.isEmpty()) return
            ensureCapacity(input.size)
            input.copyInto(values, end)
            end += input.size
        }

        fun copyFramesTo(
            destination: FloatArray,
            destinationFrameOffset: Int,
            sourceFrameOffset: Int,
            frames: Int,
        ) {
            values.copyInto(
                destination,
                destinationFrameOffset * CHANNELS,
                start + sourceFrameOffset * CHANNELS,
                start + (sourceFrameOffset + frames) * CHANNELS,
            )
        }

        fun discardFrames(frames: Int) {
            val floats = (frames * CHANNELS).coerceAtMost(end - start)
            start += floats
            if (start == end) {
                start = 0
                end = 0
            }
        }

        fun clear() {
            start = 0
            end = 0
        }

        private fun ensureCapacity(additional: Int) {
            if (end + additional <= values.size) return
            val size = end - start
            if (size + additional <= values.size) {
                values.copyInto(values, 0, start, end)
                start = 0
                end = size
                return
            }
            var capacity = values.size
            while (capacity < size + additional) capacity *= 2
            val replacement = FloatArray(capacity)
            values.copyInto(replacement, 0, start, end)
            values = replacement
            start = 0
            end = size
        }
    }

    private class StreamingStereoResampler(
        private val inputRate: Int,
        private val outputRate: Int,
    ) {
        private var pending = FloatArray(0)
        private var position = 0.0

        fun appendAndDrain(input: FloatArray): FloatArray {
            if (inputRate == outputRate) return input
            val merged = FloatArray(pending.size + input.size)
            pending.copyInto(merged)
            input.copyInto(merged, pending.size)
            pending = merged
            val frames = pending.size / CHANNELS
            if (frames < 2) return FloatArray(0)
            val step = inputRate.toDouble() / outputRate
            val output = ArrayList<Float>()
            while (position + 1.0 < frames) {
                val lower = floor(position).toInt()
                val upper = lower + 1
                val fraction = (position - lower).toFloat()
                for (channel in 0 until CHANNELS) {
                    val a = pending[lower * CHANNELS + channel]
                    val b = pending[upper * CHANNELS + channel]
                    output.add(a + (b - a) * fraction)
                }
                position += step
            }
            val discard = floor(position).toInt().coerceAtMost(frames - 1)
            if (discard > 0) {
                pending = pending.copyOfRange(discard * CHANNELS, pending.size)
                position -= discard
            }
            return FloatArray(output.size) { output[it] }
        }
    }
}
