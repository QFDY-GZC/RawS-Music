package com.rawsmusic.separation

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.rawsmusic.core.common.ffmpeg.FFmpegBridge
import com.rawsmusic.module.player.RealtimePlaybackPcmProcessor
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.concurrent.Executors
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Model-backed, process-local playback separator.
 *
 * Two model chunks are decoded ahead before output starts. That initial delay lets
 * inference remain ahead of AudioTrack without writing intermediate stem files.
 */
object AiRealtimeOnnxPcmProcessor : RealtimePlaybackPcmProcessor, Closeable {
    private const val TAG = "AiRealtimeOnnx"
    private const val CHANNELS = 2
    private const val INITIAL_SEGMENTS = 2
    private const val MAX_WAIT_MS = 15_000L

    private val lock = Object()
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "RawS-AI-Realtime").apply { isDaemon = true }
    }
    private val tasks = ArrayDeque<SegmentTask>()
    private val outputs = ArrayDeque<OutputBlock>()
    private val modelSamples = FloatQueue()

    @Volatile private var initialized = false
    @Volatile private var enabled = false
    @Volatile private var ready = false
    @Volatile private var stem = AiSeparationStem.VOCALS
    @Volatile private var strength = 1f
    @Volatile private var generation = 0L
    @Volatile private var onPreparingChanged: (Boolean) -> Unit = {}
    @Volatile private var onPhaseChanged: (AiRealtimeSeparationPhase) -> Unit = {}
    @Volatile private var onFailure: (String) -> Unit = {}
    @Volatile private var publishedPhase = AiRealtimeSeparationPhase.IDLE
    @Volatile private var playbackPositionProvider: () -> Long = { 0L }
    @Volatile private var songIdentityProvider: () -> String = { "" }

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
    private var outputOffsetFrames = 0
    private var inputEnded = false
    private var cachedResult: AiSeparationResult? = null
    private var cachedSongIdentity = ""
    private var cachedDecoder = 0L
    private var cachedDecoderPath = ""
    private var cachedDecodeBuffer = ByteArray(0)

    override val active: Boolean
        get() = enabled && (ready || cachedResult != null)

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
        if (!value) {
            enabled = false
            ready = false
            onPreparingChanged(false)
            publishPhase(AiRealtimeSeparationPhase.IDLE)
            reset("disabled")
            executor.execute {
                synchronized(lock) {
                    runtimeSession?.close()
                    runtimeSession = null
                    installedModel = null
                    contract = null
                }
            }
            Log.i(TAG, "AI_REALTIME_MODEL disabled storage=memory")
            return
        }
        if (enabled && ready) return
        enabled = true
        ready = false
        onPreparingChanged(true)
        publishPhase(AiRealtimeSeparationPhase.LOADING_MODEL)
        val openGeneration = ++generation
        executor.execute {
            runCatching {
                val store = AiSeparationPluginStore.get(appContext)
                val selected = requireNotNull(store.selectedRealtimeInstalledModel()) {
                    "请先下载并选择实时人声分离模型"
                }
                val modelContract = requireNotNull(selected.catalog.contract) {
                    "当前模型不包含可执行参数"
                }
                require(selected.executable) { "当前模型不可执行" }
                require(modelContract.tensorLayout == "bcft_complex_channels") {
                    "当前高质量波形模型仅支持离线分离；实时播放请选择 MDX 模型"
                }
                val modelFile = requireNotNull(store.selectedRealtimeModelFile()) {
                    "当前实时模型文件不存在"
                }
                val session = AiOnnxRuntimeSession.open(appContext, modelFile, modelContract)
                val accepted = synchronized(lock) {
                    if (!enabled || openGeneration != generation) {
                        session.close()
                        false
                    } else {
                        runtimeSession?.close()
                        runtimeSession = session
                        installedModel = selected
                        contract = modelContract
                        clearPipelineLocked("model_ready")
                        ready = true
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
                if (openGeneration == generation) {
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

    fun setCachedResult(result: AiSeparationResult?, songIdentity: String) {
        synchronized(lock) {
            if (cachedResult?.id == result?.id && cachedSongIdentity == songIdentity) return
            closeCachedDecoderLocked()
            cachedResult = result
            cachedSongIdentity = songIdentity
            clearPipelineLocked("cached_result_changed")
        }
        if (enabled && result != null) {
            publishPhase(AiRealtimeSeparationPhase.ACTIVE)
            onPreparingChanged(false)
            Log.i(TAG, "AI_REALTIME_CACHE hit result=${result.id} format=${result.outputFormat}")
        } else if (enabled && !ready) {
            onPreparingChanged(true)
            publishPhase(AiRealtimeSeparationPhase.LOADING_MODEL)
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
            if (cachedResult != null) {
                if (cachedSongIdentity != songIdentityProvider()) {
                    closeCachedDecoderLocked()
                    cachedResult = null
                    return byteCount
                }
                return processCachedLocked(buffer, byteCount, frames, format, dry)
            }
            if (inputEnded) return 0
            if (sourceFormat != format) {
                clearPipelineLocked("format_changed")
                sourceFormat = format
                val modelRate = installedModel?.catalog?.sampleRate ?: return byteCount
                inputResampler = StreamingStereoResampler(sampleRate, modelRate)
            }
            val converted = requireNotNull(inputResampler).appendAndDrain(dry)
            modelSamples.append(converted)
            enqueueAvailableSegmentsLocked(format)

            if (!playbackStarted) {
                publishPhase(AiRealtimeSeparationPhase.BUFFERING_AUDIO)
                if (submittedSegments < INITIAL_SEGMENTS && outputs.isEmpty()) {
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
            if (cachedResult != null) return -1
            val format = sourceFormat ?: return -1
            if (!inputEnded) {
                inputEnded = true
                enqueueTailSegmentLocked(format)
            }
            if (outputs.isEmpty() && (tasks.isNotEmpty() || workerScheduled)) {
                waitForOutputLocked()
            }
            if (outputs.isEmpty()) {
                return if (tasks.isEmpty() && !workerScheduled) -1 else 0
            }
            val frameSize = format.channels * format.bytesPerSample
            val requestedFrames = (minOf(maxByteCount, buffer.size) / frameSize).coerceAtLeast(1)
            return writeOutputLocked(buffer, requestedFrames, format)
        }
    }

    override fun reset(reason: String) {
        synchronized(lock) {
            generation++
            clearPipelineLocked(reason)
        }
    }

    override fun close() {
        enabled = false
        ready = false
        reset("close")
        executor.execute {
            synchronized(lock) {
                runtimeSession?.close()
                runtimeSession = null
            }
        }
    }

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
                )
            )
            submittedSegments++
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
            )
        )
        submittedSegments++
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
        val mixtureBytes = ByteBuffer.allocateDirect(task.segment.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
        val vocalBytes = ByteBuffer.allocateDirect(task.segment.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
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
        val vocalFull = FloatArray(task.segment.size)
        vocalBytes.rewind()
        vocalBytes.asFloatBuffer().get(vocalFull)
        val usefulFrames = task.usefulMixture.size / CHANNELS
        val vocalStart = task.trimFrames * CHANNELS
        val modelOutput = FloatArray(usefulFrames * 4)
        for (frame in 0 until usefulFrames) {
            val source = frame * CHANNELS
            val vocal = vocalStart + source
            val target = frame * 4
            modelOutput[target] = task.usefulMixture[source]
            modelOutput[target + 1] = task.usefulMixture[source + 1]
            modelOutput[target + 2] = vocalFull[vocal]
            modelOutput[target + 3] = vocalFull[vocal + 1]
        }
        val output = resampleBlock(
            input = modelOutput,
            channels = 4,
            inputRate = selected.catalog.sampleRate,
            outputRate = task.sourceSampleRate,
        )
        synchronized(lock) {
            if (enabled && task.generation == generation) {
                outputs.addLast(OutputBlock(output))
                if (!playbackStarted) {
                    publishPhase(AiRealtimeSeparationPhase.ACTIVE)
                    onPreparingChanged(false)
                }
                lock.notifyAll()
            }
        }
        Log.i(
            TAG,
            "AI_REALTIME_MODEL segment infer_ms=${SystemClock.elapsedRealtime() - started} " +
                "out_frames=${output.size / 4}",
        )
    }

    private fun waitForOutputLocked() {
        val deadline = SystemClock.elapsedRealtime() + MAX_WAIT_MS
        while (enabled && outputs.isEmpty() && (tasks.isNotEmpty() || workerScheduled)) {
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0L) break
            lock.wait(remaining.coerceAtMost(250L))
        }
    }

    private fun writeOutputLocked(
        destination: ByteArray,
        requestedFrames: Int,
        format: PcmFormat,
    ): Int {
        var writtenFrames = 0
        while (writtenFrames < requestedFrames && outputs.isNotEmpty()) {
            val block = outputs.first()
            val blockFrames = block.samples.size / 4
            val available = blockFrames - outputOffsetFrames
            val take = minOf(requestedFrames - writtenFrames, available)
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
        val result = cachedResult ?: return byteCount
        val selectedFile = if (stem == AiSeparationStem.VOCALS) {
            result.vocalsFile
        } else {
            result.instrumentalFile
        }
        if (!selectedFile.isFile) {
            cachedResult = null
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
                cachedResult = null
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
        outputs.clear()
        modelSamples.clear()
        inputResampler = null
        sourceFormat = null
        previousContext = FloatArray(0)
        submittedSegments = 0
        playbackStarted = false
        outputOffsetFrames = 0
        inputEnded = false
        lock.notifyAll()
        Log.i(TAG, "AI_REALTIME_MODEL reset reason=$reason")
    }

    private fun closeCachedDecoderLocked() {
        if (cachedDecoder != 0L) {
            FFmpegBridge.closeDecoder(cachedDecoder)
            cachedDecoder = 0L
        }
        cachedDecoderPath = ""
    }

    private fun fail(message: String) {
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
    )

    private data class OutputBlock(val samples: FloatArray)

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
