package com.rawsmusic.separation

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import com.rawsmusic.core.common.ffmpeg.FFmpegBridge
import com.rawsmusic.module.player.PlayerController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.math.max

data class AiSeparationLivePlaybackState(
    val taskId: String = "",
    val stem: AiSeparationStem = AiSeparationStem.VOCALS,
    val strength: Float = 1f,
    val playing: Boolean = false,
    val waitingForData: Boolean = false,
    val positionMs: Long = 0L,
    val bufferedMs: Long = 0L,
    val error: String = "",
)

/**
 * Tails the two progressively written IEEE float WAV files on one shared frame clock.
 *
 * This path deliberately targets normal Android audio output. USB exclusive owns a separate
 * transport and must not be silently bypassed by a secondary AudioTrack.
 */
class AiSeparationLivePlayer private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val lock = Any()
    private val requestedPlaying = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)
    private val playbackGeneration = AtomicLong(0L)
    private val desiredStem = AtomicReference(AiSeparationStem.VOCALS)
    private val desiredStrength = AtomicReference(1f)
    private val mutable = MutableStateFlow(AiSeparationLivePlaybackState())
    val state: StateFlow<AiSeparationLivePlaybackState> = mutable.asStateFlow()

    @Volatile private var worker: Thread? = null
    @Volatile private var source: AiSeparationLiveStreamState? = null
    private var focusRequest: AudioFocusRequest? = null
    private var legacyFocusListener: AudioManager.OnAudioFocusChangeListener? = null

    fun playLive(
        stem: AiSeparationStem,
        strength: Float = desiredStrength.get(),
        startPositionMs: Long = 0L,
    ): Result<Unit> = play(
        AiSeparationLiveStreamBus.state.value,
        stem,
        strength,
        startPositionMs,
    )

    fun playResult(
        result: AiSeparationResult,
        stem: AiSeparationStem,
        strength: Float = desiredStrength.get(),
    ): Result<Unit> = play(
        AiSeparationLiveStreamState(
            taskId = "result:${result.id}",
            sourceName = result.sourceName,
            sampleRate = result.sampleRate,
            vocalsPath = result.vocalsFile.absolutePath,
            instrumentalPath = result.instrumentalFile.absolutePath,
            availableFrames = result.totalFrames,
            totalFrames = result.totalFrames,
            completed = true,
        ),
        stem,
        strength,
    )

    fun play(
        stream: AiSeparationLiveStreamState,
        stem: AiSeparationStem,
        strength: Float = desiredStrength.get(),
        startPositionMs: Long = 0L,
    ): Result<Unit> =
        runCatching {
            require(stream.ready || stream.active) { "分轨音频尚未准备好" }
            val controller = PlayerController.getInstanceOrNull()
            require(controller?.usbExclusiveActive?.value != true) {
                "实时分轨暂不接管 USB 独占输出"
            }
            controller?.pause()

            synchronized(lock) {
                desiredStem.set(stem)
                desiredStrength.set(strength.coerceIn(0f, 1f))
                requestedPlaying.set(true)
                val existing = worker
                if (existing?.isAlive == true && source?.taskId == stream.taskId) {
                    source = stream
                    mutable.value = mutable.value.copy(
                        stem = stem,
                        strength = desiredStrength.get(),
                        playing = true,
                        error = "",
                    )
                    Log.i(TAG, "AI_STEM_STREAM stem_switch task=${stream.taskId} stem=$stem")
                    return@runCatching
                }
                stopLocked(abandonFocus = true)
                require(requestAudioFocus()) { "无法取得音频焦点" }
                source = stream
                stopRequested.set(false)
                requestedPlaying.set(true)
                val generation = playbackGeneration.incrementAndGet()
                mutable.value = AiSeparationLivePlaybackState(
                    taskId = stream.taskId,
                    stem = stem,
                    strength = desiredStrength.get(),
                    playing = true,
                    waitingForData = true,
                )
                worker = thread(
                    start = true,
                    isDaemon = true,
                    name = "RawS-AI-Stem-Player",
                ) {
                    runPlayback(stream, generation, startPositionMs)
                }
                Log.i(
                    TAG,
                    "AI_STEM_STREAM player_start task=${stream.taskId} stem=$stem " +
                        "sampleRate=${stream.sampleRate} generation=$generation",
                )
            }
            Unit
        }.onFailure { error ->
            mutable.value = mutable.value.copy(
                playing = false,
                waitingForData = false,
                error = error.message ?: error.javaClass.simpleName,
            )
        }

    fun selectStem(stem: AiSeparationStem): Result<Unit> {
        val current = source ?: AiSeparationLiveStreamBus.state.value
        return play(current, stem, desiredStrength.get())
    }

    fun setStrength(strength: Float) {
        val safeStrength = strength.coerceIn(0f, 1f)
        desiredStrength.set(safeStrength)
        mutable.value = mutable.value.copy(strength = safeStrength)
    }

    fun toggle(): Result<Unit> = runCatching {
        synchronized(lock) {
            val running = worker?.isAlive == true
            if (!running) {
                playLive(desiredStem.get()).getOrThrow()
                return@runCatching
            }
            val resume = !requestedPlaying.get()
            requestedPlaying.set(resume)
            mutable.value = mutable.value.copy(playing = resume, error = "")
        }
    }

    fun stop() {
        synchronized(lock) {
            stopLocked(abandonFocus = true)
            mutable.value = AiSeparationLivePlaybackState()
        }
    }

    private fun stopLocked(abandonFocus: Boolean) {
        playbackGeneration.incrementAndGet()
        stopRequested.set(true)
        requestedPlaying.set(false)
        worker?.interrupt()
        worker = null
        source = null
        if (abandonFocus) abandonAudioFocus()
    }

    private fun runPlayback(
        initial: AiSeparationLiveStreamState,
        generation: Long,
        startPositionMs: Long,
    ) {
        if (initial.completed && initial.fileFor(desiredStem.get()).extension.lowercase() != "wav") {
            runEncodedResultPlayback(initial, generation, startPositionMs)
            return
        }
        var track: AudioTrack? = null
        var file: RandomAccessFile? = null
        var complementaryFile: RandomAccessFile? = null
        var openedPath = ""
        var openedStem = desiredStem.get()
        var positionFrames = (
            startPositionMs.coerceAtLeast(0L) * initial.sampleRate / 1000L
            ).coerceAtMost(initial.totalFrames.takeIf { it > 0L } ?: Long.MAX_VALUE)
        var trackStarted = false
        var waitingLogged = false
        try {
            track = createAudioTrack(initial.sampleRate)
            val byteBuffer = ByteArray(READ_FRAMES * BYTES_PER_FRAME)
            val complementaryByteBuffer = ByteArray(READ_FRAMES * BYTES_PER_FRAME)
            val floatBuffer = FloatArray(READ_FRAMES * CHANNELS)
            val complementaryFloatBuffer = FloatArray(READ_FRAMES * CHANNELS)
            while (isCurrentGeneration(generation)) {
                val latest = resolveLatestSource(initial)
                source = latest
                val selectedStem = desiredStem.get()
                val selectedFile = latest.fileFor(selectedStem)
                val otherStem = if (selectedStem == AiSeparationStem.VOCALS) {
                    AiSeparationStem.INSTRUMENTAL
                } else {
                    AiSeparationStem.VOCALS
                }
                val otherFile = latest.fileFor(otherStem)
                if (openedPath != selectedFile.absolutePath || openedStem != selectedStem) {
                    file?.close()
                    file = null
                    complementaryFile?.close()
                    complementaryFile = null
                    openedPath = selectedFile.absolutePath
                    openedStem = selectedStem
                    mutable.value = mutable.value.copy(stem = selectedStem)
                    Log.i(
                        TAG,
                        "AI_STEM_STREAM source_open task=${latest.taskId} stem=$selectedStem " +
                            "frame=$positionFrames",
                    )
                }

                if (!requestedPlaying.get()) {
                    if (trackStarted) {
                        track.pause()
                        trackStarted = false
                    }
                    sleepQuietly(PAUSE_POLL_MS)
                    continue
                }

                if (
                    !selectedFile.isFile ||
                    selectedFile.length() <= WAV_HEADER_BYTES ||
                    !otherFile.isFile ||
                    otherFile.length() <= WAV_HEADER_BYTES
                ) {
                    publishWaiting(latest, positionFrames, selectedFile)
                    if (!waitingLogged) {
                        waitingLogged = true
                        Log.i(
                            TAG,
                            "AI_STEM_STREAM waiting task=${latest.taskId} stem=$selectedStem " +
                                "frame=$positionFrames reason=file_not_ready",
                        )
                    }
                    if (!latest.active && latest.error.isNotBlank()) {
                        error(latest.error)
                    }
                    sleepQuietly(DATA_POLL_MS)
                    continue
                }
                if (file == null) {
                    file = RandomAccessFile(selectedFile, "r").apply {
                        seek(WAV_HEADER_BYTES + positionFrames * BYTES_PER_FRAME)
                    }
                    complementaryFile = RandomAccessFile(otherFile, "r").apply {
                        seek(WAV_HEADER_BYTES + positionFrames * BYTES_PER_FRAME)
                    }
                }

                val selectedPhysicalFrames = (
                    (selectedFile.length() - WAV_HEADER_BYTES).coerceAtLeast(0L) /
                        BYTES_PER_FRAME
                    )
                val complementaryPhysicalFrames = (
                    (otherFile.length() - WAV_HEADER_BYTES).coerceAtLeast(0L) /
                        BYTES_PER_FRAME
                    )
                val physicalFrames = minOf(selectedPhysicalFrames, complementaryPhysicalFrames)
                val declaredFrames = if (latest.availableFrames > 0L) {
                    minOf(physicalFrames, latest.availableFrames)
                } else {
                    physicalFrames
                }
                val readableFrames = declaredFrames - positionFrames
                val prebufferFrames = if (trackStarted) {
                    1L
                } else {
                    minOf(
                        (initial.sampleRate * PREBUFFER_SECONDS).toLong(),
                        latest.totalFrames.takeIf { it > 0L } ?: Long.MAX_VALUE,
                    )
                }
                if (readableFrames < prebufferFrames && latest.active) {
                    publishWaiting(latest, positionFrames, selectedFile)
                    if (!waitingLogged) {
                        waitingLogged = true
                        Log.i(
                            TAG,
                            "AI_STEM_STREAM waiting task=${latest.taskId} stem=$selectedStem " +
                                "frame=$positionFrames buffered=$readableFrames",
                        )
                    }
                    sleepQuietly(DATA_POLL_MS)
                    continue
                }
                if (readableFrames <= 0L) {
                    if (latest.active) {
                        publishWaiting(latest, positionFrames, selectedFile)
                        sleepQuietly(DATA_POLL_MS)
                        continue
                    }
                    break
                }

                val requestedFrames = minOf(READ_FRAMES.toLong(), readableFrames).toInt()
                val requestedBytes = requestedFrames * BYTES_PER_FRAME
                val bytesRead = file.read(byteBuffer, 0, requestedBytes)
                val complementaryBytesRead = complementaryFile?.read(
                    complementaryByteBuffer,
                    0,
                    requestedBytes,
                ) ?: -1
                if (bytesRead <= 0 || complementaryBytesRead <= 0) {
                    if (!latest.active) break
                    publishWaiting(latest, positionFrames, selectedFile)
                    sleepQuietly(DATA_POLL_MS)
                    continue
                }
                val availableBytes = minOf(bytesRead, complementaryBytesRead)
                val alignedBytes = availableBytes - availableBytes % BYTES_PER_FRAME
                val floatCount = alignedBytes / Float.SIZE_BYTES
                decodeLittleEndianFloats(byteBuffer, floatBuffer, floatCount)
                decodeLittleEndianFloats(
                    complementaryByteBuffer,
                    complementaryFloatBuffer,
                    floatCount,
                )
                val strength = desiredStrength.get()
                val complementaryGain = 1f - strength
                for (index in 0 until floatCount) {
                    floatBuffer[index] = (
                        floatBuffer[index] + complementaryFloatBuffer[index] * complementaryGain
                        ).coerceIn(-1f, 1f)
                }
                mutable.value = mutable.value.copy(strength = strength)

                if (!trackStarted) {
                    track.play()
                    trackStarted = true
                    waitingLogged = false
                }
                var floatOffset = 0
                while (
                    floatOffset < floatCount &&
                    requestedPlaying.get() &&
                    isCurrentGeneration(generation)
                ) {
                    val written = track.write(
                        floatBuffer,
                        floatOffset,
                        floatCount - floatOffset,
                        AudioTrack.WRITE_BLOCKING,
                    )
                    if (written < 0) error("AudioTrack 写入失败：$written")
                    if (written == 0) break
                    floatOffset += written
                    positionFrames += written / CHANNELS
                }
                publishPosition(latest, positionFrames, selectedFile, waiting = false)
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: Throwable) {
            Log.e(TAG, "AI_STEM_STREAM playback failed", error)
            mutable.value = mutable.value.copy(
                playing = false,
                waitingForData = false,
                error = error.message ?: error.javaClass.simpleName,
            )
        } finally {
            runCatching { file?.close() }
            runCatching { complementaryFile?.close() }
            runCatching {
                track?.pause()
                track?.flush()
                track?.release()
            }
            synchronized(lock) {
                if (worker === Thread.currentThread()) {
                    worker = null
                    requestedPlaying.set(false)
                    source = null
                    abandonAudioFocus()
                    mutable.value = mutable.value.copy(
                        playing = false,
                        waitingForData = false,
                    )
                    Log.i(
                        TAG,
                        "AI_STEM_STREAM player_stop task=${initial.taskId} " +
                            "frame=$positionFrames generation=$generation",
                    )
                }
            }
        }
    }

    private fun runEncodedResultPlayback(
        initial: AiSeparationLiveStreamState,
        generation: Long,
        startPositionMs: Long,
    ) {
        var track: AudioTrack? = null
        var selectedDecoder = 0L
        var complementaryDecoder = 0L
        var openedStem = desiredStem.get()
        var positionFrames = startPositionMs.coerceAtLeast(0L) * initial.sampleRate / 1000L
        var trackStarted = false
        val selectedBytes = ByteArray(READ_FRAMES * S16_BYTES_PER_FRAME)
        val complementaryBytes = ByteArray(READ_FRAMES * S16_BYTES_PER_FRAME)
        val output = FloatArray(READ_FRAMES * CHANNELS)

        fun closeDecoders() {
            FFmpegBridge.closeDecoder(selectedDecoder)
            FFmpegBridge.closeDecoder(complementaryDecoder)
            selectedDecoder = 0L
            complementaryDecoder = 0L
        }

        fun openDecoders(stem: AiSeparationStem) {
            closeDecoders()
            val other = if (stem == AiSeparationStem.VOCALS) {
                AiSeparationStem.INSTRUMENTAL
            } else {
                AiSeparationStem.VOCALS
            }
            selectedDecoder = FFmpegBridge.openDecoder(
                initial.fileFor(stem).absolutePath,
                initial.sampleRate,
                16,
                CHANNELS,
            )
            complementaryDecoder = FFmpegBridge.openDecoder(
                initial.fileFor(other).absolutePath,
                initial.sampleRate,
                16,
                CHANNELS,
            )
            require(selectedDecoder != 0L && complementaryDecoder != 0L) {
                "无法解码已保存的分轨音频"
            }
            val positionMs = positionFrames * 1000L / initial.sampleRate.coerceAtLeast(1)
            FFmpegBridge.seekDecoder(selectedDecoder, positionMs)
            FFmpegBridge.seekDecoder(complementaryDecoder, positionMs)
            openedStem = stem
            mutable.value = mutable.value.copy(stem = stem, waitingForData = false)
        }

        try {
            track = createAudioTrack(initial.sampleRate)
            openDecoders(openedStem)
            while (isCurrentGeneration(generation)) {
                if (!requestedPlaying.get()) {
                    if (trackStarted) {
                        track.pause()
                        trackStarted = false
                    }
                    sleepQuietly(PAUSE_POLL_MS)
                    continue
                }
                val selectedStem = desiredStem.get()
                if (selectedStem != openedStem) openDecoders(selectedStem)
                val selectedRead = FFmpegBridge.decodeChunk(
                    selectedDecoder,
                    selectedBytes,
                    0,
                    selectedBytes.size,
                )
                val complementaryRead = FFmpegBridge.decodeChunk(
                    complementaryDecoder,
                    complementaryBytes,
                    0,
                    complementaryBytes.size,
                )
                if (selectedRead <= 0 || complementaryRead <= 0) break
                val bytes = minOf(selectedRead, complementaryRead)
                    .let { it - it % S16_BYTES_PER_FRAME }
                val frames = bytes / S16_BYTES_PER_FRAME
                val strength = desiredStrength.get()
                val complementaryGain = 1f - strength
                for (frame in 0 until frames) {
                    for (channel in 0 until CHANNELS) {
                        val offset = frame * S16_BYTES_PER_FRAME + channel * 2
                        val selected = readS16(selectedBytes, offset)
                        val complementary = readS16(complementaryBytes, offset)
                        output[frame * CHANNELS + channel] =
                            (selected + complementary * complementaryGain).coerceIn(-1f, 1f)
                    }
                }
                mutable.value = mutable.value.copy(strength = strength)
                if (!trackStarted) {
                    track.play()
                    trackStarted = true
                }
                var offset = 0
                val sampleCount = frames * CHANNELS
                while (
                    offset < sampleCount &&
                    requestedPlaying.get() &&
                    isCurrentGeneration(generation)
                ) {
                    val written = track.write(
                        output,
                        offset,
                        sampleCount - offset,
                        AudioTrack.WRITE_BLOCKING,
                    )
                    if (written < 0) error("AudioTrack 写入失败：$written")
                    if (written == 0) break
                    offset += written
                    positionFrames += written / CHANNELS
                }
                publishPosition(
                    initial,
                    positionFrames,
                    initial.fileFor(selectedStem),
                    waiting = false,
                )
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: Throwable) {
            Log.e(TAG, "AI_STEM_RESULT playback failed", error)
            mutable.value = mutable.value.copy(
                playing = false,
                waitingForData = false,
                error = error.message ?: error.javaClass.simpleName,
            )
        } finally {
            closeDecoders()
            runCatching {
                track?.pause()
                track?.flush()
                track?.release()
            }
            synchronized(lock) {
                if (worker === Thread.currentThread()) {
                    worker = null
                    requestedPlaying.set(false)
                    source = null
                    abandonAudioFocus()
                    mutable.value = mutable.value.copy(
                        playing = false,
                        waitingForData = false,
                    )
                }
            }
        }
    }

    private fun readS16(buffer: ByteArray, offset: Int): Float {
        val value = (buffer[offset].toInt() and 0xff) or (buffer[offset + 1].toInt() shl 8)
        return value.toShort().toFloat() / 32768f
    }

    private fun isCurrentGeneration(generation: Long): Boolean =
        !stopRequested.get() && playbackGeneration.get() == generation

    private fun resolveLatestSource(initial: AiSeparationLiveStreamState): AiSeparationLiveStreamState {
        val live = AiSeparationLiveStreamBus.state.value
        return if (live.taskId == initial.taskId) live else source ?: initial
    }

    private fun publishWaiting(
        stream: AiSeparationLiveStreamState,
        positionFrames: Long,
        file: File,
    ) = publishPosition(stream, positionFrames, file, waiting = true)

    private fun publishPosition(
        stream: AiSeparationLiveStreamState,
        positionFrames: Long,
        file: File,
        waiting: Boolean,
    ) {
        val physicalFrames = if (file.isFile) {
            (file.length() - WAV_HEADER_BYTES).coerceAtLeast(0L) / BYTES_PER_FRAME
        } else {
            0L
        }
        val bufferedFrames = (minOf(
            physicalFrames,
            stream.availableFrames.takeIf { it > 0L } ?: physicalFrames,
        ) - positionFrames).coerceAtLeast(0L)
        mutable.value = mutable.value.copy(
            playing = requestedPlaying.get(),
            waitingForData = waiting,
            positionMs = framesToMs(positionFrames, stream.sampleRate),
            bufferedMs = framesToMs(bufferedFrames, stream.sampleRate),
            error = "",
        )
    }

    private fun createAudioTrack(sampleRate: Int): AudioTrack {
        require(sampleRate in 8_000..192_000) { "实时分轨采样率无效：$sampleRate" }
        val channelMask = AudioFormat.CHANNEL_OUT_STEREO
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            channelMask,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        require(minBuffer > 0) { "设备不支持 float PCM AudioTrack：$minBuffer" }
        val bufferBytes = max(minBuffer * 2, sampleRate * BYTES_PER_FRAME / 5)
        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(sampleRate)
            .setChannelMask(channelMask)
            .build()
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        return AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(audioFormat)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufferBytes)
            .build()
            .also {
                require(it.state == AudioTrack.STATE_INITIALIZED) {
                    "实时分轨 AudioTrack 初始化失败"
                }
            }
    }

    private fun requestAudioFocus(): Boolean {
        val listener = AudioManager.OnAudioFocusChangeListener { change ->
            if (
                change == AudioManager.AUDIOFOCUS_LOSS ||
                change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
            ) {
                requestedPlaying.set(false)
                mutable.value = mutable.value.copy(playing = false)
            }
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setOnAudioFocusChangeListener(listener)
                .build()
            focusRequest = request
            audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            legacyFocusListener = listener
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                listener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN,
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            legacyFocusListener?.let { audioManager.abandonAudioFocus(it) }
            legacyFocusListener = null
        }
    }

    private fun decodeLittleEndianFloats(
        source: ByteArray,
        destination: FloatArray,
        count: Int,
    ) {
        for (index in 0 until count) {
            val offset = index * Float.SIZE_BYTES
            val bits = (source[offset].toInt() and 0xff) or
                ((source[offset + 1].toInt() and 0xff) shl 8) or
                ((source[offset + 2].toInt() and 0xff) shl 16) or
                ((source[offset + 3].toInt() and 0xff) shl 24)
            destination[index] = Float.fromBits(bits).takeIf { it.isFinite() }
                ?.coerceIn(-1f, 1f) ?: 0f
        }
    }

    private fun framesToMs(frames: Long, sampleRate: Int): Long =
        if (sampleRate > 0) frames * 1000L / sampleRate else 0L

    private fun sleepQuietly(milliseconds: Long) {
        try {
            Thread.sleep(milliseconds)
        } catch (error: InterruptedException) {
            throw error
        }
    }

    companion object {
        private const val TAG = "AiStemStream"
        private const val CHANNELS = 2
        private const val BYTES_PER_FRAME = CHANNELS * Float.SIZE_BYTES
        private const val WAV_HEADER_BYTES = 44L
        private const val S16_BYTES_PER_FRAME = CHANNELS * 2
        private const val READ_FRAMES = 4_096
        private const val PREBUFFER_SECONDS = 2
        private const val DATA_POLL_MS = 30L
        private const val PAUSE_POLL_MS = 50L

        @Volatile private var instance: AiSeparationLivePlayer? = null

        fun get(context: Context): AiSeparationLivePlayer = instance ?: synchronized(this) {
            instance ?: AiSeparationLivePlayer(context).also { instance = it }
        }
    }
}
