package com.rawsmusic.module.data.source.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.rawsmusic.core.common.source.RawResolvedAudioSource
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.core.common.utils.OnlinePlaybackDiagnostics
import com.rawsmusic.core.common.source.RawSourceMediaItem
import com.rawsmusic.core.common.source.RawSourceMediaType
import com.rawsmusic.core.common.source.RawSourceQuality
import com.rawsmusic.module.data.prefs.AppPreferences
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Playback state owned only by the online-source portal. */
enum class MusicSourcePlaybackStatus {
    Idle,
    Resolving,
    Preparing,
    Playing,
    Paused,
    Completed,
    Error,
}

data class MusicSourcePlaybackSnapshot(
    val queue: List<RawSourceMediaItem> = emptyList(),
    val currentIndex: Int = -1,
    val currentItem: RawSourceMediaItem? = null,
    val status: MusicSourcePlaybackStatus = MusicSourcePlaybackStatus.Idle,
    val requestedQuality: RawSourceQuality = RawSourceQuality.Standard,
    val resolvedQuality: RawSourceQuality = RawSourceQuality.Standard,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedPercent: Int = 0,
    val error: String = "",
) {
    val isPlaying: Boolean
        get() = status == MusicSourcePlaybackStatus.Playing

    val isBusy: Boolean
        get() = status == MusicSourcePlaybackStatus.Resolving || status == MusicSourcePlaybackStatus.Preparing

    val canGoPrevious: Boolean
        get() = currentIndex > 0 || positionMs > 5_000L

    val canGoNext: Boolean
        get() = currentIndex >= 0 && currentIndex < queue.lastIndex

    val progressFraction: Float
        get() = if (durationMs > 0L) {
            (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
}

/**
 * Online-source queue and quality controller.
 *
 * Resolved HTTP(S) sources are materialized through Android's network stack as seekable cache
 * files, then played by PlayerController/FfmpegAudioPlayer so PCM enters the shared DSP path. The
 * independent MediaPlayer remains only as a last-resort fallback when download or local decode fails.
 */
object MusicSourcePlaybackController {
    private const val TAG = "MusicSourcePlayback"
    private const val PREFERRED_QUALITY_KEY = "music_source_playback_quality_v1"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val generations = AtomicLong(0L)
    private val mutableSnapshot = MutableStateFlow(MusicSourcePlaybackSnapshot())
    val snapshot = mutableSnapshot.asStateFlow()

    private var applicationContext: Context? = null
    private var resolveJob: Job? = null
    private var backendStateJob: Job? = null
    private var cacheJob: Job? = null
    private var backend: MusicSourcePlaybackBackend? = null
    private var prepared = false
    private var completionHandledGeneration = Long.MIN_VALUE

    private var mediaPlayer: MediaPlayer? = null
    private var mediaPlayerGeneration = Long.MIN_VALUE
    private var mediaPlayerProgressJob: Job? = null
    private var mediaFallbackJob: Job? = null
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private var resumeOnFocusGain = false
    private var backendReachedPlayingGeneration = Long.MIN_VALUE
    private var fallbackAttemptedGeneration = Long.MIN_VALUE
    private var activeResolvedSource: RawResolvedAudioSource? = null
    private var activeStartPositionMs = 0L
    private var activeAutoPlay = true

    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        mainHandler.post {
            when (change) {
                AudioManager.AUDIOFOCUS_GAIN -> {
                    mediaPlayer?.setVolume(1f, 1f)
                    if (resumeOnFocusGain && isMediaPlayerPrepared()) {
                        resumeOnFocusGain = false
                        resumeMediaPlayer()
                    }
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    mediaPlayer?.setVolume(0.22f, 0.22f)
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    resumeOnFocusGain = mutableSnapshot.value.isPlaying
                    pauseMediaPlayer()
                }
                AudioManager.AUDIOFOCUS_LOSS -> {
                    resumeOnFocusGain = false
                    pauseMediaPlayer()
                    abandonAudioFocus()
                }
            }
        }
    }

    @Synchronized
    fun installBackend(newBackend: MusicSourcePlaybackBackend) {
        if (backend === newBackend) return
        backendStateJob?.cancel()
        backend = newBackend
        backendStateJob = scope.launch {
            newBackend.snapshot.collectLatest(::onBackendSnapshot)
        }
    }

    @Synchronized
    fun uninstallBackend(expectedBackend: MusicSourcePlaybackBackend) {
        if (backend !== expectedBackend) return
        backendStateJob?.cancel()
        backendStateJob = null
        backend = null
        if (mediaPlayer == null) prepared = false
    }

    fun play(
        context: Context,
        queue: List<RawSourceMediaItem>,
        index: Int,
    ) {
        applicationContext = context.applicationContext
        val playableQueue = queue.filter { it.mediaType == RawSourceMediaType.Music }
        if (playableQueue.isEmpty()) {
            mutableSnapshot.value = MusicSourcePlaybackSnapshot(
                status = MusicSourcePlaybackStatus.Error,
                error = "当前结果中没有可播放歌曲",
            )
            return
        }
        val requestedItem = queue.getOrNull(index)
        val resolvedIndex = requestedItem?.let { selected ->
            playableQueue.indexOfFirst { it.stableIdentity == selected.stableIdentity }
        }?.takeIf { it >= 0 } ?: index.coerceIn(playableQueue.indices)
        startQueueIndex(playableQueue, resolvedIndex)
    }

    fun playItem(context: Context, item: RawSourceMediaItem) {
        play(context, listOf(item), 0)
    }

    /** Persists quality and re-resolves the current item without losing position. */
    fun setPreferredQuality(quality: RawSourceQuality) {
        AppPreferences.storage.encode(PREFERRED_QUALITY_KEY, quality.name)
        val state = mutableSnapshot.value
        if (state.currentItem == null || state.currentIndex !in state.queue.indices) return
        if (state.requestedQuality == quality && state.status != MusicSourcePlaybackStatus.Error) return

        val resumePosition = currentPosition().takeIf { it > 0L } ?: state.positionMs
        val shouldAutoPlay = state.isPlaying || state.isBusy
        startQueueIndex(
            queue = state.queue,
            index = state.currentIndex,
            requestedQualityOverride = quality,
            startPositionMs = resumePosition,
            autoPlay = shouldAutoPlay,
        )
    }

    fun preferredQuality(): RawSourceQuality = loadPreferredQuality()

    fun playPause(context: Context? = applicationContext) {
        context?.let { applicationContext = it.applicationContext }
        when (mutableSnapshot.value.status) {
            MusicSourcePlaybackStatus.Playing -> pauseInternal()
            MusicSourcePlaybackStatus.Paused -> resumeInternal()
            MusicSourcePlaybackStatus.Completed,
            MusicSourcePlaybackStatus.Error -> replayCurrent()
            MusicSourcePlaybackStatus.Idle,
            MusicSourcePlaybackStatus.Resolving,
            MusicSourcePlaybackStatus.Preparing -> Unit
        }
    }

    fun next() {
        val state = mutableSnapshot.value
        if (state.canGoNext) startQueueIndex(state.queue, state.currentIndex + 1)
    }

    fun previous() {
        val state = mutableSnapshot.value
        if (state.positionMs > 5_000L && prepared) {
            seekTo(0L)
        } else if (state.currentIndex > 0) {
            startQueueIndex(state.queue, state.currentIndex - 1)
        } else if (prepared) {
            seekTo(0L)
        }
    }

    fun seekTo(positionMs: Long) {
        if (!prepared) return
        val target = positionMs.coerceIn(0L, mutableSnapshot.value.durationMs.coerceAtLeast(0L))
        if (isMediaPlayerPrepared()) {
            val player = mediaPlayer ?: return
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    player.seekTo(target, MediaPlayer.SEEK_CLOSEST)
                } else {
                    @Suppress("DEPRECATION")
                    player.seekTo(target.toInt())
                }
            }.onFailure {
                AppLogger.w(TAG, "${OnlinePlaybackDiagnostics.PREFIX} MEDIAPLAYER_SEEK_FAIL generation=$mediaPlayerGeneration targetMs=$target", it)
            }
        } else {
            backend?.seekTo(target)
        }
        mutableSnapshot.value = mutableSnapshot.value.copy(positionMs = target)
    }

    fun stop() {
        generations.incrementAndGet()
        resolveJob?.cancel()
        resolveJob = null
        mediaFallbackJob?.cancel()
        mediaFallbackJob = null
        cacheJob?.cancel()
        cacheJob = null
        prepared = false
        completionHandledGeneration = Long.MIN_VALUE
        activeResolvedSource = null
        backend?.stop()
        releaseMediaPlayer(abandonFocus = true)
        mutableSnapshot.value = MusicSourcePlaybackSnapshot()
    }

    private fun replayCurrent() {
        val state = mutableSnapshot.value
        if (state.currentIndex in state.queue.indices) {
            startQueueIndex(state.queue, state.currentIndex)
        }
    }

    private fun startQueueIndex(
        queue: List<RawSourceMediaItem>,
        index: Int,
        requestedQualityOverride: RawSourceQuality? = null,
        startPositionMs: Long = 0L,
        autoPlay: Boolean = true,
    ) {
        val context = applicationContext ?: return
        if (index !in queue.indices) return
        val item = queue[index]

        val generation = generations.incrementAndGet()
        resolveJob?.cancel()
        mediaFallbackJob?.cancel()
        mediaFallbackJob = null
        cacheJob?.cancel()
        cacheJob = null
        prepared = false
        completionHandledGeneration = Long.MIN_VALUE
        backendReachedPlayingGeneration = Long.MIN_VALUE
        fallbackAttemptedGeneration = Long.MIN_VALUE
        activeResolvedSource = null
        backend?.stop()
        releaseMediaPlayer(abandonFocus = false)
        val requestedQuality = MusicSourceAudioResolver.preferredQuality(
            item = item,
            preferred = requestedQualityOverride ?: loadPreferredQuality(),
        )
        mutableSnapshot.value = MusicSourcePlaybackSnapshot(
            queue = queue,
            currentIndex = index,
            currentItem = item,
            status = MusicSourcePlaybackStatus.Resolving,
            requestedQuality = requestedQuality,
            resolvedQuality = requestedQuality,
            durationMs = item.durationMs,
        )

        resolveJob = scope.launch {
            try {
                val resolved = MusicSourceAudioResolver.resolve(context, item, requestedQuality)
                if (generation != generations.get()) return@launch
                prepareResolved(
                    generation = generation,
                    item = item,
                    source = resolved,
                    startPositionMs = startPositionMs.coerceAtLeast(0L),
                    autoPlay = autoPlay,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (generation != generations.get()) return@launch
                fail(error.message.orEmpty().ifBlank { "在线播放失败" }.take(1_024))
            }
        }
    }

    private fun prepareResolved(
        generation: Long,
        item: RawSourceMediaItem,
        source: RawResolvedAudioSource,
        startPositionMs: Long,
        autoPlay: Boolean,
    ) {
        activeResolvedSource = source
        activeStartPositionMs = startPositionMs
        activeAutoPlay = autoPlay
        mutableSnapshot.value = mutableSnapshot.value.copy(
            status = MusicSourcePlaybackStatus.Preparing,
            resolvedQuality = source.quality,
            error = "",
            positionMs = startPositionMs,
            bufferedPercent = 0,
        )

        val activeBackend = backend
        if (activeBackend == null) {
            prepareMediaPlayer(
                generation = generation,
                source = source,
                startPositionMs = startPositionMs,
                autoPlay = autoPlay,
                reason = "backend_unavailable",
            )
            return
        }

        if (!source.url.startsWith("http://", ignoreCase = true) &&
            !source.url.startsWith("https://", ignoreCase = true)
        ) {
            playBackend(
                backend = activeBackend,
                generation = generation,
                item = item,
                source = source,
                startPositionMs = startPositionMs,
                autoPlay = autoPlay,
            )
            return
        }

        val context = applicationContext ?: run {
            fail("在线播放上下文不可用")
            return
        }
        cacheJob?.cancel()
        cacheJob = scope.launch {
            try {
                AppLogger.i(
                    TAG,
                    "${OnlinePlaybackDiagnostics.PREFIX} CACHE_PREPARE_START generation=$generation " +
                        "quality=${source.quality} url=${OnlinePlaybackDiagnostics.safeUrl(source.url)}"
                )
                val cached = OnlineAudioFileCache.materialize(
                    context = context,
                    item = item,
                    source = source,
                ) { percent, downloadedBytes, totalBytes ->
                    mainHandler.post {
                        if (generation != generations.get()) return@post
                        val current = mutableSnapshot.value
                        if (current.status == MusicSourcePlaybackStatus.Preparing) {
                            mutableSnapshot.value = current.copy(bufferedPercent = percent.coerceIn(0, 100))
                        }
                    }
                    if (percent == 0 || percent == 25 || percent == 50 || percent == 75 || percent == 100) {
                        AppLogger.i(
                            TAG,
                            "${OnlinePlaybackDiagnostics.PREFIX} CACHE_PROGRESS generation=$generation " +
                                "percent=$percent downloadedBytes=$downloadedBytes totalBytes=$totalBytes"
                        )
                    }
                }
                if (generation != generations.get()) return@launch
                val localSource = source.copy(
                    url = cached.file.absolutePath,
                    headers = emptyMap(),
                    userAgent = null,
                    expiresAtMs = null,
                )
                AppLogger.i(
                    TAG,
                    "${OnlinePlaybackDiagnostics.PREFIX} CACHE_PREPARE_END generation=$generation " +
                        "fromCache=${cached.fromCache} bytes=${cached.bytes} " +
                        "contentType=${cached.contentType.ifBlank { "unknown" }} " +
                        "file=${cached.file.name}"
                )
                playBackend(
                    backend = activeBackend,
                    generation = generation,
                    item = item,
                    source = localSource,
                    startPositionMs = startPositionMs,
                    autoPlay = autoPlay,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (generation != generations.get()) return@launch
                AppLogger.e(
                    TAG,
                    "${OnlinePlaybackDiagnostics.PREFIX} CACHE_PREPARE_FAIL generation=$generation " +
                        "message=${error.message.orEmpty().replace('\n', ' ').replace('\r', ' ').take(512)} " +
                        "url=${OnlinePlaybackDiagnostics.safeUrl(source.url)}",
                    error,
                )
                prepareMediaPlayer(
                    generation = generation,
                    source = source,
                    startPositionMs = startPositionMs,
                    autoPlay = autoPlay,
                    reason = "cache_download_failed",
                )
            }
        }
    }

    private fun playBackend(
        backend: MusicSourcePlaybackBackend,
        generation: Long,
        item: RawSourceMediaItem,
        source: RawResolvedAudioSource,
        startPositionMs: Long,
        autoPlay: Boolean,
    ) {
        backend.play(
            MusicSourcePlaybackRequest(
                generation = generation,
                item = item,
                source = source,
                startPositionMs = startPositionMs,
                autoPlay = autoPlay,
            )
        )
    }

    private fun pauseInternal() {
        if (!prepared) return
        if (isMediaPlayerPrepared()) pauseMediaPlayer() else backend?.pause()
    }

    private fun resumeInternal() {
        if (!prepared) return
        if (isMediaPlayerPrepared()) resumeMediaPlayer() else backend?.resume()
    }

    private fun onBackendSnapshot(backendSnapshot: MusicSourceBackendSnapshot) {
        val generation = generations.get()
        if (backendSnapshot.generation != generation) return
        if (mediaPlayerGeneration == generation) return
        val state = mutableSnapshot.value
        if (state.currentItem == null) return

        val duration = backendSnapshot.durationMs.takeIf { it > 0L } ?: state.durationMs
        when (backendSnapshot.status) {
            MusicSourceBackendStatus.Idle -> {
                if (state.status != MusicSourcePlaybackStatus.Error &&
                    state.status != MusicSourcePlaybackStatus.Completed
                ) {
                    prepared = false
                }
            }
            MusicSourceBackendStatus.Preparing -> {
                mutableSnapshot.value = state.copy(
                    status = MusicSourcePlaybackStatus.Preparing,
                    positionMs = backendSnapshot.positionMs.coerceAtLeast(0L),
                    durationMs = duration.coerceAtLeast(0L),
                    bufferedPercent = 0,
                    error = "",
                )
            }
            MusicSourceBackendStatus.Playing -> {
                backendReachedPlayingGeneration = generation
                prepared = true
                mutableSnapshot.value = state.copy(
                    status = MusicSourcePlaybackStatus.Playing,
                    positionMs = backendSnapshot.positionMs.coerceAtMost(duration.coerceAtLeast(0L)),
                    durationMs = duration.coerceAtLeast(0L),
                    bufferedPercent = 100,
                    error = "",
                )
            }
            MusicSourceBackendStatus.Paused -> {
                prepared = true
                mutableSnapshot.value = state.copy(
                    status = MusicSourcePlaybackStatus.Paused,
                    positionMs = backendSnapshot.positionMs.coerceAtMost(duration.coerceAtLeast(0L)),
                    durationMs = duration.coerceAtLeast(0L),
                    bufferedPercent = 100,
                    error = "",
                )
            }
            MusicSourceBackendStatus.Completed -> {
                if (completionHandledGeneration == generation) return
                completionHandledGeneration = generation
                prepared = false
                if (state.canGoNext) {
                    startQueueIndex(state.queue, state.currentIndex + 1)
                } else {
                    mutableSnapshot.value = state.copy(
                        status = MusicSourcePlaybackStatus.Completed,
                        positionMs = duration.coerceAtLeast(state.positionMs),
                        durationMs = duration.coerceAtLeast(0L),
                        bufferedPercent = 100,
                    )
                }
            }
            MusicSourceBackendStatus.Error -> {
                if (fallbackAttemptedGeneration == generation) return
                val source = activeResolvedSource
                val canFallback = source != null &&
                    source.url.startsWith("http", ignoreCase = true) &&
                    backendReachedPlayingGeneration != generation &&
                    fallbackAttemptedGeneration != generation
                if (canFallback) {
                    fallbackAttemptedGeneration = generation
                    AppLogger.w(
                        TAG,
                        "${OnlinePlaybackDiagnostics.PREFIX} MEDIAPLAYER_FALLBACK_START generation=$generation " +
                            "reason=${backendSnapshot.error.replace('\n', ' ').replace('\r', ' ').take(256)} " +
                            "url=${OnlinePlaybackDiagnostics.safeUrl(source.url)}"
                    )
                    mediaFallbackJob?.cancel()
                    mediaFallbackJob = scope.launch {
                        backend?.stop()
                        // PlayerController teardown is asynchronous. Give the failed AudioTrack/
                        // decoder session a short window to release audio focus and native output
                        // before the independent MediaPlayer requests the same route.
                        delay(160L)
                        if (generation != generations.get()) return@launch
                        prepareMediaPlayer(
                            generation = generation,
                            source = source,
                            startPositionMs = activeStartPositionMs,
                            autoPlay = activeAutoPlay,
                            reason = "ffmpeg_open_failed",
                        )
                    }
                } else {
                    prepared = false
                    mutableSnapshot.value = state.copy(
                        status = MusicSourcePlaybackStatus.Error,
                        positionMs = backendSnapshot.positionMs.coerceAtLeast(0L),
                        durationMs = duration.coerceAtLeast(0L),
                        error = backendSnapshot.error.ifBlank { "在线播放失败" }.take(1_024),
                    )
                }
            }
        }
    }

    private fun currentPosition(): Long {
        if (isMediaPlayerPrepared()) {
            return runCatching { mediaPlayer?.currentPosition?.toLong() ?: 0L }
                .getOrDefault(0L)
                .coerceAtLeast(0L)
        }
        return backend?.snapshot?.value?.positionMs
            ?.coerceAtLeast(0L)
            ?: mutableSnapshot.value.positionMs.coerceAtLeast(0L)
    }

    private fun fail(message: String) {
        prepared = false
        releaseMediaPlayer(abandonFocus = true)
        mutableSnapshot.value = mutableSnapshot.value.copy(
            status = MusicSourcePlaybackStatus.Error,
            error = message.take(1_024),
        )
    }

    private fun prepareMediaPlayer(
        generation: Long,
        source: RawResolvedAudioSource,
        startPositionMs: Long,
        autoPlay: Boolean,
        reason: String,
    ) {
        val context = applicationContext ?: run {
            fail("在线播放上下文不可用")
            return
        }
        if (generation != generations.get()) return
        releaseMediaPlayer(abandonFocus = false)

        val player = MediaPlayer()
        mediaPlayer = player
        mediaPlayerGeneration = generation
        prepared = false
        val requestHeaders = LinkedHashMap(source.headers)
        source.userAgent
            ?.takeIf { it.isNotBlank() }
            ?.let { userAgent ->
                if (requestHeaders.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                    requestHeaders["User-Agent"] = userAgent
                }
            }

        AppLogger.i(
            TAG,
            "${OnlinePlaybackDiagnostics.PREFIX} MEDIAPLAYER_OPEN_START generation=$generation reason=$reason " +
                "headers=${OnlinePlaybackDiagnostics.headerNames(requestHeaders)} " +
                "url=${OnlinePlaybackDiagnostics.safeUrl(source.url)}"
        )
        try {
            player.setAudioAttributes(audioAttributes)
            player.setOnPreparedListener { preparedPlayer ->
                if (generation != generations.get() || mediaPlayer !== preparedPlayer) return@setOnPreparedListener
                prepared = true
                val reportedDuration = runCatching { preparedPlayer.duration.toLong() }
                    .getOrDefault(0L)
                    .coerceAtLeast(0L)
                val duration = reportedDuration.takeIf { it > 0L }
                    ?: mutableSnapshot.value.currentItem?.durationMs
                    ?: 0L
                val targetPosition = if (duration > 0L) {
                    startPositionMs.coerceIn(0L, duration)
                } else {
                    startPositionMs.coerceAtLeast(0L)
                }
                mutableSnapshot.value = mutableSnapshot.value.copy(
                    status = MusicSourcePlaybackStatus.Preparing,
                    durationMs = duration,
                    positionMs = targetPosition,
                    error = "",
                )
                if (targetPosition > 0L) {
                    runCatching {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            preparedPlayer.seekTo(targetPosition, MediaPlayer.SEEK_CLOSEST)
                        } else {
                            @Suppress("DEPRECATION")
                            preparedPlayer.seekTo(targetPosition.toInt())
                        }
                    }
                }
                AppLogger.i(
                    TAG,
                    "${OnlinePlaybackDiagnostics.PREFIX} MEDIAPLAYER_PREPARED generation=$generation " +
                        "durationMs=$duration startMs=$targetPosition autoPlay=$autoPlay"
                )
                if (!autoPlay) {
                    mutableSnapshot.value = mutableSnapshot.value.copy(
                        status = MusicSourcePlaybackStatus.Paused,
                        bufferedPercent = 100,
                    )
                    return@setOnPreparedListener
                }
                if (!requestAudioFocus(context)) {
                    fail("无法获取音频焦点")
                    return@setOnPreparedListener
                }
                runCatching { preparedPlayer.start() }
                    .onSuccess {
                        mutableSnapshot.value = mutableSnapshot.value.copy(
                            status = MusicSourcePlaybackStatus.Playing,
                            bufferedPercent = 100,
                            error = "",
                        )
                        AppLogger.i(
                            TAG,
                            "${OnlinePlaybackDiagnostics.PREFIX} MEDIAPLAYER_PLAYING generation=$generation"
                        )
                        startMediaPlayerProgressUpdates(generation)
                    }
                    .onFailure { error -> fail(error.message ?: "无法开始在线播放") }
            }
            player.setOnBufferingUpdateListener { current, percent ->
                if (generation == generations.get() && mediaPlayer === current) {
                    mutableSnapshot.value = mutableSnapshot.value.copy(
                        bufferedPercent = percent.coerceIn(0, 100),
                    )
                }
            }
            player.setOnCompletionListener { completedPlayer ->
                if (generation != generations.get() || mediaPlayer !== completedPlayer) return@setOnCompletionListener
                val state = mutableSnapshot.value
                prepared = false
                if (state.canGoNext) {
                    startQueueIndex(state.queue, state.currentIndex + 1)
                } else {
                    mutableSnapshot.value = state.copy(
                        status = MusicSourcePlaybackStatus.Completed,
                        positionMs = state.durationMs,
                        bufferedPercent = 100,
                    )
                    abandonAudioFocus()
                }
            }
            player.setOnErrorListener { failedPlayer, what, extra ->
                if (generation == generations.get() && mediaPlayer === failedPlayer) {
                    AppLogger.e(
                        TAG,
                        "${OnlinePlaybackDiagnostics.PREFIX} MEDIAPLAYER_ERROR generation=$generation what=$what extra=$extra"
                    )
                    fail("在线播放器错误：$what/$extra")
                }
                true
            }
            player.setDataSource(context, Uri.parse(source.url), requestHeaders)
            player.prepareAsync()
        } catch (error: Throwable) {
            AppLogger.e(
                TAG,
                "${OnlinePlaybackDiagnostics.PREFIX} MEDIAPLAYER_OPEN_FAIL generation=$generation " +
                    "message=${error.message.orEmpty().replace('\n', ' ').replace('\r', ' ').take(512)}",
                error,
            )
            fail(error.message ?: "无法打开在线播放地址")
        }
    }

    private fun pauseMediaPlayer() {
        val player = mediaPlayer ?: return
        if (!isMediaPlayerPrepared()) return
        runCatching {
            if (player.isPlaying) player.pause()
        }.onSuccess {
            mutableSnapshot.value = mutableSnapshot.value.copy(
                status = MusicSourcePlaybackStatus.Paused,
                positionMs = currentPosition(),
            )
        }
    }

    private fun resumeMediaPlayer() {
        val context = applicationContext ?: return
        val player = mediaPlayer ?: return
        if (!isMediaPlayerPrepared()) return
        if (!requestAudioFocus(context)) {
            fail("无法获取音频焦点")
            return
        }
        runCatching { player.start() }
            .onSuccess {
                mutableSnapshot.value = mutableSnapshot.value.copy(
                    status = MusicSourcePlaybackStatus.Playing,
                    error = "",
                )
                startMediaPlayerProgressUpdates(generations.get())
            }
            .onFailure { fail(it.message ?: "无法继续在线播放") }
    }

    private fun startMediaPlayerProgressUpdates(generation: Long) {
        mediaPlayerProgressJob?.cancel()
        mediaPlayerProgressJob = scope.launch {
            while (isActive && generation == generations.get() && mediaPlayerGeneration == generation) {
                val state = mutableSnapshot.value
                if (state.currentItem == null) break
                val duration = runCatching {
                    if (isMediaPlayerPrepared()) mediaPlayer?.duration?.toLong() ?: 0L else 0L
                }.getOrDefault(0L).takeIf { it > 0L } ?: state.durationMs
                mutableSnapshot.value = state.copy(
                    positionMs = currentPosition().coerceAtMost(duration.coerceAtLeast(0L)),
                    durationMs = duration.coerceAtLeast(0L),
                )
                delay(500L)
            }
        }
    }

    private fun isMediaPlayerPrepared(): Boolean =
        prepared && mediaPlayer != null && mediaPlayerGeneration == generations.get()

    private fun releaseMediaPlayer(abandonFocus: Boolean) {
        mediaPlayerProgressJob?.cancel()
        mediaPlayerProgressJob = null
        mediaPlayer?.let { player ->
            runCatching { player.setOnPreparedListener(null) }
            runCatching { player.setOnCompletionListener(null) }
            runCatching { player.setOnErrorListener(null) }
            runCatching { player.setOnBufferingUpdateListener(null) }
            runCatching { player.reset() }
            runCatching { player.release() }
        }
        mediaPlayer = null
        mediaPlayerGeneration = Long.MIN_VALUE
        prepared = false
        if (abandonFocus) abandonAudioFocus()
    }

    private fun requestAudioFocus(context: Context): Boolean {
        val manager = audioManager ?: (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).also {
            audioManager = it
        }
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(audioAttributes)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener(focusListener, mainHandler)
                .build()
                .also { focusRequest = it }
            manager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            manager.requestAudioFocus(
                focusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN,
            )
        }
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonAudioFocus() {
        val manager = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { runCatching { manager.abandonAudioFocusRequest(it) } }
        } else {
            @Suppress("DEPRECATION")
            runCatching { manager.abandonAudioFocus(focusListener) }
        }
        resumeOnFocusGain = false
    }

    private fun loadPreferredQuality(): RawSourceQuality = runCatching {
        RawSourceQuality.valueOf(
            AppPreferences.storage.decodeString(PREFERRED_QUALITY_KEY, RawSourceQuality.Standard.name)
                ?: RawSourceQuality.Standard.name
        )
    }.getOrDefault(RawSourceQuality.Standard)
}
