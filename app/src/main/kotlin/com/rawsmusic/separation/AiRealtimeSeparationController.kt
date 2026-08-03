package com.rawsmusic.separation

import android.content.Context
import android.util.Log
import com.rawsmusic.module.player.PlayerController
import com.rawsmusic.module.player.RealtimePlaybackPcmProcessorRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class AiRealtimeSeparationState(
    val enabled: Boolean = false,
    val stem: AiSeparationStem = AiSeparationStem.VOCALS,
    val strength: Float = 1f,
    val preparing: Boolean = false,
    val phase: AiRealtimeSeparationPhase = AiRealtimeSeparationPhase.IDLE,
    val error: String = "",
)

enum class AiRealtimeSeparationPhase {
    IDLE,
    LOADING_MODEL,
    BUFFERING_AUDIO,
    RUNNING_MODEL,
    ACTIVE,
}

/**
 * Process-local control for the realtime playback DSP.
 *
 * Unlike offline AI separation, this path never creates a job, output file, URI,
 * secondary AudioTrack or persistent result. The enabled state starts disabled in
 * every new process and the native DSP operates only on the current playback PCM.
 */
object AiRealtimeSeparationController {
    private const val TAG = "AiRealtimeStem"
    private val mutable = MutableStateFlow(AiRealtimeSeparationState())
    val state: StateFlow<AiRealtimeSeparationState> = mutable.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var initialized = false
    private lateinit var appContext: Context
    private var strengthCommitJob: Job? = null
    private var resultObserverJob: Job? = null

    fun initialize(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            appContext = context.applicationContext
            mutable.value = AiRealtimeSeparationState(
                enabled = false,
                stem = AiSeparationPreferences.liveStem(appContext),
                strength = AiSeparationPreferences.liveStrength(appContext),
            )
            AiRealtimeOnnxPcmProcessor.initialize(
                context = appContext,
                onPreparing = { preparing ->
                    mutable.value = mutable.value.copy(preparing = preparing)
                },
                onPhase = { phase ->
                    mutable.value = mutable.value.copy(
                        phase = phase,
                        preparing = phase == AiRealtimeSeparationPhase.LOADING_MODEL ||
                            phase == AiRealtimeSeparationPhase.BUFFERING_AUDIO ||
                            phase == AiRealtimeSeparationPhase.RUNNING_MODEL,
                    )
                },
                onError = { message ->
                    mutable.value = mutable.value.copy(
                        enabled = false,
                        preparing = false,
                        phase = AiRealtimeSeparationPhase.IDLE,
                        error = message,
                    )
                },
                positionProvider = {
                    PlayerController.getInstanceOrNull()?.position?.value ?: 0L
                },
                currentSongIdentity = {
                    PlayerController.getInstanceOrNull()?.currentSong?.value
                        ?.let(::playbackIdentity)
                        .orEmpty()
                },
            )
            RealtimePlaybackPcmProcessorRegistry.install(AiRealtimeOnnxPcmProcessor)
            observeCompletedResults()
            initialized = true
            applyToPlayback(enabled = false)
            Log.i(TAG, "AI_REALTIME_STEM initialized backend=onnx process_local=true enabled=false")
        }
    }

    fun setEnabled(context: Context, enabled: Boolean): Result<Unit> = runCatching {
        initialize(context)
        requireNotNull(PlayerController.getInstanceOrNull()) {
            "播放器尚未就绪"
        }
        val current = mutable.value
        configureCachedResult()
        mutable.value = current.copy(
            enabled = enabled,
            preparing = enabled,
            phase = if (enabled) {
                AiRealtimeSeparationPhase.LOADING_MODEL
            } else {
                AiRealtimeSeparationPhase.IDLE
            },
            error = "",
        )
        AiRealtimeOnnxPcmProcessor.setStem(current.stem)
        AiRealtimeOnnxPcmProcessor.setStrength(current.strength)
        AiRealtimeOnnxPcmProcessor.setEnabled(enabled)
        PlayerController.getInstanceOrNull()?.setRealtimeStemEnabled(false)
        Log.i(
            TAG,
            "AI_REALTIME_STEM enabled=$enabled backend=onnx stem=${current.stem} " +
                "strength=${current.strength} storage=memory"
        )
        Unit
    }.onFailure { error ->
        mutable.value = mutable.value.copy(
            enabled = false,
            preparing = false,
            phase = AiRealtimeSeparationPhase.IDLE,
            error = error.message ?: error.javaClass.simpleName,
        )
        Log.e(TAG, "AI_REALTIME_STEM enable failed", error)
    }

    fun setStem(stem: AiSeparationStem) {
        if (!initialized) return
        AiSeparationPreferences.setLiveStem(appContext, stem)
        mutable.value = mutable.value.copy(stem = stem, error = "")
        AiRealtimeOnnxPcmProcessor.setStem(stem)
    }

    fun setStrength(strength: Float) {
        if (!initialized) return
        val safe = strength.coerceIn(0f, 1f)
        AiRealtimeOnnxPcmProcessor.setStrength(safe)
        strengthCommitJob?.cancel()
        strengthCommitJob = scope.launch {
            delay(120L)
            AiSeparationPreferences.setLiveStrength(appContext, safe)
            mutable.value = mutable.value.copy(strength = safe, error = "")
        }
    }

    fun disable() {
        if (!initialized) return
        AiRealtimeOnnxPcmProcessor.setEnabled(false)
        PlayerController.getInstanceOrNull()?.setRealtimeStemEnabled(false)
        mutable.value = mutable.value.copy(
            enabled = false,
            preparing = false,
            phase = AiRealtimeSeparationPhase.IDLE,
            error = "",
        )
        Log.i(TAG, "AI_REALTIME_STEM disabled storage=none")
    }

    private fun observeCompletedResults() {
        resultObserverJob?.cancel()
        val controller = PlayerController.getInstanceOrNull() ?: return
        val resultStore = AiSeparationResultStore.get(appContext)
        resultObserverJob = scope.launch {
            combine(controller.currentSong, resultStore.results) { song, _ -> song }
                .collect { song ->
                    val result = song?.let(resultStore::findFor)
                    AiRealtimeOnnxPcmProcessor.setCachedResult(
                        result = result,
                        songIdentity = song?.let(::playbackIdentity).orEmpty(),
                    )
                }
        }
    }

    private fun configureCachedResult() {
        val song = PlayerController.getInstanceOrNull()?.currentSong?.value
        val result = song?.let { AiSeparationResultStore.get(appContext).findFor(it) }
        AiRealtimeOnnxPcmProcessor.setCachedResult(
            result = result,
            songIdentity = song?.let(::playbackIdentity).orEmpty(),
        )
    }

    private fun playbackIdentity(song: com.rawsmusic.core.common.model.AudioFile): String =
        "${song.path}|${song.cueOffsetMs}|${song.cueTrackIndex}"

    private fun applyToPlayback(enabled: Boolean = mutable.value.enabled) {
        val current = mutable.value
        AiRealtimeOnnxPcmProcessor.setStem(current.stem)
        AiRealtimeOnnxPcmProcessor.setStrength(current.strength)
        AiRealtimeOnnxPcmProcessor.setEnabled(enabled)
        PlayerController.getInstanceOrNull()?.setRealtimeStemEnabled(false)
    }
}
